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
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.HexFormat
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
 * Junie, through the project's `.junie/mcp/mcp.json`. Junie's IDE ACP integration does not yet surface a remote MCP
 * OAuth challenge, so this temporary integration publishes the same short-lived project token as other IDE agents.
 */
class JunieAgent(
    private val projectDir: Path,
    private val git: GitIgnore,
    managedServer: McpServer? = null,
    private val filesChanged: () -> Unit = {},
) : McpAgent {
    override val displayName = "Junie"
    private val exclusiveGuidanceFile: Path = projectDir.resolve(EXCLUSIVE_GUIDANCE_PATH)
    private val routingRuleFile: Path = projectDir.resolve(ROUTING_RULE_PATH)
    private val config = ProjectMcpConfig(
        displayName,
        projectDir,
        git,
        CONFIG_PATH,
        CONFIG_TEMP_DIR,
        managedServer,
        filesChanged,
        allowLegacyUrlEntry = true,
    )

    override fun register(server: McpServer): List<String> = config.register(server, ::publishGuidance)

    /** Removes only the token-bearing entry this plugin owns; user-owned or malformed configuration is untouched. */
    override fun unregister() = config.unregister()

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

    private fun publishGuidance(): List<String> {
        if (hasLegacyGuidance()) {
            return buildList {
                removePluginExclusiveGuidance()?.let(::add)
                removePluginRoutingRule()?.let(::add)
                add("Legacy Junie guidelines were kept; no DATAMIMIC guidance was published because project rules suppress them.")
            }
        }
        if (hasForeignExclusiveGuidance()) {
            return buildList {
                removePluginRoutingRule()?.let(::add)
                add("$EXCLUSIVE_GUIDANCE_PATH contains user guidance; add the DATAMIMIC MCP routing there manually.")
            }
        }
        if (!hasUserGuidance()) {
            val warning = publishExclusiveGuidance()
            return if (warning == null) listOfNotNull(removePluginRoutingRule()) else listOf(warning)
        }
        return buildList {
            val routingWarning = publishRoutingRule()
            if (routingWarning == null) removePluginExclusiveGuidance()?.let(::add) else add(routingWarning)
            add("Junie user guidance was kept; older Junie versions may need the DATAMIMIC MCP routing included manually.")
        }
    }

    private fun publishExclusiveGuidance(): String? {
        if (git.isTracked(EXCLUSIVE_GUIDANCE_PATH)) {
            return "$EXCLUSIVE_GUIDANCE_PATH is tracked by Git, so the plugin leaves it unchanged."
        }
        val current = runCatching { exclusiveGuidanceFile.takeIf(Files::isRegularFile)?.readText() }.getOrNull()
        if (current != ROUTING_RULE) {
            Files.createDirectories(exclusiveGuidanceFile.parent)
            Files.writeString(exclusiveGuidanceFile, ROUTING_RULE)
        }
        excludeFromGit(EXCLUSIVE_GUIDANCE_PATH)
        return null
    }

    private fun removePluginExclusiveGuidance(): String? {
        if (!isPluginOwnedGuidance(exclusiveGuidanceFile)) return null
        if (git.isTracked(EXCLUSIVE_GUIDANCE_PATH)) {
            return "$EXCLUSIVE_GUIDANCE_PATH is tracked by Git, so the plugin leaves it unchanged."
        }
        Files.deleteIfExists(exclusiveGuidanceFile)
        return null
    }

    private fun removePluginRoutingRule(): String? {
        if (!isPluginOwnedGuidance(routingRuleFile)) return null
        if (git.isTracked(ROUTING_RULE_PATH)) {
            return "$ROUTING_RULE_PATH is tracked by Git, so the plugin leaves it unchanged."
        }
        Files.deleteIfExists(routingRuleFile)
        return null
    }

    private fun publishRoutingRule(): String? {
        if (routingPathHasSymbolicLink()) {
            return "$ROUTING_RULE_PATH or its parent is a symbolic link; it was left unchanged. Add the DATAMIMIC MCP-only routing manually."
        }
        if (Files.exists(routingRuleFile.parent, java.nio.file.LinkOption.NOFOLLOW_LINKS) &&
            !Files.isDirectory(routingRuleFile.parent, java.nio.file.LinkOption.NOFOLLOW_LINKS)
        ) {
            return "${routingRuleFile.parent} is not a directory; it was left unchanged. Add the DATAMIMIC MCP-only routing manually."
        }
        val current = if (routingRuleFile.exists()) {
            runCatching { routingRuleFile.readText() }.getOrElse {
                return "$ROUTING_RULE_PATH cannot be read; it was left unchanged. Add the DATAMIMIC MCP-only routing manually."
            }
        } else {
            null
        }
        if (current != null && !isKnownPluginGuidance(current)) {
            return "$ROUTING_RULE_PATH already contains user guidance; it was left unchanged. Add the DATAMIMIC MCP-only routing there."
        }
        if (current != ROUTING_RULE) {
            if (git.isTracked(ROUTING_RULE_PATH)) {
                return "$ROUTING_RULE_PATH is tracked by Git, so the plugin leaves it unchanged."
            }
            Files.createDirectories(routingRuleFile.parent)
            Files.writeString(routingRuleFile, ROUTING_RULE)
        }
        excludeFromGit(ROUTING_RULE_PATH)
        return null
    }

    private fun hasForeignExclusiveGuidance(): Boolean =
        Files.exists(exclusiveGuidanceFile, java.nio.file.LinkOption.NOFOLLOW_LINKS) &&
            !isPluginOwnedGuidance(exclusiveGuidanceFile)

    private fun hasUserGuidance(): Boolean =
        Files.exists(projectDir.resolve(ROOT_GUIDANCE_PATH), java.nio.file.LinkOption.NOFOLLOW_LINKS) ||
            Files.exists(projectDir.resolve(PLAYBOOK_PATH), java.nio.file.LinkOption.NOFOLLOW_LINKS) ||
            hasUserRoutingGuidance()

    private fun hasLegacyGuidance(): Boolean =
        listOf(LEGACY_GUIDELINES_PATH, LEGACY_GUIDELINES_DIRECTORY).any { path ->
            Files.exists(projectDir.resolve(path), java.nio.file.LinkOption.NOFOLLOW_LINKS)
        }

    private fun hasUserRoutingGuidance(): Boolean {
        if (routingPathHasSymbolicLink()) return Files.exists(routingRuleFile.parent, java.nio.file.LinkOption.NOFOLLOW_LINKS)
        if (!Files.isDirectory(routingRuleFile.parent)) return Files.exists(routingRuleFile.parent, java.nio.file.LinkOption.NOFOLLOW_LINKS)
        return Files.list(routingRuleFile.parent).use { paths ->
            paths.anyMatch { path ->
                path.fileName.toString().endsWith(".md") &&
                    (path.fileName.toString() != routingRuleFile.fileName.toString() || !isPluginOwnedGuidance(path))
            }
        }
    }

    private fun isPluginOwnedGuidance(file: Path): Boolean =
        Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS) &&
            runCatching { isKnownPluginGuidance(file.readText()) }.getOrDefault(false)

    private fun isKnownPluginGuidance(content: String): Boolean =
        content in KNOWN_PLUGIN_GUIDANCE || sha256(content) in PREVIOUS_GUIDANCE_SHA256

    private fun sha256(content: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.toByteArray(Charsets.UTF_8)))

    private fun routingPathHasSymbolicLink(): Boolean =
        listOf(".junie", ".junie/rules", ROUTING_RULE_PATH).any { Files.isSymbolicLink(projectDir.resolve(it)) }

    internal companion object {
        const val CONFIG_PATH = ".junie/mcp/mcp.json"
        const val EXCLUSIVE_GUIDANCE_PATH = ".junie/AGENTS.md"
        const val ROOT_GUIDANCE_PATH = "AGENTS.md"
        const val PLAYBOOK_PATH = ".junie/playbook.md"
        const val LEGACY_GUIDELINES_PATH = ".junie/guidelines.md"
        const val LEGACY_GUIDELINES_DIRECTORY = ".junie/guidelines"
        const val ROUTING_RULE_PATH = ".junie/rules/datamimic.md"
        const val CONFIG_TEMP_DIR = ".junie/mcp/.datamimic-tmp"
        private val AUTHORING_WORKFLOW = checkNotNull(
            JunieAgent::class.java.getResourceAsStream("/junie/datamimic-agent-workflow.md"),
        ) { "Missing packaged DATAMIMIC agent workflow" }.bufferedReader(Charsets.UTF_8).use { it.readText().trim() }
        val PREVIOUS_ROUTING_RULES = setOf(
            """
                # DATAMIMIC Platform routing

                For DATAMIMIC Platform project content, use only `datamimic_*` MCP tools. Begin with an available read-only `datamimic_*` tool. If those tools are unavailable, stop and report that DATAMIMIC MCP tools are unavailable. Do not fall back to local files, search, terminal commands, or local skills.
            """.trimIndent() + "\n",
            """
                # DATAMIMIC Platform routing

                For every request about this DATAMIMIC Platform project, use only the available `datamimic_*` MCP tools. Begin with an available read-only `datamimic_*` tool.

                Never inspect or modify DATAMIMIC project content through workspace files, local filesystem or search, terminal commands, local skills, another MCP server, or a subagent. Do not fall back when a DATAMIMIC tool is unavailable or returns an error.

                Never start data generation or execute the project through agent tools. Generation is an explicit user action through the IDE's DATAMIMIC Generation Run configuration or the Platform UI.

                If the `datamimic_*` tools are unavailable, stop and ask the user to sign in or reconnect the DATAMIMIC project.
            """.trimIndent() + "\n",
        )
        private val ROUTING_PREFIX = """
            # DATAMIMIC Platform routing

            For every request about this DATAMIMIC Platform project, use only the available `datamimic_*` MCP tools. Begin with an available read-only `datamimic_*` tool.

            Never inspect or modify DATAMIMIC project content through workspace files, local filesystem or search, terminal commands, local skills, another MCP server, or a subagent. Do not fall back when a DATAMIMIC tool is unavailable or returns an error.

            ## Connected DATAMIMIC MCP workflow
        """.trimIndent()
        private val ROUTING_SUFFIX = """
            Never start data generation or execute the project through agent tools. Generation is an explicit user action through the IDE's DATAMIMIC Generation Run configuration or the Platform UI.

            If the `datamimic_*` tools are unavailable, stop and ask the user to sign in or reconnect the DATAMIMIC project.
        """.trimIndent()
        val ROUTING_RULE = "$ROUTING_PREFIX\n\n$AUTHORING_WORKFLOW\n\n$ROUTING_SUFFIX\n"
        val KNOWN_PLUGIN_GUIDANCE = PREVIOUS_ROUTING_RULES + ROUTING_RULE
        // WHY: earlier development builds projected the same workflow in compact and Markdown-rendered forms.
        private val PREVIOUS_GUIDANCE_SHA256 = setOf(
            "f0c4a6749a799308a8dfde8f9a9029edf10c91ce5a505e6eba6dde52ed946a9c",
            "d05d24833016f23cc20ec3fab5461286b78c82746704bf67c125fa28796c2917",
        )
    }
}

