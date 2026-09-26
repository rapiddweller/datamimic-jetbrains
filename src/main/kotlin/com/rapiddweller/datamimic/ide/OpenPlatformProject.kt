// DATAMIMIC for JetBrains IDEs
// Copyright (c) 2026 Rapiddweller Asia Co., Ltd.
// SPDX-License-Identifier: MIT

package com.rapiddweller.datamimic.ide

import com.intellij.ide.impl.OpenProjectTask
import com.intellij.ide.impl.ProjectUtil
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.platform.ide.progress.ModalTaskOwner
import com.intellij.platform.ide.progress.TaskCancellation
import com.intellij.platform.ide.progress.withModalProgress
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.rapiddweller.datamimic.core.PlatformProject
import com.rapiddweller.datamimic.core.workspace.FolderIdentity
import com.rapiddweller.datamimic.core.workspace.ProjectFolder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Path

/** Where platform projects are kept on this computer. */
private val PROJECTS_HOME: Path = Path.of(System.getProperty("user.home"), "DATAMIMIC")

private val LOG = logger<OpenPlatformProjectAction>()

/**
 * Signs in if needed, lets the user pick a platform project, and opens its folder in a new window. Needs no open
 * project, so it also works from the Welcome screen.
 */
class OpenPlatformProjectAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project
        val platform = DatamimicPlatform.getInstance()
        platform.scope.launch(Dispatchers.EDT) {
            val auth = withModalProgress(owner(project), "Checking DATAMIMIC sign-in", TaskCancellation.cancellable()) {
                platform.state.first { it != AuthState.Unknown }
            }
            if (auth !is AuthState.SignedIn && !signInInteractively(project)) return@launch
            val projects = try {
                withModalProgress(owner(project), "Loading DATAMIMIC projects", TaskCancellation.cancellable()) {
                    withContext(Dispatchers.IO) { platform.projects.list() }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LOG.warn("Loading platform projects failed: $e")
                return@launch Messages.showErrorDialog(project, e.message ?: e.javaClass.simpleName, "Cannot Load Projects")
            }
            if (projects.isEmpty()) return@launch Messages.showInfoMessage(project, "There are no projects on the platform yet.", "Open DATAMIMIC Project")
            JBPopupFactory.getInstance()
                .createPopupChooserBuilder(projects)
                .setTitle("Open DATAMIMIC Project")
                .setRenderer(textListCellRenderer { it?.name.orEmpty() })
                .setNamerForFiltering { it.name }
                .setItemChosenCallback { chosen ->
                    // WHY: opening the project may close the Welcome screen that supplied this action.
                    platform.scope.launch(Dispatchers.EDT) { openPlatformProject(project, chosen) }
                }
                .createPopup()
                .showInFocusCenter()
        }
    }
}

/**
 * Asks for the platform URL and credentials, prefilled with the last sign-in, until signing in works or the user
 * cancels. UI thread. @return whether the user is signed in now.
 */
internal suspend fun signInInteractively(project: Project?): Boolean {
    val platform = DatamimicPlatform.getInstance()
    var prefill = withContext(Dispatchers.IO) { platform.savedLogin() }
    while (true) {
        val dialog = LoginDialog(project, prefill)
        if (!dialog.showAndGet()) return false
        val input = dialog.input()
        try {
            withModalProgress(owner(project), "Signing in to DATAMIMIC", TaskCancellation.cancellable()) {
                withContext(Dispatchers.IO) { platform.signIn(input) }
            }
            LOG.info("Signed in to ${input.origin.value}")
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LOG.warn("Sign-in to ${input.origin.value} failed: $e")
            Messages.showErrorDialog(project, e.message ?: e.javaClass.simpleName, "Sign-In Failed")
            prefill = SavedLogin(input.origin.value, input.email, input.password, input.rememberPassword)
        }
    }
}

/**
 * Downloads [target] into its folder under [PROJECTS_HOME] and opens that as a normal project in a new window, or
 * brings its window to the front when it is already open. UI thread.
 */
internal suspend fun openPlatformProject(project: Project?, target: PlatformProject) {
    val platform = DatamimicPlatform.getInstance()
    val origin = (platform.state.value as? AuthState.SignedIn)?.origin ?: return
    val root = ProjectFolder.locationFor(PROJECTS_HOME, origin, target)
    if (ProjectUtil.findAndFocusExistingProjectForPath(root) != null) return
    try {
        withModalProgress(owner(project), "Downloading ${target.name}", TaskCancellation.cancellable()) {
            withContext(Dispatchers.IO) {
                val folder = ProjectFolder(root)
                if (!folder.isProjectFolder()) folder.writeIdentity(FolderIdentity(origin, target.id, target.name))
                platform.workspace(target.id, folder).sync.syncNow()
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        LOG.warn("Downloading ${target.id} failed: $e")
        return Messages.showErrorDialog(project, e.message ?: e.javaClass.simpleName, "Cannot Open Project")
    }
    openDownloadedProject(root)
}

/** Opens without blocking the Welcome screen's event loop. */
internal suspend fun openDownloadedProject(root: Path) =
    ProjectUtil.openOrImportAsync(root, downloadedProjectTask())

internal fun downloadedProjectTask(): OpenProjectTask = OpenProjectTask.build().withForceOpenInNewFrame(true)

private fun owner(project: Project?): ModalTaskOwner = project?.let(ModalTaskOwner::project) ?: ModalTaskOwner.guess()
