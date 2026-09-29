// DATAMIMIC for JetBrains IDEs
// Copyright (c) 2026 Rapiddweller Asia Co., Ltd.
// SPDX-License-Identifier: MIT

package com.rapiddweller.datamimic.ide

import com.rapiddweller.datamimic.core.json
import com.rapiddweller.datamimic.core.mcp.JunieAgent
import com.rapiddweller.datamimic.core.mcp.McpServer
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.time.Instant

class AgentDiscoveryTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `a platform project publishes its scoped Junie configuration`() {
        val projectDir = temp.newFolder("platform-project").toPath()
        registerJunieProject(
            projectDir,
            path = null,
            server = McpServer(
                "https://dm.example/api/v2/mcp/projects/project-1",
                mapOf("Authorization" to "Bearer token", "X-DATAMIMIC-Client-Binding" to "binding"),
                Instant.EPOCH,
            ),
        )

        val entry = json.parseToJsonElement(Files.readString(projectDir.resolve(".junie/mcp/mcp.json")))
            .jsonObject.getValue("mcpServers").jsonObject.getValue("datamimic-platform").jsonObject
        assertEquals("https://dm.example/api/v2/mcp/projects/project-1", entry.getValue("url").jsonPrimitive.content)
        assertEquals("Bearer token", entry.getValue("headers").jsonObject.getValue("Authorization").jsonPrimitive.content)
        val guidance = Files.readString(projectDir.resolve(JunieAgent.EXCLUSIVE_GUIDANCE_PATH))
        assertTrue(guidance.contains("only the available `datamimic_*` MCP tools"))
        assertTrue(guidance.contains("Never start data generation"))
        assertTrue(guidance.contains("Commit only a Ready execution"))
        assertTrue(guidance.contains("bounded authoring dry run"))
        assertTrue(Files.notExists(projectDir.resolve(JunieAgent.ROUTING_RULE_PATH)))
    }
}
