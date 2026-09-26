// DATAMIMIC for JetBrains IDEs
// Copyright (c) 2026 Rapiddweller Asia Co., Ltd.
// SPDX-License-Identifier: MIT

package com.rapiddweller.datamimic.core.mcp

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class AgentConnectionTest {
    private val events = mutableListOf<String>()
    private val failingProjects = mutableSetOf<String>()
    private val agent = object : McpAgent {
        override val displayName = "Agent"

        override fun register(server: McpServer): List<String> {
            events += "register ${server.url}"
            return emptyList()
        }

        override fun unregister() {
            events += "unregister"
        }
    }
    private val connection = AgentConnection(
        activate = { events += "activate $it" },
        deactivate = { events += "deactivate $it" },
        server = { id ->
            if (id in failingProjects) throw IllegalStateException("no token for $id")
            McpServer(id, emptyMap(), Instant.EPOCH)
        },
        agents = { listOf(agent) },
    )

    @Test
    fun `the workspace is taken once, and connecting again only registers the agents again`() {
        connection.connect("A")
        connection.connect("A")

        assertEquals(listOf("activate A", "register A", "register A"), events)
        assertEquals("A", connection.activeProjectId)
    }

    @Test
    fun `when a renewed token cannot be created, no agent keeps pointing at the old one`() {
        connection.connect("A")
        events.clear()
        failingProjects += "A"

        val publication = connection.connect("A")

        assertEquals(listOf("unregister"), events)
        assertEquals(listOf("Project token: no token for A"), publication.failures)
    }

    @Test
    fun `agents are only unregistered when something was registered`() {
        connection.unregisterAgents()
        connection.leave()

        assertEquals(emptyList<String>(), events)
    }

    @Test
    fun `a registered agent can report a non-fatal setup warning`() {
        val warningAgent = object : McpAgent {
            override val displayName = "Warning Agent"
            override fun register(server: McpServer) = listOf("project guidance overrides routing")
            override fun unregister() = Unit
        }
        val warningConnection = AgentConnection({}, {}, { McpServer(it, emptyMap(), Instant.EPOCH) }, { listOf(warningAgent) })

        val publication = warningConnection.connect("A")

        assertEquals(listOf("Warning Agent"), publication.connected)
        assertEquals(listOf("Warning Agent: project guidance overrides routing"), publication.warnings)
        assertEquals(emptyList<String>(), publication.failures)
    }

    @Test
    fun `leaving disconnects the agents before the workspace is given back`() {
        connection.connect("A")
        events.clear()

        connection.leave()

        assertEquals(listOf("unregister", "deactivate A"), events)
        assertEquals(null, connection.activeProjectId)
    }
}
