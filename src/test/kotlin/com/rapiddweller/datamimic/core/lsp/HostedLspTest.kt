// DATAMIMIC for JetBrains IDEs
// Copyright (c) 2026 Rapiddweller Asia Co., Ltd.
// SPDX-License-Identifier: MIT

package com.rapiddweller.datamimic.core.lsp

import com.rapiddweller.datamimic.core.FakePlatform
import com.rapiddweller.datamimic.core.FakeWebSocketServer
import com.rapiddweller.datamimic.core.PlatformErrorCode
import com.rapiddweller.datamimic.core.PlatformException
import com.rapiddweller.datamimic.core.PlatformHttp
import com.rapiddweller.datamimic.core.SessionService
import com.rapiddweller.datamimic.core.StoredSession
import com.rapiddweller.datamimic.core.json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.net.InetAddress
import java.net.Socket
import java.net.http.HttpClient
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class HostedLspTest {
    private val upstream = FakeWebSocketServer()
    private var stored: String? = json.encodeToString(StoredSession.serializer(), StoredSession(upstream.origin, "session-1"))
    private val sessions = SessionService(HttpClient.newHttpClient(), { stored }, { stored = it })
    private val rejected = CountDownLatch(1)
    private val links = LinkedBlockingQueue<LspLink>()
    private val bridge = LspBridge(HttpClient.newHttpClient(), sessions, "binding-1", "p1", onRejected = { rejected.countDown() }, onLink = links::add)

    @After
    fun tearDown() {
        bridge.close()
        upstream.close()
    }

    @Test
    fun `document URIs are encoded exactly like the platform encodes them`() {
        // Expected values from Python's quote(segment, safe="-._~"), which the platform uses.
        assertEquals("datamimic://project/p1", DocumentUris.root("p1"))
        assertEquals("datamimic://project/p1/model/datamimic.xml", DocumentUris.of("p1", "model/datamimic.xml"))
        assertEquals(
            "datamimic://project/Proj%2F1/data/Stra%C3%9Fe%20%231%20%2B%20100%25.xml",
            DocumentUris.of("Proj/1", "data/Straße #1 + 100%.xml"),
        )
        assertEquals("data/Straße #1 + 100%.xml", DocumentUris.pathOf(DocumentUris.of("p1", "data/Straße #1 + 100%.xml"), "p1"))
    }

    @Test
    fun `only canonical document URIs of the project map to a path`() {
        for (uri in listOf(
            "datamimic://project/p2/a.xml",
            "datamimic://project/p1/a%20b%2g.xml",
            "datamimic://project/p1/stra%c3%9fe.xml",
            "datamimic://project/p1/a b.xml",
            "datamimic://project/p1/%2E%2E/secret.xml",
            "datamimic://project/p1/a%2Fb.xml",
            "datamimic://project/p1/%C3.xml",
            "file:///p1/a.xml",
        )) {
            assertNull(uri, DocumentUris.pathOf(uri, "p1"))
        }
    }

    @Test
    fun `lsp init returns the root and passes the platform's options on, and a turned-off server can be turned on`() {
        FakePlatform().use { platform ->
            val http = HttpClient.newHttpClient()
            var session: String? = null
            val platformSessions = SessionService(http, { session }, { session = it })
            platformSessions.login(platform.origin, "ada@example.com", "secret")
            val api = LspApi(PlatformHttp(http, platformSessions, "binding-1"))

            val init = api.init("p1")
            val options = json.parseToJsonElement(init.initializationOptions("s3cret")).jsonObject

            assertEquals("datamimic://project/p1", init.rootUri)
            assertEquals("t1", options.getValue("tenant_id").jsonPrimitive.content)
            assertEquals("s3cret", options.getValue(LspBridge.SECRET_OPTION).jsonPrimitive.content)

            platform.lspEnabled = false
            assertEquals(PlatformErrorCode.LSP_DISABLED, assertThrows(PlatformException::class.java) { api.init("p1") }.code)

            api.setEnabled("p1", true)
            assertEquals("only the language server flag is sent, so other settings stay", """{"config":{"lsp":{"enabled":true}}}""", platform.projectUpdates.single())
            assertEquals("datamimic://project/p1", api.init("p1").rootUri)
        }
    }

    @Test
    fun `the IDE's initialize reaches the platform without the secret, and binary or text answers come back framed`() {
        upstream.serve()
        Socket(InetAddress.getLoopbackAddress(), bridge.port).use { ide ->
            ide.soTimeout = 5_000
            ide.getOutputStream().write(framed(initialize(bridge.secret)))

            val handshake = upstream.handshakes.poll(5, TimeUnit.SECONDS)!!
            assertEquals("GET /lsp/p1?client_binding_id=binding-1 HTTP/1.1", handshake.getValue("request-line"))
            assertEquals("datamimic_session=session-1", handshake.getValue("cookie"))
            assertEquals(upstream.origin.value, handshake.getValue("origin"))
            val forwarded = json.parseToJsonElement(upstream.received.poll(5, TimeUnit.SECONDS)!!).jsonObject
            val options = forwarded.getValue("params").jsonObject.getValue("initializationOptions") as JsonObject
            assertEquals(setOf("root_uri"), options.keys)

            // The platform's language server answers in binary frames; text frames count too.
            val answer = """{"jsonrpc":"2.0","id":1,"result":{"label":"Straße ✓"}}"""
            val notice = """{"jsonrpc":"2.0","method":"window/logMessage","params":{"message":"ok"}}"""
            val connection = upstream.connections.poll(5, TimeUnit.SECONDS)!!
            connection.binary(answer)
            connection.text(notice)
            val input = BufferedInputStream(ide.getInputStream())
            assertEquals(answer, readMessage(input))
            assertEquals(notice, readMessage(input))

            ide.getOutputStream().write(framed("""{"jsonrpc":"2.0","method":"initialized","params":{}}"""))
            assertEquals("""{"jsonrpc":"2.0","method":"initialized","params":{}}""", upstream.received.poll(5, TimeUnit.SECONDS))
            assertEquals(LspLink.OPEN, links.poll(5, TimeUnit.SECONDS))
        }
        assertEquals("closing the IDE side ends the link", LspLink.CLOSED, links.poll(5, TimeUnit.SECONDS))
    }

    @Test
    fun `an open connection is kept alive with pings while the editor is idle`() {
        LspBridge(HttpClient.newHttpClient(), sessions, "binding-1", "p1", onRejected = {}, pingIntervalMs = 100).use { quick ->
            upstream.serve()
            Socket(InetAddress.getLoopbackAddress(), quick.port).use { ide ->
                ide.getOutputStream().write(framed(initialize(quick.secret)))
                upstream.received.poll(5, TimeUnit.SECONDS)!!

                Thread.sleep(600)

                assertTrue("pings so far: ${upstream.pings}", upstream.pings >= 3)
            }
        }
    }

    @Test
    fun `a local client without the secret never reaches the platform`() {
        upstream.serve()
        Socket(InetAddress.getLoopbackAddress(), bridge.port).use { intruder ->
            intruder.soTimeout = 5_000
            intruder.getOutputStream().write(framed(initialize("guessed")))

            assertEquals("the bridge hangs up", -1, intruder.getInputStream().read())
            assertNull(upstream.handshakes.poll(300, TimeUnit.MILLISECONDS))
        }
    }

    @Test
    fun `a policy close from the platform ends the IDE's connection and is reported`() {
        upstream.serve()
        Socket(InetAddress.getLoopbackAddress(), bridge.port).use { ide ->
            ide.getOutputStream().write(framed(initialize(bridge.secret)))
            upstream.received.poll(5, TimeUnit.SECONDS)!!

            upstream.connections.poll(5, TimeUnit.SECONDS)!!.close(1008)

            ide.soTimeout = 5_000
            assertEquals(-1, ide.getInputStream().read())
            assertTrue(rejected.await(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `a handshake the platform refuses is reported once and ends the IDE's connection`() {
        upstream.refuseWith = 403
        upstream.serve()
        Socket(InetAddress.getLoopbackAddress(), bridge.port).use { ide ->
            ide.soTimeout = 5_000
            ide.getOutputStream().write(framed(initialize(bridge.secret)))

            assertEquals(-1, ide.getInputStream().read())
            assertTrue(rejected.await(5, TimeUnit.SECONDS))
            assertEquals(1, upstream.handshakes.size)
        }
    }

    @Test
    fun `framing counts bytes, not characters`() {
        val message = """{"text":"äöü €"}"""
        assertEquals(message, readMessage(framed(message).inputStream()))
        assertFalse(message.length == message.toByteArray(UTF_8).size)
    }

    private fun initialize(secret: String) =
        """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"initializationOptions":{"root_uri":"datamimic://project/p1","${LspBridge.SECRET_OPTION}":"$secret"}}}"""

    private fun framed(message: String): ByteArray {
        val body = message.toByteArray(UTF_8)
        return "Content-Length: ${body.size}\r\nContent-Type: application/vscode-jsonrpc; charset=utf-8\r\n\r\n".toByteArray(UTF_8) + body
    }
}
