// DATAMIMIC for JetBrains IDEs
// Copyright (c) 2026 Rapiddweller Asia Co., Ltd.
// SPDX-License-Identifier: MIT

package com.rapiddweller.datamimic.ide.creation

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.serviceIfCreated
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.platform.ide.progress.ModalTaskOwner
import com.intellij.platform.ide.progress.withModalProgress
import com.rapiddweller.datamimic.core.PlatformException
import com.rapiddweller.datamimic.core.creation.container.templateFlows
import com.rapiddweller.datamimic.core.creation.street.PlatformResult
import com.rapiddweller.datamimic.core.creation.street.createOnPlatform
import com.rapiddweller.datamimic.core.creation.street.targetPaths
import com.rapiddweller.datamimic.core.workspace.FolderIdentity
import com.rapiddweller.datamimic.ide.ActiveProject
import com.rapiddweller.datamimic.ide.Cancellation
import com.rapiddweller.datamimic.ide.DatamimicPlatform
import com.rapiddweller.datamimic.ide.creation.truck.CreationDialog
import com.rapiddweller.datamimic.ide.platformSession
import com.rapiddweller.datamimic.ide.platformTargetError
import com.rapiddweller.datamimic.ide.toNioPathOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TITLE = "New DATAMIMIC File"

/**
 * Creates a file from a platform template in the selected folder of the window's DATAMIMIC project. This is the
 * composition root of the template flows. Only this class and [GenerateModelFromFileAction] name the flow containers.
 */
class NewDatamimicFileAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = targetDir(e) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val dir = targetDir(e) ?: return
        val identity = project.serviceIfCreated<ActiveProject>()?.identity ?: return
        DatamimicPlatform.getInstance().scope.launch(Dispatchers.EDT) { create(project, identity, dir) }
    }
}

/** The project folder of the new file, empty for the project root, or null when the action does not apply here. */
private fun targetDir(e: AnActionEvent): String? {
    val project = e.project ?: return null
    // WHY: update() runs in every window, and creating the services signs in to the platform over the network.
    val active = project.serviceIfCreated<ActiveProject>() ?: return null
    val identity = active.identity ?: return null
    val folder = active.folder ?: return null
    if (platformTargetError(project, identity) != null) return null
    val selected = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return ""
    val dir = (if (selected.isDirectory) selected else selected.parent)?.toNioPathOrNull() ?: return null
    return if (dir == folder.root) "" else folder.pathOf(dir)
}

/** UI thread. Loads the file types, asks for the type and the name, creates the file and opens it. */
private suspend fun create(project: Project, identity: FolderIdentity, dir: String) {
    val platform = DatamimicPlatform.getInstance()
    val owner = ModalTaskOwner.project(project)
    val flows = try {
        val session = platformSession(project, identity)
        templateFlows(withModalProgress(owner, "Loading DATAMIMIC file types", Cancellation.cancellable()) {
            withContext(Dispatchers.IO) { platform.workspace.templates(session.projectId) }
        })
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        return fail(project, "Cannot Load File Types", e)
    }
    if (flows.isEmpty()) return Messages.showInfoMessage(project, "The platform offers no file types for this project.", TITLE)
    var dialog = CreationDialog(project, flows, dir)
    while (dialog.showAndGet()) {
        val request = dialog.flow.toRequest(dir, dialog.fileName)
        // WHY: the user can sign out or sign in to another platform while the dialog is open.
        val session = try {
            platformSession(project, identity)
        } catch (e: IllegalStateException) {
            return fail(project, "Cannot Create File", e)
        }
        val result = try {
            // WHY: no cancel button, because a cancel cannot stop a create that the platform already received.
            withModalProgress(owner, "Creating ${request.targetPaths().joinToString()}", Cancellation.nonCancellable()) {
                createOnPlatform(request, platform.workspace, platform.uploads, session)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: PlatformException) {
            if (e.status !in 400..499) return fail(project, "Cannot Create File", e)
            dialog = CreationDialog(project, flows, dir, dialog.flow.id, dialog.fileName, e.detail)
            continue
        } catch (e: Exception) {
            return fail(project, "Cannot Create File", e)
        }
        val paths = when (result) {
            is PlatformResult.Created -> result.paths
            // WHY: only an upload task outlives the wait; a template create answers at once.
            is PlatformResult.StillRunning -> return notify(project, "The platform still creates ${request.targetPaths().joinToString()}.")
        }
        return pullAndOpen(project, owner, paths, session) { true }
    }
}
