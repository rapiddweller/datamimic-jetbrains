// DATAMIMIC for JetBrains IDEs
// Copyright (c) 2026 Rapiddweller Asia Co., Ltd.
// SPDX-License-Identifier: MIT

package com.rapiddweller.datamimic.core.mcp

import com.rapiddweller.datamimic.core.FakePlatform
import com.rapiddweller.datamimic.core.PlatformHttp
import com.rapiddweller.datamimic.core.SessionService
import com.rapiddweller.datamimic.core.json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.http.HttpClient
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class McpTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val platform = FakePlatform()
    private var stored: String? = null
    private val http = HttpClient.newHttpClient()
    private val sessions = SessionService(http, { stored }, { stored = it })
    private val tokens = ProjectTokensApi(PlatformHttp(http, sessions, "ide-binding"))
    private var now = Instant.parse("2026-09-23T08:00:00Z")
    private val clock = object : Clock() {
        override fun instant() = now
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId) = this
    }
    private val access = McpAccess(tokens, "IU", "ide-binding", clock)

    @Before
    fun signIn() {
        sessions.login(platform.origin, "ada@example.com", "secret")
    }

    @After
    fun tearDown() = platform.close()

    @Test
    fun `agents get the project MCP url, a project token and their own client binding`() {
        val server = access.server(platform.origin, "p1")

        assertEquals("${platform.origin.value}/api/v2/mcp/projects/p1", server.url)
        assertEquals("Bearer secret-1", server.headers["Authorization"])
        val binding = server.headers.getValue("X-DATAMIMIC-Client-Binding")
        assertTrue(binding.matches(Regex("^[A-Za-z0-9_.:-]{1,128}$")))
        assertNotEquals("agents never share the IDE's own lock identity", "ide-binding", binding)
        assertEquals(Instant.parse("2026-09-24T08:00:00Z"), server.expiresAt)
        assertEquals(Instant.parse("2026-09-24T04:00:00Z"), access.renewalDue(server))
    }

    @Test
    fun `a token with enough time left is reused, a nearly expired one is replaced`() {
        val first = access.server(platform.origin, "p1")
        now = now.plus(Duration.ofHours(12))
        assertEquals(first.headers["Authorization"], access.server(platform.origin, "p1").headers["Authorization"])

        now = now.plus(Duration.ofHours(9))
        val renewed = access.server(platform.origin, "p1")

        assertEquals("Bearer secret-2", renewed.headers["Authorization"])
        assertEquals(1, platform.projectTokens.size)
    }

    @Test
    fun `revoking removes the token and tolerates one that is already gone`() {
        access.server(platform.origin, "p1")

        access.revoke(platform.origin, "p1")
        access.revoke(platform.origin, "p1")

        assertTrue(platform.projectTokens.isEmpty())
    }

    @Test
    fun `claude code gets the server in its local scope, replacing an earlier registration`() {
        assumeFalse(isWindows())
        val calls = temp.newFile("calls.txt")
        val cli = script("claude", "echo \"$(pwd)|$*\" >> '${calls.path}'")
        val projectDir = temp.newFolder("project")
        val agent = ClaudeCodeAgent(cli.toPath(), projectDir.toPath())

        agent.register(McpServer("https://dm.example/api/v2/mcp/projects/p1", mapOf("Authorization" to "Bearer t"), Instant.EPOCH))
        agent.unregister()

        val lines = calls.readLines()
        assertTrue(lines.all { it.startsWith(projectDir.canonicalPath + "|") || it.startsWith(projectDir.path + "|") })
        assertEquals(
            listOf(
                "mcp remove --scope local datamimic-platform",
                "mcp add --transport http --scope local datamimic-platform https://dm.example/api/v2/mcp/projects/p1 --header Authorization: Bearer t",
                "mcp remove --scope local datamimic-platform",
            ),
            lines.map { it.substringAfter('|') },
        )
    }

    @Test
    fun `a failing claude code registration names the CLI's error`() {
        assumeFalse(isWindows())
        val cli = script("claude", "case \"$2\" in add) echo 'Error: invalid header' >&2; exit 1 ;; esac")

        val error = assertThrows(McpAgentException::class.java) {
            ClaudeCodeAgent(cli.toPath(), temp.newFolder().toPath()).register(McpServer("https://x", emptyMap(), Instant.EPOCH))
        }
        assertEquals("Claude Code: Error: invalid header", error.message)
    }

    @Test
    fun `junie gets the server in the project's mcp json, next to the user's own servers, and kept out of git`() {
        val projectDir = temp.newFolder("junie-project")
        File(projectDir, ".git/info").mkdirs()
        val config = File(projectDir, ".junie/mcp/mcp.json").apply {
            parentFile.mkdirs()
            writeText("""{"mcpServers":{"github":{"command":"docker"}},"other":true}""")
        }
        val agent = JunieAgent(projectDir.toPath(), GitIgnore(projectDir.toPath(), git = null))

        agent.register(McpServer("https://dm.example/mcp", mapOf("Authorization" to "Bearer t"), Instant.EPOCH))
        agent.register(McpServer("https://dm.example/mcp", mapOf("Authorization" to "Bearer t2"), Instant.EPOCH))

        val written = json.parseToJsonElement(config.readText()).jsonObject
        val servers = written.getValue("mcpServers").jsonObject
        assertEquals(setOf("github", "datamimic-platform"), servers.keys)
        assertEquals("Bearer t2", servers.getValue("datamimic-platform").jsonObject.getValue("headers").jsonObject.getValue("Authorization").jsonPrimitive.content)
        assertTrue("other settings survive", "other" in written)
        assertEquals(listOf("/.junie/mcp/mcp.json"), File(projectDir, ".git/info/exclude").readLines().filter { it.isNotBlank() })
        if (!isWindows()) assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(config.toPath())))

        agent.unregister()
        assertEquals(setOf("github"), json.parseToJsonElement(config.readText()).jsonObject.getValue("mcpServers").jsonObject.keys)
    }

    @Test
    fun `junie's config is removed when only the DATAMIMIC server was in it`() {
        val projectDir = temp.newFolder("fresh")
        val agent = JunieAgent(projectDir.toPath(), GitIgnore(projectDir.toPath(), git = null))

        agent.register(McpServer("https://dm.example/mcp", emptyMap(), Instant.EPOCH))
        agent.unregister()

        assertFalse(File(projectDir, ".junie/mcp/mcp.json").exists())
    }

    @Test
    fun `no token is written into a junie config that git tracks`() {
        assumeFalse(isWindows())
        val projectDir = temp.newFolder("tracked").apply { File(this, ".git").mkdirs() }
        val git = script("git", "exit 0")

        val error = assertThrows(McpAgentException::class.java) {
            JunieAgent(projectDir.toPath(), GitIgnore(projectDir.toPath(), git.toPath())).register(McpServer("https://x", emptyMap(), Instant.EPOCH))
        }
        assertTrue(error.message!!.contains("tracked by Git"))
        assertFalse(File(projectDir, ".junie/mcp/mcp.json").exists())
    }

    private fun script(name: String, body: String): File = File(temp.newFolder(), name).apply {
        writeText("#!/bin/sh\n$body\n")
        setExecutable(true)
    }

    private fun isWindows() = System.getProperty("os.name").startsWith("Windows")
}
