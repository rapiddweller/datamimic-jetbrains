// DATAMIMIC for JetBrains IDEs
// Copyright (c) 2026 Rapiddweller Asia Co., Ltd.
// SPDX-License-Identifier: MIT

package com.rapiddweller.datamimic.ide

import com.intellij.icons.AllIcons
import com.intellij.ide.impl.ProjectUtil
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.EDT
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.rapiddweller.datamimic.core.PlatformProject
import com.rapiddweller.datamimic.core.generation.TaskType
import com.rapiddweller.datamimic.core.mcp.MCP_SERVER_NAME
import com.rapiddweller.datamimic.core.workspace.FolderIdentity
import com.rapiddweller.datamimic.core.workspace.ProjectFolder
import com.rapiddweller.datamimic.ide.editing.flushPlatformEdits
import com.rapiddweller.datamimic.ide.generation.RunResults
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.awt.datatransfer.StringSelection
import java.nio.file.Path
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.swing.Icon

internal const val NOTIFICATION_GROUP = "DATAMIMIC"

private val prettyJson = Json { prettyPrint = true }

/** Where platform projects are kept on this computer. */
private val PROJECTS_HOME: Path = Path.of(System.getProperty("user.home"), "DATAMIMIC")

/** Everything the user can do with a project row of the DATAMIMIC tool window. */
internal class PlatformOperations(
    private val project: Project,
    private val scope: CoroutineScope,
    private val active: ActiveProject,
    private val selection: () -> PlatformNode?,
) {
    private val platform = DatamimicPlatform.getInstance()

    val openProject = action("Open Project", AllIcons.Actions.MenuOpen, { it is PlatformNode.ProjectNode && !isActive(it) }) { node ->
        if (node is PlatformNode.ProjectNode) openProject(node.project)
    }

    val generate = action("Generate Data…", AllIcons.Actions.Execute, ::isActiveProjectNode) { node ->
        if (node is PlatformNode.ProjectNode) chooseTaskType(node.project)
    }

    /** AI Assistant has no API or file for its MCP servers, so its entry is handed over for "Add → As JSON". */
    val copyAiAssistantConfiguration = action("Copy MCP Configuration for AI Assistant", AllIcons.Actions.Copy, ::isActiveProjectNode) { node ->
        if (node is PlatformNode.ProjectNode) copyAiAssistantConfiguration(node.project)
    }

    /**
     * Downloads [target] into its folder under [PROJECTS_HOME] and opens that as a normal project in a new window, or
     * brings its window to the front when it is already open.
     */
    fun openProject(target: PlatformProject) = perform("Cannot Open Project") {
        val origin = (platform.state.value as? AuthState.SignedIn)?.origin ?: return@perform
        val root = ProjectFolder.locationFor(PROJECTS_HOME, origin, target)
        if (ProjectUtil.findAndFocusExistingProjectForPath(root) != null) return@perform
        withBackgroundProgress(project, "Downloading ${target.name}") {
            withContext(Dispatchers.IO) {
                val folder = ProjectFolder(root)
                if (!folder.isProjectFolder()) folder.writeIdentity(FolderIdentity(origin, target.id, target.name))
                platform.workspace(target.id, folder).sync.syncNow()
            }
        }
        // WHY: the OpenProjectTask builder compiles an internal constructor into the plugin, which newer IDEs removed.
        ProjectUtil.openOrImport(root, null, true)
    }

    fun signOut() = perform("Sign Out Failed") {
        // WHY: agents, locks and project tokens can only be given back while the session still exists.
        disconnectAllWindowsForSignOut()
        val revoked = withContext(Dispatchers.IO) { platform.signOut() }
        if (!revoked) notify("Signed out locally. The platform was unreachable, so the session expires there on its own.", NotificationType.WARNING)
    }

    private fun copyAiAssistantConfiguration(target: PlatformProject) = perform("Cannot Create the MCP Configuration") {
        val server = withContext(Dispatchers.IO) { platform.mcpServer(target.id) }
        val configuration = buildJsonObject {
            putJsonObject("mcpServers") {
                putJsonObject(MCP_SERVER_NAME) {
                    put("type", "streamable-http")
                    put("url", server.url)
                    putJsonObject("headers") { server.headers.forEach { (name, value) -> put(name, value) } }
                }
            }
        }
        CopyPasteManager.getInstance().setContents(StringSelection(prettyJson.encodeToString(JsonObject.serializer(), configuration)))
        val expires = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault()).format(server.expiresAt)
        notify(
            "Copied. In Settings | Tools | AI Assistant | Model Context Protocol (MCP), choose Add, then As JSON, at project level. " +
                "The token in it is valid until $expires; copy again after that.",
            NotificationType.INFORMATION,
        )
    }

    private fun chooseTaskType(target: PlatformProject) {
        JBPopupFactory.getInstance()
            .createPopupChooserBuilder(TaskType.entries)
            .setTitle("Generate Data for ${target.name}")
            .setRenderer(textListCellRenderer { it?.label.orEmpty() })
            .setItemChosenCallback { runGeneration(target, it) }
            .createPopup()
            .showInFocusCenter()
    }

    private fun runGeneration(target: PlatformProject, taskType: TaskType) {
        val session = active.session() ?: return
        // WHY: the platform generates from its stored files, so local edits must reach it first.
        val notOnPlatform = flushPlatformEdits(project, session)
        if (notOnPlatform.isNotEmpty() && Messages.showOkCancelDialog(
                project,
                "These files have changes that are not on the platform, so the run would not use them:\n" + notOnPlatform.sorted().joinToString("\n"),
                "Generate Data",
                "Generate Anyway",
                Messages.getCancelButton(),
                Messages.getWarningIcon(),
            ) != Messages.OK
        ) {
            return
        }
        perform("Generation Failed") {
            val outcome = withBackgroundProgress(project, "Generating data for ${target.name}") {
                withContext(Dispatchers.IO) { platform.generation.run(target.id, taskType) }
            }
            val logs = withContext(Dispatchers.IO) { runCatching { platform.generation.logs(target.id, outcome.taskId) }.getOrDefault("") }
            val previews = withContext(Dispatchers.IO) { runCatching { platform.generation.previews(target.id, outcome.taskId) }.getOrDefault(emptyList()) }
            RunResults.show(project, target.name, outcome, logs, previews)
            val type = if (outcome.status.succeeded) NotificationType.INFORMATION else NotificationType.WARNING
            val text = if (outcome.status.terminal) "finished: ${outcome.status}" else "is still running on the platform"
            notify("Data generation for ${target.name} $text.", type)
        }
    }

    private fun perform(title: String, block: suspend CoroutineScope.() -> Unit) {
        scope.launch(Dispatchers.EDT) {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Messages.showErrorDialog(project, e.message ?: e.javaClass.simpleName, title)
            }
        }
    }

    private fun notify(text: String, type: NotificationType) {
        NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP).createNotification(text, type).notify(project)
    }

    private fun action(text: String, icon: Icon, enabledFor: (PlatformNode?) -> Boolean, perform: (PlatformNode?) -> Unit) =
        object : DumbAwareAction(text, null, icon) {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT

            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = platform.state.value is AuthState.SignedIn && enabledFor(selection())
            }

            override fun actionPerformed(e: AnActionEvent) = perform(selection())
        }

    private fun isActive(node: PlatformNode.ProjectNode): Boolean {
        val identity = active.identity ?: return false
        return identity.projectId == node.project.id && identity.origin == (platform.state.value as? AuthState.SignedIn)?.origin
    }

    private fun isActiveProjectNode(node: PlatformNode?) = node is PlatformNode.ProjectNode && isActive(node) && active.session() != null
}