/** AI Assistant reads project MCP servers from `.ai/mcp/mcp.json` when its own project setting enables it. */
class AiAssistantAgent(
    projectDir: Path,
    git: GitIgnore,
    managedServer: McpServer? = null,
    filesChanged: () -> Unit = {},
) : McpAgent {
    override val displayName = "AI Assistant"
    private val config = ProjectMcpConfig(
        displayName,
        projectDir,
        git,
        CONFIG_PATH,
        CONFIG_TEMP_DIR,
        managedServer,
        filesChanged,
        type = "streamable-http",
        acceptBlankConfig = true,
    )

    override fun register(server: McpServer): List<String> = config.register(server)

    override fun unregister() = config.unregister()

    internal companion object {
        const val CONFIG_PATH = ".ai/mcp/mcp.json"
        const val CONFIG_TEMP_DIR = ".ai/mcp/.datamimic-tmp"
    }
}

/** Shared safe owner for a project's `datamimic-platform` MCP JSON entry. */
private class ProjectMcpConfig(
    private val agentName: String,
    private val projectDir: Path,
    private val git: GitIgnore,
    private val configPath: String,
    private val tempPath: String,
    private var managedServer: McpServer?,
    private val filesChanged: () -> Unit,
    private val type: String? = null,
    private val acceptBlankConfig: Boolean = false,
    private val allowLegacyUrlEntry: Boolean = false,
) {
    private val configFile = projectDir.resolve(configPath)
    private val tempDirectory = projectDir.resolve(tempPath)
    private val rootDirectory = projectDir.resolve(configPath.substringBefore('/'))
    private val localIgnore = rootDirectory.resolve(".gitignore")
    private val localIgnoreBlock = "# DATAMIMIC JetBrains MCP\n/${configPath.substringAfter('/')}\n/${tempPath.substringAfter('/')}\n# End DATAMIMIC JetBrains MCP\n"

    fun register(server: McpServer, beforeWrite: () -> List<String> = { emptyList() }): List<String> {
        if (pathHasSymbolicLink()) throw McpAgentException("$agentName: $configPath or its parent is a symbolic link, so the DATAMIMIC configuration is not written.")
        if (git.isTracked(configPath)) throw McpAgentException("$agentName: $configPath is tracked by Git, so the plugin leaves it unchanged.")
        val servers = readServers()
        servers[MCP_SERVER_NAME]?.let { current ->
            if (!isPluginManagedEntry(current, server.url) && !(allowLegacyUrlEntry && isLegacyUrlEntry(current, server.url))) {
                throw McpAgentException("$agentName: $MCP_SERVER_NAME already exists and is not managed by this plugin.")
            }
        }
        val warnings = beforeWrite()
        // WHY: Git must ignore the token-bearing file before the first write.
        excludeFromGit(configPath)
        excludeFromGit(tempPath)
        writeServers(servers + (MCP_SERVER_NAME to entry(server)))
        managedServer = server
        filesChanged()
        return warnings
    }

    fun unregister() {
        if (pathHasSymbolicLink() || git.isTracked(configPath)) return
        val server = managedServer ?: return
        val servers = runCatching(::readServers).getOrNull() ?: return
        if (!isExactManagedEntry(servers[MCP_SERVER_NAME] ?: return, server)) return
        writeServers(servers - MCP_SERVER_NAME)
        filesChanged()
    }

    private fun entry(server: McpServer) = buildJsonObject {
        type?.let { put("type", it) }
        put("url", server.url)
        putJsonObject("headers") {
            server.headers.forEach { (name, value) -> put(name, value) }
            put(MANAGED_HEADER, MANAGED_BY)
        }
    }

    private fun readConfig(): JsonObject {
        if (!configFile.exists()) return JsonObject(emptyMap())
        val content = configFile.readText()
        return if (acceptBlankConfig && content.isBlank()) JsonObject(emptyMap()) else json.parseToJsonElement(content).jsonObject
    }

    private fun readServers(): Map<String, JsonElement> = readConfig()[SERVERS_KEY]?.jsonObject ?: emptyMap()

    private fun writeServers(servers: Map<String, JsonElement>) {
        val others = readConfig() - SERVERS_KEY
        if (servers.isEmpty() && others.isEmpty()) {
            Files.deleteIfExists(configFile)
            removeLocalIgnoreBlock()
            deleteEmpty(tempDirectory)
            deleteEmpty(configFile.parent)
            deleteEmpty(rootDirectory)
            return
        }
        writeSecretFile(configFile, JsonObject(others + (SERVERS_KEY to JsonObject(servers))).toString(), tempDirectory)
    }

    private fun excludeFromGit(relativePath: String) {
        if (git.exclude(relativePath)) return
        val current = if (localIgnore.exists()) localIgnore.readText() else ""
        if (current.contains(localIgnoreBlock)) return
        Files.createDirectories(localIgnore.parent)
        Files.writeString(localIgnore, current + (if (current.isEmpty() || current.endsWith("\n")) "" else "\n") + localIgnoreBlock)
    }

    private fun removeLocalIgnoreBlock() {
        if (!localIgnore.exists()) return
        val current = localIgnore.readText()
        val remaining = current.replace(localIgnoreBlock, "")
        if (remaining == current) return
        if (remaining.isEmpty()) Files.deleteIfExists(localIgnore) else Files.writeString(localIgnore, remaining)
    }

    private fun deleteEmpty(directory: Path) {
        runCatching { Files.deleteIfExists(directory) }
    }

    private fun pathHasSymbolicLink(): Boolean =
        listOf(rootDirectory, configFile.parent, tempDirectory, configFile, localIgnore).any(Files::isSymbolicLink)

    private fun isPluginManagedEntry(entry: JsonElement, expectedUrl: String): Boolean = runCatching {
        val objectEntry = entry.jsonObject
        (type == null || objectEntry["type"]?.jsonPrimitive?.content == type) &&
            objectEntry["url"]?.jsonPrimitive?.content == expectedUrl &&
            objectEntry["headers"]?.jsonObject?.get("Authorization")?.jsonPrimitive?.content?.startsWith("Bearer ") == true &&
            objectEntry["headers"]?.jsonObject?.get(MANAGED_HEADER)?.jsonPrimitive?.content == MANAGED_BY
    }.getOrDefault(false)

    private fun isExactManagedEntry(entry: JsonElement, server: McpServer): Boolean = runCatching {
        val headers = entry.jsonObject["headers"]?.jsonObject ?: return@runCatching false
        isPluginManagedEntry(entry, server.url) &&
            headers.filterKeys { it != MANAGED_HEADER }.mapValues { (_, value) -> value.jsonPrimitive.content } == server.headers
    }.getOrDefault(false)

    private fun isLegacyUrlEntry(entry: JsonElement, expectedUrl: String): Boolean = runCatching {
        val objectEntry = entry.jsonObject
        objectEntry.size == 1 && objectEntry["url"]?.jsonPrimitive?.content == expectedUrl
    }.getOrDefault(false)

    private companion object {
        const val SERVERS_KEY = "mcpServers"
        const val MANAGED_HEADER = "X-DATAMIMIC-Managed-By"
        const val MANAGED_BY = "datamimic-jetbrains"
    }
}

