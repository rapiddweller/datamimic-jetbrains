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

        override fun register(server: McpServer) {
            events += "register ${server.url}"
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
    fun `a switch publishes the new project before it leaves the old one`() {
        connection.switchTo("A")
        events.clear()

        connection.switchTo("B")

        assertEquals(listOf("activate B", "register B", "deactivate A"), events)
        assertEquals("B", connection.activeProjectId)
    }

    @Test
    fun `when the new project cannot be published, no agent keeps pointing at the old one`() {
        connection.switchTo("A")
        events.clear()
        failingProjects += "B"

        val publication = connection.switchTo("B")

        assertEquals(listOf("activate B", "unregister", "deactivate A"), events)
        assertEquals(listOf("Project token: no token for B"), publication.failures)
    }

    @Test
    fun `agents are only unregistered when something was registered`() {
        connection.unregisterAgents()
        connection.leave()

        assertEquals(emptyList<String>(), events)
    }

    @Test
    fun `leaving disconnects the agents before the workspace is given back`() {
        connection.switchTo("A")
        events.clear()

        connection.leave()

        assertEquals(listOf("unregister", "deactivate A"), events)
        assertEquals(null, connection.activeProjectId)
    }
}
