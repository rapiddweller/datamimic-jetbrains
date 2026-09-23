// DATAMIMIC for JetBrains IDEs
// Copyright (c) 2026 Rapiddweller Asia Co., Ltd.
// SPDX-License-Identifier: MIT

package com.rapiddweller.datamimic.ide

import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.components.serviceIfCreated
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectCloseListener
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.ui.Messages
import com.intellij.platform.ide.progress.runWithModalProgressBlocking
import com.intellij.util.EnvironmentUtil
import com.rapiddweller.datamimic.core.mcp.AgentConnection
import com.rapiddweller.datamimic.core.mcp.ClaudeCodeAgent
import com.rapiddweller.datamimic.core.mcp.GitIgnore
import com.rapiddweller.datamimic.core.mcp.JunieAgent
import com.rapiddweller.datamimic.core.mcp.McpAgent
import com.rapiddweller.datamimic.core.mcp.Publication
import com.rapiddweller.datamimic.core.process.findOnPath
import com.rapiddweller.datamimic.core.workspace.FolderIdentity
import com.rapiddweller.datamimic.core.workspace.ProjectFolder
import com.rapiddweller.datamimic.core.workspace.WorkspaceSession
import com.rapiddweller.datamimic.core.workspace.WorkspaceUpdate
import com.rapiddweller.datamimic.ide.editing.PlatformEditors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

/**
 * The platform project this IDE window is the local copy of, like the one open project in the platform UI. The
 * window's folder names it ([ProjectFolder.identity]); a window without one is an ordinary project and stays
 * unconnected. It scopes the synced files, edit locks, live updates and the MCP server the window's IDE agents use.
 */
@Service(Service.Level.PROJECT)
class ActiveProject(private val project: Project, private val scope: CoroutineScope) : Disposable {
    private val platform = DatamimicPlatform.getInstance()
    private val folder: ProjectFolder? = project.basePath?.let { ProjectFolder(Path.of(it)) }?.takeIf { it.isProjectFolder() }

    /** Null for a window that is not a DATAMIMIC project folder. */
    val identity: FolderIdentity? = folder?.identity()

    private val connection = AgentConnection(
        activate = { platform.activate(it, checkNotNull(folder)) },
        deactivate = platform::deactivate,
        server = platform::mcpServer,
        agents = ::agents,
    )
    private var renewal: Job? = null
    private var updates: Job? = null
    private var missingNotice: Notification? = null
    private var otherPlatformNotified = false

    init {
        if (identity != null) {
            scope.launch {
                platform.state.collect { auth ->
                    when (auth) {
                        is AuthState.SignedIn -> if (auth.origin == identity.origin) connect(identity) else notifyOtherPlatform(identity)
                        // WHY: an expired session keeps the folder's sync state, so local edits upload after signing in again.
                        is AuthState.SignedOut -> {
                            renewal?.cancel()
                            withContext(Dispatchers.IO) { connection.unregisterAgents() }
                        }
                        AuthState.Unknown -> Unit
                    }
                }
            }
        }
    }

    /** The window's platform session while it is connected. */
    fun session(): WorkspaceSession? = identity?.let { platform.existingWorkspace(it.projectId) }

    /** Before signing out: disconnects the agents and gives locks and their token back while the session still exists. */
    suspend fun disconnectForSignOut() {
        renewal?.cancel()
        withContext(Dispatchers.IO) { connection.leave() }
    }

    /** UI thread, while the window closes: agents must not keep a live token for a window that is gone. */
    internal fun leaveOnClose() {
        renewal?.cancel()
        if (connection.activeProjectId == null) return
        runWithModalProgressBlocking(project, "Disconnecting DATAMIMIC agents") {
            withTimeoutOrNull(CLOSE_TIMEOUT_MS) { withContext(Dispatchers.IO) { connection.leave() } }
        }
    }

    private suspend fun connect(target: FolderIdentity) {
        renewal?.cancel()
        val publication = withContext(Dispatchers.IO) { connection.switchTo(target.projectId) }
        session()?.let(::watch)
        report(target, publication)
        val server = publication.server ?: return
        renewal = scope.launch {
            delay(Duration.between(Instant.now(), platform.mcpRenewalDue(server)).toMillis().coerceAtLeast(0))
            if (connection.activeProjectId == target.projectId) connect(target)
        }
    }

    /** Shows what the sync reports; a new session after signing in again replaces the old one's watcher. */
    private fun watch(session: WorkspaceSession) {
        updates?.cancel()
        updates = scope.launch(Dispatchers.EDT) {
            session.updates.collect { update ->
                when (update) {
                    is WorkspaceUpdate.MissingLocally -> showMissing(session, update.paths)
                    is WorkspaceUpdate.SyncProblem -> notify(update.message, NotificationType.WARNING)
                    is WorkspaceUpdate.FileChanged, is WorkspaceUpdate.StreamChanged, is WorkspaceUpdate.TreeChanged ->
                        project.service<PlatformEditors>().refreshBanners()
                }
            }
        }
    }

