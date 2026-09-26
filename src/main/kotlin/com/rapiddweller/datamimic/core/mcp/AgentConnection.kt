// DATAMIMIC for JetBrains IDEs
// Copyright (c) 2026 Rapiddweller Asia Co., Ltd.
// SPDX-License-Identifier: MIT

package com.rapiddweller.datamimic.core.mcp

/** Outcome of pointing the agents at a project, for the notification the user sees. */
class Publication(
    val server: McpServer?,
    val connected: List<String>,
    val failures: List<String>,
    val warnings: List<String> = emptyList(),
)

/** The platform project of one IDE window and the agents pointed at its MCP server. Blocking; call off the UI thread. */
class AgentConnection(
    /** Takes the project's workspace (files, locks, live updates) on the platform. */
    private val activate: (projectId: String) -> Unit,
    /** Gives it back; the last window to leave also revokes the agents' token. */
    private val deactivate: (projectId: String) -> Unit,
    private val server: (projectId: String) -> McpServer,
    private val agents: () -> List<McpAgent>,
) {
    var activeProjectId: String? = null
        private set

    private var registered = false

    /** Takes [projectId]'s workspace once, then points the agents at it; again later, e.g. with a renewed token. */
    @Synchronized
    fun connect(projectId: String): Publication {
        check(activeProjectId == null || activeProjectId == projectId) { "A window stays with one platform project." }
        if (activeProjectId == null) {
            activate(projectId)
            activeProjectId = projectId
        }
        return publish(projectId)
    }

    private fun publish(projectId: String): Publication {
        val current = try {
            server(projectId)
        } catch (e: Exception) {
            // WHY: an older registration would keep pointing at a project whose token is about to be revoked.
            unregisterAgents()
            return Publication(null, emptyList(), listOf("Project token: ${e.message ?: e.javaClass.simpleName}"))
        }
        val outcomes = agents().associateWith { agent -> runCatching { agent.register(current) } }
        registered = registered || outcomes.values.any { it.isSuccess }
        return Publication(
            current,
            outcomes.filterValues { it.isSuccess }.keys.map(McpAgent::displayName),
            outcomes.mapNotNull { (agent, outcome) -> outcome.exceptionOrNull()?.let { "${agent.displayName}: ${it.message}" } },
            outcomes.flatMap { (agent, outcome) -> outcome.getOrNull().orEmpty().map { "${agent.displayName}: $it" } },
        )
    }

    /** Disconnects the agents but keeps the workspace, e.g. while the session is expired. */
    @Synchronized
    fun unregisterAgents() {
        if (!registered) return
        agents().forEach { runCatching { it.unregister() } }
        registered = false
    }

    /** Leaves the active project completely: agents first, then the workspace. */
    @Synchronized
    fun leave() {
        unregisterAgents()
        activeProjectId?.let(deactivate)
        activeProjectId = null
    }
}