/** Keeps plugin-owned local files out of Git without touching shared ignore files. */
class GitIgnore(private val projectDir: Path, private val git: Path?) {
    /** Whether Git already tracks [relativePath]; then writing a secret there would reach the repository. */
    fun isTracked(relativePath: String): Boolean {
        if (!hasGitWorkTreeMarker()) return false
        val cli = git ?: throw McpAgentException("DATAMIMIC MCP: cannot determine whether $relativePath is tracked because Git is unavailable.")
        val result = runCommand(cli, listOf("ls-files", "--error-unmatch", relativePath), projectDir, timeoutSeconds = 30)
        return when (result.exitCode) {
            0 -> true
            1 -> false
            else -> throw McpAgentException("DATAMIMIC MCP: cannot determine whether $relativePath is tracked: ${result.failureText()}")
        }
    }

    /** Adds [relativePath] to the containing work tree's local exclude file. Returns false before Git is initialized. */
    fun exclude(relativePath: String): Boolean {
        val root = repositoryRootForExclusion() ?: return false
        val marker = root.resolve(".git")
        val exclude = if (Files.isDirectory(marker)) {
            marker.resolve("info/exclude")
        } else {
            val cli = git ?: throw McpAgentException("DATAMIMIC MCP: cannot locate Git's local exclude file because Git is unavailable.")
            val result = runCommand(cli, listOf("rev-parse", "--git-path", "info/exclude"), projectDir, timeoutSeconds = 30)
            if (!result.succeeded) throw McpAgentException("DATAMIMIC MCP: cannot locate Git's local exclude file: ${result.failureText()}")
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
internal fun writeSecretFile(target: Path, content: String) = writeSecretFile(target, content, target.parent)

internal fun writeSecretFile(target: Path, content: String, tempDirectory: Path) {
    Files.createDirectories(target.parent)
    Files.createDirectories(tempDirectory)
    val temp = Files.createTempFile(tempDirectory, ".${target.fileName}", ".tmp")
    try {
        runCatching { Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("rw-------")) }
        Files.writeString(temp, content)
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
        }
    } finally {
        Files.deleteIfExists(temp)
    }
}
