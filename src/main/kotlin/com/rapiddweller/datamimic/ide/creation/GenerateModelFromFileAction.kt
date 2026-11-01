// DATAMIMIC for JetBrains IDEs
// Copyright (c) 2026 Rapiddweller Asia Co., Ltd.
// SPDX-License-Identifier: MIT

package com.rapiddweller.datamimic.ide.creation

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.serviceIfCreated
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
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
import com.rapiddweller.datamimic.ide.toNioPathOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

private const val TITLE = "Generate DATAMIMIC Model"
private const val FAILED = "Cannot Generate Model"

/**
 * Generates a DATAMIMIC model from a JSON, XML or CSV file in the selected folder of the window's DATAMIMIC project. A
 * selected source file in the Project view skips the file chooser. This is the second composition root of the creation
 * flows: it names the upload flows.
 */
class GenerateModelFromFileAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = targetDir(e) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val dir = targetDir(e) ?: return
        val identity = project.serviceIfCreated<ActiveProject>()?.identity ?: return
        val selected = e.getData(CommonDataKeys.VIRTUAL_FILE)?.takeIf { !it.isDirectory && uploadFlows(it.name).isNotEmpty() }
        val source = selected ?: FileChooser.chooseFile(sourceChooser(), project, null) ?: return
        val path = source.toNioPathOrNull() ?: return Messages.showErrorDialog(project, "Choose a file on the local disk.", TITLE)
        // WHY: the upload sends the file on disk; here the UI thread holds the write-intent lock that a save needs.
        val documents = FileDocumentManager.getInstance()
        documents.getCachedDocument(source)?.takeIf { documents.isDocumentUnsaved(it) }?.let { documents.saveDocument(it) }
        DatamimicPlatform.getInstance().scope.launch(Dispatchers.EDT) { generate(project, identity, dir, path) }
    }
}

// WHY: the chooser takes dropped files, and the flows decide which files it offers.
private fun sourceChooser() = FileChooserDescriptor(true, false, false, false, false, false)
    .withTitle("Choose a JSON, XML or CSV File")
    .withFileFilter { uploadFlows(it.name).isNotEmpty() }

/** The start of [path] for the delimiter detection. */
private fun readSample(path: Path): String = Files.newInputStream(path).use { it.readNBytes(SAMPLE_BYTES) }.toString(Charsets.UTF_8)

private const val SAMPLE_BYTES = 8192

/** UI thread. Asks for the flow, the choices and the name, asks before it replaces files, then generates and opens the model. */
private suspend fun generate(project: Project, identity: FolderIdentity, dir: String, source: Path) {
    val platform = DatamimicPlatform.getInstance()
    val owner = ModalTaskOwner.project(project)
    val sourcePath = source.toString()
    sourceProblem(sourcePath)?.let { return Messages.showErrorDialog(project, it, TITLE) }
    val sample = try {
        readSample(source)
    } catch (e: IOException) {
        return fail(project, FAILED, e)
    }
    val sourceProjectPath = project.serviceIfCreated<ActiveProject>()?.folder?.pathOf(source)
    val flows = uploadFlows(sourcePath, dir, sourceProjectPath, sample)
    val dialogOf = { last: CreationDialog?, error: String? ->
        CreationDialog(
            project, flows, dir, last?.flow?.id, last?.fileName.orEmpty(), error, last?.choices.orEmpty(),
            dialogTitle = "Generate DATAMIMIC model", source = sourcePath,
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
