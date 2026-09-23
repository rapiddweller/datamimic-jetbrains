// DATAMIMIC for JetBrains IDEs
// Copyright (c) 2026 Rapiddweller Asia Co., Ltd.
// SPDX-License-Identifier: MIT

package com.rapiddweller.datamimic.core.workspace

import com.rapiddweller.datamimic.core.PlatformOrigin
import com.rapiddweller.datamimic.core.SessionService
import com.rapiddweller.datamimic.core.StoredSession
import com.rapiddweller.datamimic.core.json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.http.HttpClient
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Runs [WorkspaceEventStream] against a minimal RFC 6455 server that behaves like the platform's events route. */
class WorkspaceEventStreamTest {
    private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    private val origin = PlatformOrigin("http://127.0.0.1:${server.localPort}")
    private val handshakes = LinkedBlockingQueue<Map<String, String>>()
    private val events = CopyOnWriteArrayList<WorkspaceEvent>()
    private val states = LinkedBlockingQueue<StreamState>()
    private var stored: String? = json.encodeToString(StoredSession.serializer(), StoredSession(origin, "session-1"))
    private val stream = WorkspaceEventStream(
        HttpClient.newHttpClient(),
        SessionService(HttpClient.newHttpClient(), { stored }, { stored = it }),
        "binding-1",
        "p1",
        onEvent = events::add,
        onState = states::add,
    )

    @After
    fun tearDown() {
        stream.close()
        server.close()
    }

    @Test
    fun `authenticates with the session cookie and origin, goes live on the snapshot, and stops when superseded`() {
        serve { out ->
            out.textFrame(SNAPSHOT)
            out.closeFrame(4001)
        }

        stream.start()

        val handshake = handshakes.poll(5, TimeUnit.SECONDS)!!
        assertEquals("GET /api/v2/projects/p1/workspace/events?client_binding_id=binding-1 HTTP/1.1", handshake.getValue("request-line"))
        assertEquals(origin.value, handshake.getValue("origin"))
        assertEquals("datamimic_session=session-1", handshake.getValue("cookie"))
        assertEquals(listOf(StreamState.CONNECTING, StreamState.LIVE, StreamState.SUPERSEDED), awaitStates(StreamState.SUPERSEDED))
        assertTrue(events.single() is WorkspaceEvent.Snapshot)
    }

    @Test
    fun `a stream closed while it waits to reconnect never connects again`() {
        serve { out -> out.closeFrame(1011) }

        stream.start()
        handshakes.poll(5, TimeUnit.SECONDS)!!
        Thread.sleep(50)
        stream.close()
        Thread.sleep(1_000)

        assertEquals("no reconnect after close", 0, handshakes.size)
        assertEquals(StreamState.CLOSED, generateSequence { states.poll() }.last())
    }

    private fun awaitStates(last: StreamState): List<StreamState> {
        val seen = mutableListOf<StreamState>()
        while (seen.lastOrNull() != last) seen += states.poll(5, TimeUnit.SECONDS) ?: break
        return seen
    }

    /** Accepts connections until the test ends; each one gets the handshake followed by [afterHandshake]. */
    private fun serve(afterHandshake: (OutputStream) -> Unit) = thread(isDaemon = true) {
        while (!server.isClosed) {
            val socket = runCatching { server.accept() }.getOrNull() ?: break
            thread(isDaemon = true) { answer(socket, afterHandshake) }
        }
    }

    private fun answer(socket: java.net.Socket, afterHandshake: (OutputStream) -> Unit) {
        socket.use {
            val reader = socket.getInputStream().bufferedReader()
            val lines = generateSequence { reader.readLine() }.takeWhile { it.isNotEmpty() }.toList()
            val headers = lines.drop(1).associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() } +
                ("request-line" to lines.first())
            handshakes += headers
            val accept = Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-1").digest((headers.getValue("sec-websocket-key") + WS_GUID).toByteArray()),
            )
            val out = socket.getOutputStream()
            out.write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n".toByteArray())
            afterHandshake(out)
            out.flush()
            Thread.sleep(500)
        }
    }

    private fun OutputStream.textFrame(text: String) {
        val payload = text.toByteArray()
        write(0x81)
        if (payload.size < 126) {
            write(payload.size)
        } else {
            write(126)
            write(payload.size shr 8)
            write(payload.size and 0xFF)
        }
        write(payload)
    }

    private fun OutputStream.closeFrame(code: Int) {
        write(byteArrayOf(0x88.toByte(), 2, (code shr 8).toByte(), (code and 0xFF).toByte()))
    }

    private companion object {
        const val WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        const val SNAPSHOT = """{"type":"snapshot","tree_revision":"r1","transport":{"keepalive_interval_seconds":5,"stale_after_seconds":15},
            "locks":{},"collaboration":{"active_client_count":1,"edit_mode":"automatic"}}"""
    }
}
