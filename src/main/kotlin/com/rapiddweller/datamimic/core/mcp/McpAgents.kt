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

    override fun register(server: McpServer): List<String> {
        unregister()
        val headers = server.headers.flatMap { (name, value) -> listOf("--header", "$name: $value") }
        val result = runCommand(
            cli,
            listOf("mcp", "add", "--transport", "http", "--scope", "local", MCP_SERVER_NAME, server.url) + headers,
            projectDir,
            timeoutSeconds = 60,
        )
        if (!result.succeeded) throw McpAgentException("Claude Code: ${result.failureText()}")
        return emptyList()
    }

    override fun unregister() {
        // WHY: "not found" is the goal state, so the exit code of remove does not matter.
        runCommand(cli, listOf("mcp", "remove", "--scope", "local", MCP_SERVER_NAME), projectDir, timeoutSeconds = 60)
    }
}

/**
 * Junie, through the project's `.junie/mcp/mcp.json`: the project-level file scopes the server to this IDE window.
 * The plugin also owns one routing rule; neither it nor the MCP entry replaces user guidance.
 */
class JunieAgent(private val projectDir: Path, private val git: GitIgnore) : McpAgent {
    override val displayName = "Junie"

    private val configFile: Path = projectDir.resolve(CONFIG_PATH)
    private val routingRuleFile: Path = projectDir.resolve(ROUTING_RULE_PATH)

    override fun register(server: McpServer): List<String> {
        if (mcpPathHasSymbolicLink()) {
            throw McpAgentException("Junie: $CONFIG_PATH or its parent is a symbolic link, so the DATAMIMIC token is not written.")
        }
        if (git.isTracked(CONFIG_PATH)) {
            throw McpAgentException("Junie: $CONFIG_PATH is tracked by Git, so the DATAMIMIC token is not written into it.")
        }
        val currentServers = readServers()
        val warning = publishRoutingRule()
        excludeFromGit(CONFIG_PATH)
        val entry = buildJsonObject {
            put("url", server.url)
            putJsonObject("headers") { server.headers.forEach { (name, value) -> put(name, value) } }
        }
        writeServers(currentServers + (MCP_SERVER_NAME to entry))
        return listOfNotNull(warning)
    }

    override fun unregister() {
        if (mcpPathHasSymbolicLink()) return
        if (configFile.exists()) writeServers(readServers() - MCP_SERVER_NAME)
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

    private fun excludeFromGit(relativePath: String) {
        if (!git.exclude(relativePath)) excludeBeforeGitInit(relativePath)
    }

    private fun excludeBeforeGitInit(relativePath: String) {
        val ignore = projectDir.resolve(".junie/.gitignore")
        val pattern = "/${relativePath.removePrefix(".junie/")}"
        val current = if (ignore.exists()) ignore.readText() else ""
        if (current.lines().any { it.trim() == pattern }) return
        Files.createDirectories(ignore.parent)
        Files.writeString(ignore, current + (if (current.isEmpty() || current.endsWith("\n")) "" else "\n") + pattern + "\n")
    }

    private fun publishRoutingRule(): String? {
        if (Files.exists(projectDir.resolve(EXCLUSIVE_GUIDANCE_PATH), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return "$EXCLUSIVE_GUIDANCE_PATH overrides project rules; add the DATAMIMIC MCP-only routing there or use Junie's default project guidance."
        }
        if (routingPathHasSymbolicLink()) {
            return "$ROUTING_RULE_PATH or its parent is a symbolic link; it was left unchanged. Add the DATAMIMIC MCP-only routing manually."
        }
        if (routingRuleFile.exists() && routingRuleFile.readText() != ROUTING_RULE) {
            return "$ROUTING_RULE_PATH already contains user guidance; it was left unchanged. Add the DATAMIMIC MCP-only routing there."
        }
        if (!routingRuleFile.exists()) {
            Files.createDirectories(routingRuleFile.parent)
            Files.writeString(routingRuleFile, ROUTING_RULE)
        }
        excludeFromGit(ROUTING_RULE_PATH)
        return null
    }

    private fun routingPathHasSymbolicLink(): Boolean =
        listOf(".junie", ".junie/rules", ROUTING_RULE_PATH).any { Files.isSymbolicLink(projectDir.resolve(it)) }

    private fun mcpPathHasSymbolicLink(): Boolean =
        listOf(".junie", ".junie/mcp", CONFIG_PATH).any { Files.isSymbolicLink(projectDir.resolve(it)) }

    private companion object {
        const val CONFIG_PATH = ".junie/mcp/mcp.json"
        const val EXCLUSIVE_GUIDANCE_PATH = ".junie/AGENTS.md"
        const val ROUTING_RULE_PATH = ".junie/rules/datamimic.md"
        const val SERVERS_KEY = "mcpServers"
        val ROUTING_RULE = """
            # DATAMIMIC Platform routing

            For DATAMIMIC Platform project content, use only `datamimic_*` MCP tools. Begin with an available read-only `datamimic_*` tool. If those tools are unavailable, stop and report that DATAMIMIC MCP tools are unavailable. Do not fall back to local files, search, terminal commands, or local skills.
        """.trimIndent() + "\n"
    }
}

/** Keeps plugin-owned local files out of Git without touching shared ignore files. */
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

    /** Adds [relativePath] to the containing work tree's local exclude file. Returns false before Git is initialized. */
    fun exclude(relativePath: String): Boolean {
        val root = repositoryRootForExclusion() ?: return false
        val marker = root.resolve(".git")
        val exclude = if (Files.isDirectory(marker)) {
            marker.resolve("info/exclude")
        } else {
            val cli = git ?: throw McpAgentException("Junie: cannot locate Git's local exclude file because Git is unavailable.")
            val result = runCommand(cli, listOf("rev-parse", "--git-path", "info/exclude"), projectDir, timeoutSeconds = 30)
            if (!result.succeeded) throw McpAgentException("Junie: cannot locate Git's local exclude file: ${result.failureText()}")
            val path = Path.of(result.stdout.trim())
            if (path.isAbsolute) path else projectDir.resolve(path).normalize()
        }
        val target = projectDir.resolve(relativePath).toAbsolutePath().normalize()
        val rootPath = root.toAbsolutePath().normalize()
        check(target.startsWith(rootPath)) { "$target is outside Git work tree $rootPath" }
        val pattern = "/${rootPath.relativize(target).toString().replace(java.io.File.separatorChar, '/')}"
        val current = if (exclude.exists()) exclude.readText() else ""
        if (current.lines().any { it.trim() == pattern }) return true
        Files.createDirectories(exclude.parent)
        Files.writeString(exclude, current + (if (current.isEmpty() || current.endsWith("\n")) "" else "\n") + pattern + "\n")
        return true
    }

    private fun hasGitWorkTreeMarker(): Boolean =
        generateSequence(projectDir.toAbsolutePath().normalize()) { it.parent }.any { directory ->
            val marker = directory.resolve(".git")
            marker.resolve("HEAD").exists() || Files.isRegularFile(marker)
        }

    private fun repositoryRootForExclusion(): Path? =
        generateSequence(projectDir.toAbsolutePath().normalize()) { it.parent }.firstOrNull { directory ->
            val marker = directory.resolve(".git")
            Files.isDirectory(marker) || Files.isRegularFile(marker)
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
