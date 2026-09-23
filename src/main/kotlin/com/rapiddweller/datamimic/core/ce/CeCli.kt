// DATAMIMIC for JetBrains IDEs
// Copyright (c) 2026 Rapiddweller Asia Co., Ltd.
// SPDX-License-Identifier: MIT

package com.rapiddweller.datamimic.core.ce

import com.rapiddweller.datamimic.core.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import com.rapiddweller.datamimic.core.process.executableName
import com.rapiddweller.datamimic.core.process.findOnPath
import com.rapiddweller.datamimic.core.process.isExecutableFile
import com.rapiddweller.datamimic.core.process.isWindows
import com.rapiddweller.datamimic.core.process.runCommand
import java.nio.file.Path

@Serializable
enum class LintSeverity {
    @SerialName("error") ERROR,
    @SerialName("warning") WARNING,
    @SerialName("info") INFO,
    @SerialName("hint") HINT,

    /** A severity this client does not know yet; shown like [INFO]. */
    UNKNOWN,
}

@Serializable
data class LintDiagnostic(
    val rule: String,
    val severity: LintSeverity = LintSeverity.UNKNOWN,
    val message: String,
    @SerialName("fix_hint") val fixHint: String? = null,
    val path: String? = null,
    /** 1-based; CE reports no column, so a diagnostic covers its whole line. */
    val line: Int? = null,
)

@Serializable
data class LintReport(val ok: Boolean, val diagnostics: List<LintDiagnostic> = emptyList())

class CeCliException(message: String) : RuntimeException(message)

/** The local DATAMIMIC CE command line (`pip install "datamimic-ce[mcp]"`). */
class CeCli(val executable: Path) {
    /** The MCP adapter installed next to the CLI, if the `mcp` extra is installed. */
    val mcpExecutable: Path? get() = executable.resolveSibling(executableName("datamimic-mcp")).takeIf(::isExecutableFile)

    /** Why the MCP adapter cannot start, or null when it can (a broken install fails on import, not on first use). */
    fun mcpProblem(): String? {
        val mcp = mcpExecutable ?: return "The DATAMIMIC CE MCP adapter is not installed. Install it with pip install \"datamimic-ce[mcp]\"."
        val result = runCommand(mcp, listOf("--help"), timeoutSeconds = 30)
        return if (result.succeeded) null else "The DATAMIMIC CE MCP adapter does not start: ${result.failureText()}"
    }

    fun version(): String {
        val output = run(listOf("version"), timeoutSeconds = 30)
        return VERSION.find(output)?.groupValues?.get(1) ?: throw CeCliException("Unexpected `datamimic version` output: ${output.take(200)}")
    }

    /** Lints the saved descriptor; relative includes resolve against its directory. */
    fun lint(descriptor: Path): LintReport {
        val result = runCommand(executable, listOf("lint", descriptor.toAbsolutePath().toString(), "--format", "json"), timeoutSeconds = 60)
        // WHY: lint exits 1 when it found errors; only unparsable output means the CLI itself failed.
        return runCatching { json.decodeFromString<LintReport>(result.stdout) }
            .getOrElse { throw CeCliException("`datamimic lint` failed: ${result.failureText()}") }
    }

    /** JSON Schema of `*.dm.json` authoring models, as published by this CE version. */
    fun authoringSchema(): String {
        val output = run(listOf("capabilities", "--section", AUTHORING_SPEC), timeoutSeconds = 60)
        val section = json.parseToJsonElement(output).jsonObject[AUTHORING_SPEC]
        if (section !is JsonObject) throw CeCliException("`datamimic capabilities` returned no authoring schema.")
        return section.toString()
    }

    fun runArguments(descriptor: Path, taskId: String): List<String> =
        listOf(executable.toString(), "run", descriptor.toAbsolutePath().toString(), "--task-id", taskId)

    private fun run(arguments: List<String>, timeoutSeconds: Long): String {
        val result = runCommand(executable, arguments, timeoutSeconds = timeoutSeconds)
        return if (result.succeeded) result.stdout else throw CeCliException(result.failureText())
    }

    companion object {
        private const val AUTHORING_SPEC = "authoring_spec"
        private val VERSION = Regex("""version:\s*(\S+)""")

        /**
         * Finds the CLI: an explicitly configured path wins, then a virtual environment in the project, then PATH.
         * Returns null when nothing executable is found.
         */
        fun locate(configured: String?, projectDir: Path?, pathVariable: String?): CeCli? {
            val name = executableName("datamimic")
            val candidates = buildList {
                configured?.takeIf { it.isNotBlank() }?.let { add(Path.of(it.trim())) }
                projectDir?.let { dir -> VENV_DIRS.forEach { add(dir.resolve(it).resolve(VENV_BIN).resolve(name)) } }
            }
            return (candidates.firstOrNull(::isExecutableFile) ?: findOnPath("datamimic", pathVariable))?.let(::CeCli)
        }

        private val VENV_DIRS = listOf(".venv", "venv")

        private val VENV_BIN = if (isWindows) "Scripts" else "bin"
    }
}
