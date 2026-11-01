// DATAMIMIC for JetBrains IDEs
// Copyright (c) 2026 Rapiddweller Asia Co., Ltd.
// SPDX-License-Identifier: MIT

package com.rapiddweller.datamimic.core.creation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The dependency rules of ADR 0006. The rule that `core` never imports `com.intellij` is in `SessionTest`. */
class CreationArchitectureTest {
    private val main = File("src/main/kotlin/com/rapiddweller/datamimic")
    private val street = main.resolve("core/creation/street")
    private val container = main.resolve("core/creation/container")
    private val truck = main.resolve("ide/creation/truck")
    private val compositionRoot = main.resolve("ide/creation/NewDatamimicFileAction.kt")

    @Test
    fun `the scanned core folders exist, so no rule passes on an empty scan`() {
        assertTrue(sources(street).isNotEmpty())
        assertTrue(sources(container).isNotEmpty())
    }

    @Test
    fun `only the composition root names the containers`() {
        val offenders = (sources(File("src/main/kotlin")) + sources(File("src/main/java")))
            .filter { !it.startsWith(container) && it != compositionRoot }
            .filter { "datamimic.core.creation.container" in it.readText() }
        assertEquals(emptyList<String>(), offenders.map { it.path })
    }

    @Test
    fun `street and truck name no extension literal`() {
        // A string literal that starts with a dot and a letter, such as ".csv", names an extension.
        val extensionLiteral = Regex(""""\.\p{L}""")
        val offenders = (sources(street) + sources(truck)).filter { extensionLiteral.containsMatchIn(it.readText()) }
        assertEquals(emptyList<String>(), offenders.map { it.path })
    }

    @Test
    fun `containers do no I-O`() {
        val forbidden = Regex("""\b(WorkspaceApi|WorkspaceSession|ProjectSync|PlatformHttp)\b|java\.io\.|java\.nio\.file|java\.net\.|import .*\.\*""")
        val offenders = sources(container).mapNotNull { file -> forbidden.find(file.readText())?.let { "${file.path}: ${it.value}" } }
        assertEquals(emptyList<String>(), offenders)
    }

    private fun sources(dir: File): List<File> =
        dir.walk().filter { it.isFile && (it.extension == "kt" || it.extension == "java") }.toList()
}
