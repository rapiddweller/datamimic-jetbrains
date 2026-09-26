// DATAMIMIC for JetBrains IDEs
// Copyright (c) 2026 Rapiddweller Asia Co., Ltd.
// SPDX-License-Identifier: MIT

package com.rapiddweller.datamimic.core.mcp

import com.rapiddweller.datamimic.core.json
import com.rapiddweller.datamimic.core.process.runCommand
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.exists
import kotlin.io.path.readText

class McpAgentException(message: String) : RuntimeException(message)

/** Name of the platform MCP server in every agent's configuration. */
const val MCP_SERVER_NAME = "datamimic-platform"

/**
 * Claude Code, through its own CLI. The "local" scope belongs to one project directory and lives in the user's
 * Claude configuration, so the token never lands in the repository.
 */
class ClaudeCodeAgent(private val cli: Path, private val projectDir: Path) : McpAgent {
    override val displayName = "Claude Code"

    override fun register(server: McpServer) {
        unregister()
        val headers = server.headers.flatMap { (name, value) -> listOf("--header", "$name: $value") }
        val result = runCommand(
            cli,
            listOf("mcp", "add", "--transport", "http", "--scope", "local", MCP_SERVER_NAME, server.url) + headers,
            projectDir,
            timeoutSeconds = 60,
        )
        if (!result.succeeded) throw McpAgentException("Claude Code: ${result.failureText()}")
    }

    override fun unregister() {
        // WHY: "not found" is the goal state, so the exit code of remove does not matter.
        runCommand(cli, listOf("mcp", "remove", "--scope", "local", MCP_SERVER_NAME), projectDir, timeoutSeconds = 60)
    }
}

/**
 * Junie, through the project's `.junie/mcp/mcp.json`: the project-level file scopes the server to this IDE window.
 * Only the DATAMIMIC entry is touched; the file is kept out of Git because it holds the token.
 */
class JunieAgent(private val projectDir: Path, private val git: GitIgnore) : McpAgent {
    override val displayName = "Junie"

    private val configFile: Path = projectDir.resolve(CONFIG_PATH)

    override fun register(server: McpServer) {
        if (git.isTracked(CONFIG_PATH)) {
            throw McpAgentException("Junie: $CONFIG_PATH is tracked by Git, so the DATAMIMIC token is not written into it.")
        }
        if (projectDir.resolve(".git").exists()) git.exclude(CONFIG_PATH) else excludeBeforeGitInit()
        val entry = buildJsonObject {
            put("url", server.url)
            putJsonObject("headers") { server.headers.forEach { (name, value) -> put(name, value) } }
        }
        writeServers(readServers() + (MCP_SERVER_NAME to entry))
    }

    override fun unregister() {
        if (!configFile.exists()) return
        writeServers(readServers() - MCP_SERVER_NAME)
    }

    private fun readConfig(): JsonObject =
        if (configFile.exists()) json.parseToJsonElement(configFile.readText()).jsonObject else JsonObject(emptyMap())

    private fun readServers(): Map<String, JsonElement> = readConfig()[SERVERS_KEY]?.jsonObject ?: emptyMap()

    private fun writeServers(servers: Map<String, JsonElement>) {
        val others = readConfig() - SERVERS_KEY
        if (servers.isEmpty() && others.isEmpty()) {
            Files.deleteIfExists(configFile)
            return
        }
        writeSecretFile(configFile, JsonObject(others + (SERVERS_KEY to JsonObject(servers))).toString())
    }

    private fun excludeBeforeGitInit() {
        val ignore = projectDir.resolve(".junie/.gitignore")
        val pattern = "/mcp/mcp.json"
        val current = if (ignore.exists()) ignore.readText() else ""
        if (current.lines().any { it.trim() == pattern }) return
        Files.createDirectories(ignore.parent)
        Files.writeString(ignore, current + (if (current.isEmpty() || current.endsWith("\n")) "" else "\n") + pattern + "\n")
    }

    private companion object {
        const val CONFIG_PATH = ".junie/mcp/mcp.json"
        const val SERVERS_KEY = "mcpServers"
    }
}

/** Keeps files that hold credentials out of the project's Git repository, without touching shared ignore files. */
class GitIgnore(private val projectDir: Path, private val git: Path?) {
    /** Whether Git already tracks [relativePath]; then writing a secret there would reach the repository. */
    fun isTracked(relativePath: String): Boolean {
        if (!hasGitWorkTreeMarker()) return false
        val cli = git ?: throw McpAgentException("Junie: cannot determine whether $relativePath is tracked because Git is unavailable.")
        val result = runCommand(cli, listOf("ls-files", "--error-unmatch", relativePath), projectDir, timeoutSeconds = 30)
        return when (result.exitCode) {
            0 -> true
            1 -> false
            else -> throw McpAgentException("Junie: cannot determine whether $relativePath is tracked: ${result.failureText()}")
        }
    }

    /** Adds [relativePath] to `.git/info/exclude`, which is local to this clone and never committed. */
    fun exclude(relativePath: String) {
        val exclude = projectDir.resolve(".git/info/exclude")
        if (!projectDir.resolve(".git").exists()) return
        val pattern = "/$relativePath"
        val current = if (exclude.exists()) exclude.readText() else ""
        if (current.lines().any { it.trim() == pattern }) return
        Files.createDirectories(exclude.parent)
        Files.writeString(exclude, current + (if (current.isEmpty() || current.endsWith("\n")) "" else "\n") + pattern + "\n")
    }

    private fun hasGitWorkTreeMarker(): Boolean =
        generateSequence(projectDir.toAbsolutePath().normalize()) { it.parent }.any { directory ->
            val marker = directory.resolve(".git")
            marker.resolve("HEAD").exists() || Files.isRegularFile(marker)
        }
}

/** Writes atomically and, where the file system allows it, readable only by the owner. */
internal fun writeSecretFile(target: Path, content: String) {
    Files.createDirectories(target.parent)
    val temp = Files.createTempFile(target.parent, ".${target.fileName}", ".tmp")
    try {
        runCatching { Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("rw-------")) }
        Files.writeString(temp, content)
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    } finally {
        Files.deleteIfExists(temp)
    }
}
