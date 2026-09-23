// DATAMIMIC for JetBrains IDEs
// Copyright (c) 2026 Rapiddweller Asia Co., Ltd.
// SPDX-License-Identifier: MIT

package com.rapiddweller.datamimic.core.process

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.isExecutable
import kotlin.io.path.isRegularFile

class CommandResult(val exitCode: Int, val stdout: String, val stderr: String) {
    val succeeded: Boolean get() = exitCode == 0

    /** The last line the program wrote to stderr, which for Python and Node CLIs names the actual error. */
    fun failureText(): String = stderr.lines().lastOrNull { it.isNotBlank() }?.trim() ?: "exit code $exitCode"
}

class CommandTimeoutException(program: Path, seconds: Long) :
    RuntimeException("`${program.fileName}` did not finish within $seconds s.")

/** Runs a local command line tool with a hard timeout. Blocking; call off the UI thread. */
fun runCommand(program: Path, arguments: List<String>, workingDirectory: Path? = null, timeoutSeconds: Long): CommandResult {
    // WHY: files instead of pipes, so a chatty or hanging tool can neither block on a full pipe nor outlive the timeout.
    val stdout = Files.createTempFile("datamimic-command", ".out").toFile()
    val stderr = Files.createTempFile("datamimic-command", ".err").toFile()
    try {
        val process = ProcessBuilder(listOf(program.toString()) + arguments)
            .redirectOutput(stdout)
            .redirectError(stderr)
            .redirectInput(ProcessBuilder.Redirect.from(File(NULL_DEVICE)))
            .apply {
                workingDirectory?.let { directory(it.toFile()) }
                environment()["NO_COLOR"] = "1"
            }
            .start()
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw CommandTimeoutException(program, timeoutSeconds)
        }
        return CommandResult(process.exitValue(), stdout.readText(), stderr.readText())
    } finally {
        stdout.delete()
        stderr.delete()
    }
}

/** The first executable called [name] in the directories of a PATH value. */
fun findOnPath(name: String, pathVariable: String?): Path? =
    pathVariable?.split(File.pathSeparator)
        ?.filter { it.isNotBlank() }
        ?.map { Path.of(it).resolve(executableName(name)) }
        ?.firstOrNull(::isExecutableFile)

fun isExecutableFile(path: Path): Boolean = path.isRegularFile() && path.isExecutable()

val isWindows: Boolean = System.getProperty("os.name").startsWith("Windows")

fun executableName(base: String): String = if (isWindows) "$base.exe" else base

private val NULL_DEVICE = if (isWindows) "NUL" else "/dev/null"
