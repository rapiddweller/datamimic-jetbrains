// DATAMIMIC for JetBrains IDEs
// Copyright (c) 2026 Rapiddweller Asia Co., Ltd.
// SPDX-License-Identifier: MIT

package com.rapiddweller.datamimic.ide

import com.rapiddweller.datamimic.core.json
import com.rapiddweller.datamimic.core.mcp.AgentConnection
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
    fun `an activated project discovers Junie and writes its MCP configuration`() {
        val projectDir = temp.newFolder("project").toPath()
        val userHome = temp.newFolder("home").toPath()
        Files.createDirectories(userHome.resolve(".junie"))
        var activated = false
        val connection = AgentConnection(
            activate = { activated = true },
            deactivate = {},
            server = { McpServer("https://dm.example/mcp", mapOf("Authorization" to "Bearer token"), Instant.EPOCH) },
            agents = { discoverAgents(projectDir, path = null, userHome) },
        )

        val publication = connection.connect("p1")

        assertTrue(activated)
        assertEquals(listOf("Junie"), publication.connected)
        val config = json.parseToJsonElement(Files.readString(projectDir.resolve(".junie/mcp/mcp.json"))).jsonObject
        assertEquals("https://dm.example/mcp", config.getValue("mcpServers").jsonObject.getValue("datamimic-platform").jsonObject.getValue("url").jsonPrimitive.content)
    }
}
