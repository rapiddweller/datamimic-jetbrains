// DATAMIMIC for JetBrains IDEs
// Copyright (c) 2026 Rapiddweller Asia Co., Ltd.
// SPDX-License-Identifier: MIT

package com.rapiddweller.datamimic.ide.creation

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.serviceIfCreated
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.ide.progress.ModalTaskOwner
import com.intellij.platform.ide.progress.withModalProgress
import com.rapiddweller.datamimic.core.PlatformErrorCode
import com.rapiddweller.datamimic.core.PlatformException
import com.rapiddweller.datamimic.core.creation.container.uploadFlows
import com.rapiddweller.datamimic.core.creation.street.PlatformResult
import com.rapiddweller.datamimic.core.creation.street.UploadFailedException
import com.rapiddweller.datamimic.core.creation.street.createOnPlatform
import com.rapiddweller.datamimic.core.creation.street.existingTargets
import com.rapiddweller.datamimic.core.creation.street.sourceProblem
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.nio.file.Path

private const val TITLE = "Generate DATAMIMIC Model"
private const val FAILED = "Cannot Generate Model"

/**
 * Generates a DATAMIMIC model from the selected JSON, XML or CSV file, in the folder of that file. This is the second
 * composition root of the creation flows: it names the upload flows.
 */
class GenerateModelFromFileAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = sourceFile(e) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val source = sourceFile(e) ?: return
        val identity = project.serviceIfCreated<ActiveProject>()?.identity ?: return
        // WHY: the upload sends the file on disk; here the UI thread holds the write-intent lock that a save needs.
        val documents = FileDocumentManager.getInstance()
        documents.getCachedDocument(source.file)?.takeIf { documents.isDocumentUnsaved(it) }?.let { documents.saveDocument(it) }
        DatamimicPlatform.getInstance().scope.launch(Dispatchers.EDT) { generate(project, identity, source) }
    }
}

/** [dir] is the project folder of [file], empty for the project root; the model goes there. */
private class SourceFile(val file: VirtualFile, val path: Path, val dir: String)

/** The selected source file, or null when the action does not apply here. */
private fun sourceFile(e: AnActionEvent): SourceFile? {
    val project = e.project ?: return null
    // WHY: update() runs in every window, and creating the services signs in to the platform over the network.
    val active = project.serviceIfCreated<ActiveProject>() ?: return null
    val identity = active.identity ?: return null
    val folder = active.folder ?: return null
    if (platformTargetError(project, identity) != null) return null
    val file = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)?.singleOrNull() ?: return null
    if (file.isDirectory || uploadFlows(file.name).isEmpty()) return null
    val path = file.toNioPathOrNull() ?: return null
    val parent = path.parent ?: return null
    val dir = if (parent == folder.root) "" else folder.pathOf(parent) ?: return null
    return SourceFile(file, path, dir)
}

/** UI thread. Asks for the flow, the choices and the name, asks before it replaces files, then generates and opens the model. */
private suspend fun generate(project: Project, identity: FolderIdentity, source: SourceFile) {
    val platform = DatamimicPlatform.getInstance()
    val owner = ModalTaskOwner.project(project)
    val sourcePath = source.path.toString()
    sourceProblem(sourcePath)?.let { return Messages.showErrorDialog(project, it, TITLE) }
    val flows = uploadFlows(sourcePath)
    val dir = source.dir
    val dialogOf = { last: CreationDialog?, error: String? ->
        CreationDialog(
            project, flows, dir, last?.flow?.id, last?.fileName.orEmpty(), error, last?.choices.orEmpty(),
            dialogTitle = "Generate DATAMIMIC model", source = source.file.name,
        )
    }
    var dialog = dialogOf(null, null)
    while (dialog.showAndGet()) {
        val request = dialog.flow.toRequest(dir, dialog.fileName, dialog.choices)
        val targets = request.targetPaths().joinToString(", ")
        val existing = try {
            val session = platformSession(project, identity)
            withModalProgress(owner, "Checking existing files", Cancellation.cancellable()) {
                existingTargets(request, platform.workspace, session)
            }
        } catch (e: CancellationException) {
            // WHY: a cancel here changed nothing, so the user goes back to the dialog.
            currentCoroutineContext().ensureActive()
            dialog = dialogOf(dialog, null)
            continue
        } catch (e: Exception) {
            return fail(project, FAILED, e)
        }
        if (existing.isNotEmpty() && !confirmReplace(project, existing)) {
            dialog = dialogOf(dialog, null)
            continue
        }
        // WHY: the user can switch the project or sign out while a dialog is open.
        val session = try {
            platformSession(project, identity)
        } catch (e: IllegalStateException) {
            return fail(project, FAILED, e)
        }
        val result = try {
            withModalProgress(owner, "Generating model", Cancellation.cancellable()) {
                createOnPlatform(request, platform.workspace, platform.uploads, session)
            }
        } catch (e: CancellationException) {
            // WHY: Cancel stops only the wait. The platform task goes on, so the result is "still running".
            currentCoroutineContext().ensureActive()
            null
        } catch (e: PlatformException) {
            when {
                e.code == PlatformErrorCode.UPLOAD_GENERATE_ALREADY_RUNNING -> return Messages.showInfoMessage(
                    project,
                    "A model run for this name is already in progress. Wait for it to finish, or choose another name.",
                    TITLE,
                )
                e.status in 400..499 -> {
                    dialog = dialogOf(dialog, e.detail)
                    continue
                }
                else -> return fail(project, FAILED, e)
            }
        } catch (e: UploadFailedException) {
            return fail(project, FAILED, e, "${e.message}\n\nThe platform can have written some of the files. Check the Project view.")
        } catch (e: Exception) {
            return fail(project, FAILED, e)
        }
        return when (result) {
            is PlatformResult.Created -> {
                val model = request.targetPaths().first()
                pullAndOpen(project, owner, result.paths, session) { it == model }
            }
            is PlatformResult.StillRunning, null ->
                notify(project, "The platform is still generating $targets. The files appear when it is done.")
        }
    }
}

private fun confirmReplace(project: Project, existing: List<String>): Boolean {
    val message = "These files exist:\n\n${existing.joinToString("\n")}\n\nThe platform replaces them and does not ask again."
    return Messages.showYesNoDialog(project, message, TITLE, "Replace", "Back", Messages.getWarningIcon()) == Messages.YES
}