    private fun showMissing(session: WorkspaceSession, paths: Set<String>) {
        missingNotice?.expire()
        missingNotice = null
        if (paths.isEmpty()) return
        val listed = paths.sorted().take(MISSING_LISTED).joinToString("\n") + if (paths.size > MISSING_LISTED) "\n…" else ""
        missingNotice = group().createNotification(
            "${paths.size} files were deleted outside the IDE",
            "They are still on the platform:\n$listed",
            NotificationType.WARNING,
        ).addAction(
            NotificationAction.createSimpleExpiring("Restore") { scope.launch { runSafely { session.sync.restoreMissing(paths) } } },
        ).addAction(
            NotificationAction.createSimpleExpiring("Delete on platform…") { deleteMissing(session, paths) },
        ).also { it.notify(project) }
    }

    private fun deleteMissing(session: WorkspaceSession, paths: Set<String>) {
        val confirmed = Messages.showOkCancelDialog(
            project,
            "Delete ${paths.size} files from the platform project ${identity?.projectName}? Other users lose them too.",
            "Delete on Platform",
            "Delete",
            Messages.getCancelButton(),
            Messages.getWarningIcon(),
        ) == Messages.OK
        if (confirmed) scope.launch { runSafely { session.sync.deleteMissing(paths) } }
    }

    private suspend fun runSafely(block: suspend () -> Unit) {
        runCatching { block() }.onFailure { notify(it.message ?: it.javaClass.simpleName, NotificationType.ERROR) }
    }

    private fun notifyOtherPlatform(target: FolderIdentity) {
        if (otherPlatformNotified) return
        otherPlatformNotified = true
        notify("This folder belongs to ${target.origin.value}. Sign in there to sync ${target.projectName}.", NotificationType.WARNING)
    }

    private fun report(target: FolderIdentity, publication: Publication) {
        if (publication.connected.isNotEmpty()) {
            notify(
                "${target.projectName} is connected to ${publication.connected.joinToString()}. " +
                    "Agent sessions that are already running pick it up after a restart.",
                NotificationType.INFORMATION,
            )
        }
        if (publication.failures.isNotEmpty()) {
            notify("Could not connect IDE agents to ${target.projectName}:\n" + publication.failures.joinToString("\n"), NotificationType.WARNING)
        }
    }

    /** IDE agents of this window that can be connected automatically. */
    private fun agents(): List<McpAgent> {
        val projectDir = folder?.root ?: return emptyList()
        val path = EnvironmentUtil.getValue("PATH")
        return buildList {
            findOnPath("claude", path)?.let { add(ClaudeCodeAgent(it, projectDir)) }
            // WHY: Junie keeps its state in ~/.junie; without it Junie is not in use and gets no token on disk.
            if (Files.isDirectory(Path.of(System.getProperty("user.home"), ".junie"))) {
                add(JunieAgent(projectDir, GitIgnore(projectDir, findOnPath("git", path))))
            }
        }
    }

    private fun group() = NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP)

    private fun notify(text: String, type: NotificationType) = group().createNotification(text, type).notify(project)

    /** Only reached without [leaveOnClose], e.g. when the plugin is unloaded: best effort on the app scope. */
    override fun dispose() {
        if (connection.activeProjectId == null) return
        platform.scope.launch(Dispatchers.IO) { connection.leave() }
    }

    private companion object {
        const val CLOSE_TIMEOUT_MS = 10_000L
        const val MISSING_LISTED = 10
    }
}

internal fun Project.activeProject(): ActiveProject = service()

/** Every connected IDE window, so signing out can disconnect them while the session still exists. */
internal suspend fun disconnectAllWindowsForSignOut() {
    // WHY: serviceIfCreated, so signing out never creates and connects a window that was not connected.
    ProjectManager.getInstance().openProjects.mapNotNull { it.serviceIfCreated<ActiveProject>() }.forEach { it.disconnectForSignOut() }
}

/** Connects a DATAMIMIC project folder when its window opens, without waiting for the tool window. */
class ActiveProjectStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        val base = project.basePath ?: return
        if (ProjectFolder(Path.of(base)).isProjectFolder()) project.activeProject()
    }
}

/** Disconnects the window's agents while it closes, which also covers exiting the IDE. */
class ActiveProjectCloser : ProjectCloseListener {
    override fun projectClosing(project: Project) {
        project.serviceIfCreated<ActiveProject>()?.leaveOnClose()
    }
}
