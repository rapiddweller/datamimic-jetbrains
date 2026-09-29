// DATAMIMIC for JetBrains IDEs
// Copyright (c) 2026 Rapiddweller Asia Co., Ltd.
// SPDX-License-Identifier: MIT

package com.rapiddweller.datamimic.ide

import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.rapiddweller.datamimic.core.PlatformProject
import com.rapiddweller.datamimic.core.generation.TaskType
import com.rapiddweller.datamimic.ide.generation.RunResults
import com.rapiddweller.datamimic.ide.generation.startGeneration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.swing.Icon

internal const val NOTIFICATION_GROUP = "DATAMIMIC"

/** Everything the user can do with a project row; [project] and [active] are null on the Welcome screen. */
internal class PlatformOperations(
    private val project: Project?,
    private val scope: CoroutineScope,
    private val active: ActiveProject?,
    private val selection: () -> PlatformNode?,
    private val open: suspend (Project?, PlatformProject) -> Unit = ::openPlatformProject,
) {
    private val platform = DatamimicPlatform.getInstance()

    val openProject = action("Open Project", AllIcons.Actions.MenuOpen, { it is PlatformNode.ProjectNode && !isActive(it) }) { node ->
        if (node is PlatformNode.ProjectNode) openProject(node.project)
    }

    val generate = action("Generate Data…", AllIcons.Actions.Execute, ::isActiveProjectNode) { node ->
        if (node is PlatformNode.ProjectNode) chooseTaskType(node.project)
    }

    fun openProject(target: PlatformProject) {
        // WHY: opening a project disposes the Welcome screen and its panel scope.
        platform.scope.launch(Dispatchers.EDT) { open(project, target) }
    }

    fun signOut() = perform("Sign Out Failed") {
        val revoked = withContext(Dispatchers.IO) { platform.signOut() }
        if (!revoked) notify("Signed out locally. The platform was unreachable, so the session expires there on its own.", NotificationType.WARNING)
    }

    private fun chooseTaskType(target: PlatformProject) {
        JBPopupFactory.getInstance()
            .createPopupChooserBuilder(TaskType.entries)
            .setTitle("Generate Data for ${target.name}")
            .setRenderer(textListCellRenderer { it?.label.orEmpty() })
            .setItemChosenCallback(::runGeneration)
            .createPopup()
            .showInFocusCenter()
    }

    private fun runGeneration(taskType: TaskType) {
        val project = project ?: return
        val identity = active?.identity ?: return
        startGeneration(
            project = project,
            identity = identity,
            taskType = taskType,
            scope = scope,
            acceptRun = { run ->
                RunResults.show(project, identity, run, scope)
                true
            },
        )
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
        val identity = active?.identity ?: return false
        return identity.projectId == node.project.id && identity.origin == (platform.state.value as? AuthState.SignedIn)?.origin
    }

    private fun isActiveProjectNode(node: PlatformNode?) = node is PlatformNode.ProjectNode && isActive(node) && active?.session() != null
}
