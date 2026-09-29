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
        deactivate = { projectId, beforeLastDeactivate -> beforeLastDeactivate(); events += "deactivate $projectId" },
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
    fun `when a replacement token cannot be created, agents keep their still-valid registration`() {
        connection.connect("A")
        events.clear()
        failingProjects += "A"

        val publication = connection.connect("A")

        assertEquals(emptyList<String>(), events)
        assertEquals(listOf("Project token: no token for A"), publication.failures)
    }

    @Test
    fun `agents are only unregistered when something was registered`() {
        connection.unregisterAgents()
        connection.leave()

        assertEquals(emptyList<String>(), events)
    }

    @Test
    fun `a project without session agents does not create a project token`() {
        val noAgentConnection = AgentConnection(
            activate = { events += "activate $it" },
            deactivate = { _, _ -> },
            server = { error("must not create a token") },
            agents = { emptyList() },
        )

        val publication = noAgentConnection.connect("A")

        assertEquals(listOf("activate A"), events)
        assertEquals(null, publication.server)
    }

    @Test
    fun `removed agents are unregistered before deciding whether to renew a token`() {
        var available = listOf(agent)
        var mayCreateToken = true
        val dynamicConnection = AgentConnection(
            activate = { events += "activate $it" },
            deactivate = { _, _ -> },
            server = { if (mayCreateToken) McpServer(it, emptyMap(), Instant.EPOCH) else error("removed agents must not create a token") },
            agents = { available },
        )
        dynamicConnection.connect("A")
        events.clear()
        available = emptyList()
        mayCreateToken = false

        dynamicConnection.connect("A")

        assertEquals(listOf("unregister"), events)
    }

    @Test
    fun `a registered agent can report a non-fatal setup warning`() {
        val warningAgent = object : McpAgent {
            override val displayName = "Warning Agent"
            override fun register(server: McpServer) = listOf("project guidance overrides routing")
            override fun unregister() = Unit
        }
        val warningConnection = AgentConnection({}, { _, beforeLastDeactivate -> beforeLastDeactivate() }, { McpServer(it, emptyMap(), Instant.EPOCH) }, { listOf(warningAgent) })

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

    @Test
    fun `leaving unwinds agents in reverse registration order`() {
        fun namedAgent(name: String) = object : McpAgent {
            override val displayName = name
            override fun register(server: McpServer) = emptyList<String>()
            override fun unregister() {
                events += "unregister $name"
            }
        }
        val orderedConnection = AgentConnection(
            activate = {},
            deactivate = { _, beforeLastDeactivate -> beforeLastDeactivate() },
            server = { McpServer(it, emptyMap(), Instant.EPOCH) },
            agents = { listOf(namedAgent("external"), namedAgent("local")) },
        )
        orderedConnection.connect("A")

        orderedConnection.leave()

        assertEquals(listOf("unregister local", "unregister external"), events)
    }

    @Test
    fun `only the last window unregisters shared agents`() {
        var windows = 0
        fun sharedConnection() = AgentConnection(
            activate = { windows++; events += "activate $it" },
            deactivate = { projectId, beforeLastDeactivate -> if (--windows == 0) beforeLastDeactivate(); events += "deactivate $projectId" },
            server = { McpServer(it, emptyMap(), Instant.EPOCH) },
            agents = { listOf(agent) },
        )
        val first = sharedConnection()
        val second = sharedConnection()
        first.connect("A")
        second.connect("A")
        events.clear()

        first.leave()
        assertEquals(listOf("deactivate A"), events)

        second.leave()
        assertEquals(listOf("deactivate A", "unregister", "deactivate A"), events)
    }
}
