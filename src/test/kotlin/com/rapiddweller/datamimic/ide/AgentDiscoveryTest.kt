// DATAMIMIC for JetBrains IDEs
// Copyright (c) 2026 Rapiddweller Asia Co., Ltd.
// SPDX-License-Identifier: MIT

package com.rapiddweller.datamimic.ide

import com.rapiddweller.datamimic.core.json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

class AgentDiscoveryTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `a downloaded project publishes Junie configuration before its first open`() {
        val projectDir = temp.newFolder("downloaded-project").toPath()

        registerJunieBeforeOpen(projectDir, "https://dm.example/mcp", path = null)

        val entry = json.parseToJsonElement(Files.readString(projectDir.resolve(".junie/mcp/mcp.json")))
            .jsonObject.getValue("mcpServers").jsonObject.getValue("datamimic-platform").jsonObject
        assertEquals("https://dm.example/mcp", entry.getValue("url").jsonPrimitive.content)
        assertFalse("credentials must remain Junie-owned", "headers" in entry)
        val rule = Files.readString(projectDir.resolve(".junie/rules/datamimic.md"))
        assertTrue(rule.contains("only the available `datamimic_*` MCP tools"))
        assertTrue(rule.contains("Never start data generation"))
    }
}
