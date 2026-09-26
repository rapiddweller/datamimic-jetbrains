// DATAMIMIC for JetBrains IDEs
// Copyright (c) 2026 Rapiddweller Asia Co., Ltd.
// SPDX-License-Identifier: MIT

package com.rapiddweller.datamimic.core.mcp

import com.rapiddweller.datamimic.core.FakePlatform
import com.rapiddweller.datamimic.core.PlatformHttp
import com.rapiddweller.datamimic.core.SessionService
import com.rapiddweller.datamimic.core.json
import com.rapiddweller.datamimic.core.process.runCommand
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.http.HttpClient
import java.nio.file.Files
import java.nio.file.Path
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
        val rootGuidance = File(projectDir, "AGENTS.md").apply { writeText("user root guidance\n") }
        val agent = JunieAgent(projectDir.toPath(), GitIgnore(projectDir.toPath(), git = null))

        agent.register(McpServer("https://dm.example/mcp", mapOf("Authorization" to "Bearer t"), Instant.EPOCH))
        agent.register(McpServer("https://dm.example/mcp", mapOf("Authorization" to "Bearer t2"), Instant.EPOCH))

        val written = json.parseToJsonElement(config.readText()).jsonObject
        val servers = written.getValue("mcpServers").jsonObject
        assertEquals(setOf("github", "datamimic-platform"), servers.keys)
        assertEquals("Bearer t2", servers.getValue("datamimic-platform").jsonObject.getValue("headers").jsonObject.getValue("Authorization").jsonPrimitive.content)
        assertTrue("other settings survive", "other" in written)
        assertEquals(
            setOf("/.junie/mcp/mcp.json", "/.junie/rules/datamimic.md"),
            File(projectDir, ".git/info/exclude").readLines().filter { it.isNotBlank() }.toSet(),
        )
        if (!isWindows()) assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(config.toPath())))
        assertTrue(File(projectDir, ".junie/rules/datamimic.md").readText().contains("only `datamimic_*` MCP tools"))
        assertEquals("user root guidance\n", rootGuidance.readText())

        agent.unregister()
        assertEquals(setOf("github"), json.parseToJsonElement(config.readText()).jsonObject.getValue("mcpServers").jsonObject.keys)
        assertTrue(File(projectDir, ".junie/rules/datamimic.md").exists())
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
    fun `junie connects MCP but warns without replacing exclusive or conflicting guidance`() {
        val exclusiveProject = temp.newFolder("exclusive-guidance")
        File(exclusiveProject, ".junie/AGENTS.md").apply {
            parentFile.mkdirs()
            writeText("user guidance\n")
        }

        val exclusiveWarnings =
            JunieAgent(exclusiveProject.toPath(), GitIgnore(exclusiveProject.toPath(), git = null)).register(McpServer("https://x", emptyMap(), Instant.EPOCH))

        assertTrue(exclusiveWarnings.single().contains(".junie/AGENTS.md overrides project rules"))
        assertEquals("user guidance\n", File(exclusiveProject, ".junie/AGENTS.md").readText())
        assertTrue(File(exclusiveProject, ".junie/mcp/mcp.json").exists())

        val ruleProject = temp.newFolder("rule-guidance")
        val rule = File(ruleProject, ".junie/rules/datamimic.md").apply {
            parentFile.mkdirs()
            writeText("user routing\n")
        }

        val ruleWarnings =
            JunieAgent(ruleProject.toPath(), GitIgnore(ruleProject.toPath(), git = null)).register(McpServer("https://x", emptyMap(), Instant.EPOCH))

        assertTrue(ruleWarnings.single().contains("already contains user guidance"))
        assertEquals("user routing\n", rule.readText())
        assertTrue(File(ruleProject, ".junie/mcp/mcp.json").exists())
    }

    @Test
    fun `junie keeps routing guidance changed after registration`() {
        val projectDir = temp.newFolder("changed-guidance")
        val agent = JunieAgent(projectDir.toPath(), GitIgnore(projectDir.toPath(), git = null))

        agent.register(McpServer("https://dm.example/mcp", emptyMap(), Instant.EPOCH))
        val rule = File(projectDir, ".junie/rules/datamimic.md")
        rule.writeText("user changed this\n")
        agent.unregister()

        assertEquals("user changed this\n", rule.readText())
        assertFalse(File(projectDir, ".junie/mcp/mcp.json").exists())
    }

    @Test
    fun `junie does not follow a routing rule directory symlink`() {
        assumeFalse(isWindows())
        val projectDir = temp.newFolder("symlink-guidance")
        val target = temp.newFolder("guidance-target")
        val sentinel = File(target, "datamimic.md").apply { writeText("user target\n") }
        File(projectDir, ".junie").mkdirs()
        Files.createSymbolicLink(File(projectDir, ".junie/rules").toPath(), target.toPath())

        val warnings =
            JunieAgent(projectDir.toPath(), GitIgnore(projectDir.toPath(), git = null)).register(McpServer("https://x", emptyMap(), Instant.EPOCH))

        assertTrue(warnings.single().contains("symbolic link"))
        assertEquals("user target\n", sentinel.readText())
        assertTrue(File(projectDir, ".junie/mcp/mcp.json").exists())
    }

    @Test
    fun `junie never writes a token through symlinked config paths`() {
        assumeFalse(isWindows())
        val projectDir = temp.newFolder("symlinked-junie")
        val target = temp.newFolder("external-junie")
        Files.createSymbolicLink(File(projectDir, ".junie").toPath(), target.toPath())

        val error = assertThrows(McpAgentException::class.java) {
            JunieAgent(projectDir.toPath(), GitIgnore(projectDir.toPath(), git = null)).register(McpServer("https://x", mapOf("Authorization" to "Bearer secret"), Instant.EPOCH))
        }

        assertTrue(error.message!!.contains("symbolic link"))
        assertFalse(File(target, "mcp/mcp.json").exists())
        assertFalse(File(target, "rules/datamimic.md").exists())

        val linkedConfigProject = temp.newFolder("symlinked-config")
        val sentinel = temp.newFile("external-mcp.json").apply { writeText("user target\n") }
        val config = File(linkedConfigProject, ".junie/mcp/mcp.json")
        config.parentFile.mkdirs()
        Files.createSymbolicLink(config.toPath(), sentinel.toPath())

        assertThrows(McpAgentException::class.java) {
            JunieAgent(linkedConfigProject.toPath(), GitIgnore(linkedConfigProject.toPath(), git = null)).register(McpServer("https://x", mapOf("Authorization" to "Bearer secret"), Instant.EPOCH))
        }
        assertEquals("user target\n", sentinel.readText())
        assertTrue(Files.isSymbolicLink(config.toPath()))

        val linkedMcpProject = temp.newFolder("symlinked-mcp-directory")
        File(linkedMcpProject, ".junie").mkdirs()
        val mcpTarget = temp.newFolder("external-mcp-directory")
        Files.createSymbolicLink(File(linkedMcpProject, ".junie/mcp").toPath(), mcpTarget.toPath())

        assertThrows(McpAgentException::class.java) {
            JunieAgent(linkedMcpProject.toPath(), GitIgnore(linkedMcpProject.toPath(), git = null)).register(McpServer("https://x", mapOf("Authorization" to "Bearer secret"), Instant.EPOCH))
        }
        assertFalse(File(mcpTarget, "mcp.json").exists())
    }

    @Test
    fun `junie keeps its config ignored before git is initialized`() {
        val projectDir = temp.newFolder("no-git")
        val ignore = File(projectDir, ".junie/.gitignore").apply {
            parentFile.mkdirs()
            writeText("/mcp/other.json\n")
        }

        JunieAgent(projectDir.toPath(), GitIgnore(projectDir.toPath(), git = null)).register(McpServer("https://dm.example/mcp", emptyMap(), Instant.EPOCH))

        assertEquals(listOf("/mcp/other.json", "/rules/datamimic.md", "/mcp/mcp.json"), ignore.readLines())
        assertFalse(File(projectDir, ".git").exists())
    }

    @Test
    fun `junie rejects a tracked config in a parent git work tree`() {
        assumeTrue("Git is available", gitAvailable())
        val repository = temp.newFolder("repository")
        git(repository, "init")
        val projectDir = File(repository, "nested/project").apply { mkdirs() }
        val config = File(projectDir, ".junie/mcp/mcp.json").apply {
            parentFile.mkdirs()
            writeText("{\"mcpServers\":{}}")
        }
        git(repository, "add", "nested/project/.junie/mcp/mcp.json")

        val error = assertThrows(McpAgentException::class.java) {
            JunieAgent(projectDir.toPath(), GitIgnore(projectDir.toPath(), Path.of("git"))).register(McpServer("https://x", emptyMap(), Instant.EPOCH))
        }

        assertTrue(error.message!!.contains("tracked by Git"))
        assertEquals("{\"mcpServers\":{}}", config.readText())
    }

    @Test
    fun `junie accepts an untracked config in a parent git work tree`() {
        assumeTrue("Git is available", gitAvailable())
        val repository = temp.newFolder("untracked-repository")
        git(repository, "init")
        val projectDir = File(repository, "nested/project").apply { mkdirs() }

        JunieAgent(projectDir.toPath(), GitIgnore(projectDir.toPath(), Path.of("git"))).register(McpServer("https://x", emptyMap(), Instant.EPOCH))

        assertTrue(File(projectDir, ".junie/mcp/mcp.json").exists())
        assertFalse(File(projectDir, ".junie/.gitignore").exists())
        assertTrue(File(repository, ".git/info/exclude").readText().contains("/nested/project/.junie/mcp/mcp.json"))
        assertTrue(File(repository, ".git/info/exclude").readText().contains("/nested/project/.junie/rules/datamimic.md"))
        assertEquals("", runCommand(Path.of("git"), listOf("status", "--short"), repository.toPath(), timeoutSeconds = 30).stdout.trim())
    }

    @Test
    fun `junie resolves the local exclude file for a linked git work tree`() {
        assumeFalse(isWindows())
        val projectDir = temp.newFolder("linked-worktree")
        File(projectDir, ".git").writeText("gitdir: elsewhere\n")
        val exclude = temp.newFile("linked-exclude")
        val git = script(
            "git",
            "case \"$1\" in ls-files) exit 1 ;; rev-parse) echo '${exclude.path}' ;; esac",
        )

        JunieAgent(projectDir.toPath(), GitIgnore(projectDir.toPath(), git.toPath())).register(McpServer("https://x", emptyMap(), Instant.EPOCH))

        assertEquals(
            setOf("/.junie/mcp/mcp.json", "/.junie/rules/datamimic.md"),
            exclude.readLines().filter { it.isNotBlank() }.toSet(),
        )
    }

    @Test
    fun `junie fails closed when git cannot answer its tracking query`() {
        val projectDir = temp.newFolder("broken-git").apply {
            File(this, ".git").mkdirs()
            File(this, ".git/HEAD").writeText("ref: refs/heads/main\n")
        }
        val git = script("git", "echo 'index unavailable' >&2; exit 2")

        val error = assertThrows(McpAgentException::class.java) {
            JunieAgent(projectDir.toPath(), GitIgnore(projectDir.toPath(), git.toPath())).register(McpServer("https://x", emptyMap(), Instant.EPOCH))
        }

        assertTrue(error.message!!.contains("cannot determine"))
        assertTrue(error.message!!.contains("index unavailable"))
        assertFalse(File(projectDir, ".junie/mcp/mcp.json").exists())
    }

    @Test
    fun `no token is written into a junie config that git tracks`() {
        assumeFalse(isWindows())
        val projectDir = temp.newFolder("tracked").apply {
            File(this, ".git").mkdirs()
            File(this, ".git/HEAD").writeText("ref: refs/heads/main\n")
        }
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

    private fun gitAvailable() = runCatching { runCommand(Path.of("git"), listOf("--version"), timeoutSeconds = 30).succeeded }.getOrDefault(false)

    private fun git(directory: File, vararg arguments: String) {
        val result = runCommand(Path.of("git"), arguments.toList(), directory.toPath(), timeoutSeconds = 30)
        check(result.succeeded) { result.failureText() }
    }

    private fun isWindows() = System.getProperty("os.name").startsWith("Windows")
}
