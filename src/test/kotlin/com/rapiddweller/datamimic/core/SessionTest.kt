// DATAMIMIC for JetBrains IDEs
// Copyright (c) 2026 Rapiddweller Asia Co., Ltd.
// SPDX-License-Identifier: MIT

package com.rapiddweller.datamimic.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.http.HttpClient

class SessionTest {
    private val platform = FakePlatform()
    private var stored: String? = null
    private val http = HttpClient.newHttpClient()
    private var expiredCalls = 0
    private val sessions = SessionService(http, { stored }, { stored = it }, onExpired = { expiredCalls++ })
    private val transport = PlatformHttp(http, sessions, "binding-1")

    @After
    fun tearDown() = platform.close()

    @Test
    fun `login stores only the session cookie and authenticates later requests`() {
        sessions.login(platform.origin, "ada@example.com", "secret")

        assertEquals(StoredSession(platform.origin, "session-1"), sessions.current())
        assertTrue("password must never be stored", !stored.orEmpty().contains("secret"))
        assertEquals("ada@example.com", AccountApi(transport).me().email)
        assertEquals(listOf("binding-1"), platform.seenBindings)
    }

    @Test
    fun `wrong credentials surface the platform's message`() {
        val error = assertThrows(PlatformException::class.java) { sessions.login(platform.origin, "ada@example.com", "wrong") }

        assertEquals(401, error.status)
        assertTrue(error.message!!.startsWith("Incorrect email or password"))
        assertNull(sessions.current())
    }

    @Test
    fun `signing in from a URL that is not the public origin explains what to use`() {
        val sameServerOtherHost = PlatformOrigin(platform.origin.value.replace("127.0.0.1", "localhost"))

        val error = assertThrows(PlatformException::class.java) { sessions.login(sameServerOtherHost, "ada@example.com", "secret") }

        assertEquals(PlatformErrorCode.AUTH_ORIGIN_FORBIDDEN, error.code)
        assertTrue(error.message!!.contains("localhost and 127.0.0.1 are different"))
    }

    @Test
    fun `an ended session is forgotten and reported once`() {
        sessions.login(platform.origin, "ada@example.com", "secret")
        platform.sessionId = "rotated-on-the-server"

        assertThrows(SessionExpiredException::class.java) { AccountApi(transport).me() }
        assertThrows(SessionExpiredException::class.java) { AccountApi(transport).me() }
        assertNull(sessions.current())
        assertEquals(1, expiredCalls)
    }

    @Test
    fun `a rejection of an older session does not end a newer sign-in`() {
        val first = sessions.login(platform.origin, "ada@example.com", "secret")
        platform.sessionId = "session-2"
        sessions.login(platform.origin, "ada@example.com", "secret")

        sessions.expire(first)

        assertEquals("session-2", sessions.current()?.sessionId)
        assertEquals(0, expiredCalls)
    }

    @Test
    fun `unsafe requests carry the platform origin`() {
        sessions.login(platform.origin, "ada@example.com", "secret")
        platform.projectPages += """{"data":[],"meta":{"pagination":{"next_page_num":null}}}"""

        // The fake answers 403 AUTH_ORIGIN_FORBIDDEN for a POST without the exact Origin.
        assertEquals(emptyList<PlatformProject>(), ProjectsApi(transport).list())
    }

    @Test
    fun `platform error codes decode to the enum, unknown ones to UNKNOWN`() {
        sessions.login(platform.origin, "ada@example.com", "secret")
        platform.files.clear()

        val missing = assertThrows(PlatformException::class.java) { transport.send(HttpMethod.GET, "/api/v2/projects/p1/workspace/files/x.xml") }
        platform.projectTokens["taken"] = "secret" to "2026-10-01T00:00:00"
        val unknown = assertThrows(PlatformException::class.java) {
            transport.send(
                HttpMethod.POST,
                "/api/v2/projects/p1/project-access-tokens",
                RequestBody.Json("""{"name":"taken","expiration_date":"2026-10-01T00:00:00Z"}"""),
            )
        }

        assertEquals(404 to PlatformErrorCode.NOT_FOUND, missing.status to missing.code)
        assertEquals("BAD_REQUEST is not a code the plugin acts on", 400 to PlatformErrorCode.UNKNOWN, unknown.status to unknown.code)
    }

    @Test
    fun `lists all project pages`() {
        sessions.login(platform.origin, "ada@example.com", "secret")
        platform.projectPages += page(project("a"), next = 2)
        platform.projectPages += page(project("b"), next = null)

        assertEquals(listOf("a", "b"), ProjectsApi(transport).list().map { it.name })
    }

    @Test
    fun `logout revokes the session on the platform and forgets it`() {
        sessions.login(platform.origin, "ada@example.com", "secret")

        assertTrue(sessions.logout())
        assertEquals(listOf("session-1"), platform.revokedSessions)
        assertNull(sessions.current())
    }

    @Test
    fun `platform origin rejects anything that is not a bare origin`() {
        assertEquals("https://dm.example", PlatformOrigin.parse(" https://dm.example/ ").value)
        assertEquals("http://localhost:3000", PlatformOrigin.parse("http://localhost:3000").value)
        assertEquals("https://dm.example", PlatformOrigin.parse("HTTPS://DM.Example:443").value)
        assertEquals("http://dm.example", PlatformOrigin.parse("http://dm.example:80/").value)
        for (bad in listOf("ftp://dm.example", "https://u:p@dm.example", "https://dm.example?x=1", "https://dm.example#a", "https://dm.example/app")) {
            assertThrows(bad, IllegalArgumentException::class.java) { PlatformOrigin.parse(bad) }
        }
        assertEquals("wss://dm.example:8443/x", PlatformOrigin.parse("https://dm.example:8443").webSocketUrl("/x").toString())
    }

    @Test
    fun `core stays free of IDE APIs so it can be tested without an IDE`() {
        val offenders = File("src/main/kotlin/com/rapiddweller/datamimic/core").walk()
            .filter { it.isFile && it.readText().contains("import com.intellij") }
            .map { it.name }
            .toList()
        assertEquals(emptyList<String>(), offenders)
    }

    private fun project(name: String) = """{"identifier":"id-$name","name":"$name","type":"standard","tc_update":"2026-09-01T00:00:00Z"}"""

    private fun page(vararg projects: String, next: Int?) =
        """{"data":[${projects.joinToString(",")}],"meta":{"pagination":{"next_page_num":${next ?: "null"}}}}"""
}
