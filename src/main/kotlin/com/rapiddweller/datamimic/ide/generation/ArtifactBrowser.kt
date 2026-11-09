// DATAMIMIC for JetBrains IDEs
// Copyright (c) 2026 Rapiddweller Asia Co., Ltd.
// SPDX-License-Identifier: MIT

package com.rapiddweller.datamimic.ide.generation

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.components.JBScrollPane
import com.rapiddweller.datamimic.core.DOWNLOAD_TIMEOUT
import com.rapiddweller.datamimic.core.PlatformSessionFence
import com.rapiddweller.datamimic.core.generation.GenerationRun
import com.rapiddweller.datamimic.core.generation.OperateFormat
import com.rapiddweller.datamimic.ide.DatamimicPlatform
import com.rapiddweller.datamimic.ide.activeProject
import com.rapiddweller.datamimic.ide.toNioPathOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import javax.swing.JButton
import javax.swing.JList
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.DefaultListModel

/** On-demand artifact listing and downloads for one successful task. */
internal class ArtifactBrowser(
    private val project: Project,
    private val run: GenerationRun,
    parentScope: CoroutineScope,
    private val current: () -> Boolean,
) : JPanel(BorderLayout()), Disposable {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext.job))
    private val artifacts = DefaultListModel<com.rapiddweller.datamimic.core.generation.TaskArtifact>()
    private val list = JList(artifacts)
    private val state = JLabel("Open this tab to load artifacts.")
    private val retry = JButton("Retry")
    private val download = JButton("Save / Open")
    private val downloadAll = JButton("Download All (ZIP)")
    private val operate = JButton("Build Operate curl…").apply {
        toolTipText = "Build a curl command for the Artifact Operate API. It works on XML and JSON artifacts."
    }
    private var metadataRequest = 0L
    private var transfer: Job? = null
    private var loading = false
    private var loaded = false
    private var downloading = false
    @Volatile
    private var disposed = false

    init {
        add(state, BorderLayout.NORTH)
        add(JBScrollPane(list), BorderLayout.CENTER)
        add(JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(retry)
            add(download)
            add(downloadAll)
            add(operate)
        }, BorderLayout.SOUTH)
        retry.addActionListener { load(force = true) }
        download.addActionListener {
            list.selectedValue?.path?.let { path ->
                // WHY: a flat artifact keeps the save dialog, where the user can rename the file. A nested one needs a folder.
                val choice = if ('/' in path) chooseFolderTarget(path) else chooseFileTarget(path)?.let { SaveChoice(it, null) }
                choice?.let { transferTo(it, path) { output, keepGoing -> run.downloadArtifact(path, output, keepGoing) } }
            }
        }
        downloadAll.addActionListener {
            val name = "${run.taskId}_artifacts.zip"
            chooseFileTarget(name)?.let { transferTo(SaveChoice(it, null), name) { output, keepGoing -> run.downloadArtifacts(output, keepGoing) } }
        }
        operate.addActionListener { list.selectedValue?.let { openOperateDialog(it.path) } }
        list.addListSelectionListener { if (!it.valueIsAdjusting) updateActions() }
        updateActions()
    }

    /** The command and the response use the active DATAMIMIC project, which must be the project of this task. */
    private fun openOperateDialog(artifactPath: String) {
        if (!current() || project.isDisposed) return
        val format = OperateFormat.of(artifactPath) ?: return
        val identity = project.activeProject().identity
        if (identity == null || identity.projectId != run.projectId) {
            state.text = "The active DATAMIMIC project is not the project of this task."
            return
        }
        OperateCurlDialog(project, artifactPath, format, run.taskId, identity) { task, body ->
            DatamimicPlatform.getInstance().generation.boundTo(identity.origin).operate(identity.projectId, task, body)
        }.show()
    }

    fun load(force: Boolean = false) {
        if (!current() || loading || !force && loaded) return
        val request = ++metadataRequest
        loading = true
        state.text = "Loading artifacts…"
        updateActions()
        scope.launch {
            try {
                val found = withContext(Dispatchers.IO) { run.artifacts() }
                withContext(Dispatchers.EDT) {
                    if (!current() || metadataRequest != request) return@withContext
                    artifacts.removeAllElements()
                    found.forEach(artifacts::addElement)
                    if (found.isNotEmpty()) list.selectedIndex = 0
                    state.text = if (found.isEmpty()) "No artifacts were generated." else "${found.size} artifact(s)."
                    loaded = true
                    loading = false
                    updateActions()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                withContext(Dispatchers.EDT) {
                    if (!current() || metadataRequest != request) return@withContext
                    artifacts.removeAllElements()
                    state.text = "Artifacts unavailable: ${error.message ?: error.javaClass.simpleName}"
                    loaded = true
                    loading = false
                    updateActions()
                }
            }
        }
    }

    private fun idle() = current() && !project.isDisposed && transfer?.isActive != true

    private fun startFolder() = project.basePath?.let { LocalFileSystem.getInstance().findFileByPath(it) }

    /** The file to write and, for a nested artifact, the folder that the file must stay in. */
    private class SaveChoice(val target: Path, val confinedTo: Path?)

    /** The user chooses a folder. A nested artifact such as `xml/order.xml` goes into the matching subfolder of it. */
    private fun chooseFolderTarget(artifactPath: String): SaveChoice? {
        if (!idle()) return null
        val descriptor = FileChooserDescriptor(false, true, false, false, false, false)
            .withTitle("Save Artifact to Folder")
            .withDescription("The artifact $artifactPath is saved below this folder.")
        val folder = FileChooser.chooseFile(descriptor, project, startFolder())?.toNioPathOrNull() ?: return null
        if (!current()) return null
        return try {
            val replaceYes = { existing: Path ->
                Messages.showYesNoDialog(project, "$existing already exists. Replace it?", "Save Artifact", "Replace", "Cancel", Messages.getWarningIcon()) == Messages.YES
            }
            artifactSaveTarget(folder, artifactPath, replaceYes)?.takeIf { current() }?.let { SaveChoice(it, folder) }
        } catch (error: Exception) {
            state.text = "Cannot save $artifactPath: ${error.message ?: error.javaClass.simpleName}"
            null
        }
    }

    private fun chooseFileTarget(name: String): Path? {
        if (!idle()) return null
        return FileChooserFactory.getInstance()
            .createSaveFileDialog(FileSaverDescriptor("Save artifact", "Save generated DATAMIMIC artifact", *emptyArray()), project)
            .save(Path.of(project.basePath ?: System.getProperty("user.home")), name)
            ?.file
            ?.toPath()
            ?.takeIf { current() }
    }

    private fun transferTo(choice: SaveChoice, name: String, downloadAction: (java.io.OutputStream, () -> Boolean) -> PlatformSessionFence) {
        val target = choice.target
        downloading = true
        state.text = "Downloading $name…"
        updateActions()
        transfer = scope.launch {
            try {
                val result = withTimeout(DOWNLOAD_TIMEOUT.toMillis()) {
                    val timeoutJob = currentCoroutineContext().job
                    runInterruptible(Dispatchers.IO) {
                        downloadAtomically(
                            target,
                            { timeoutJob.isActive && !disposed && scope.isActive && !project.isDisposed && current() },
                            ::hasUnsavedEdits,
                            choice.confinedTo,
                            downloadAction,
                        )
                        if (FileTypeManager.getInstance().getFileTypeByFileName(target.fileName.toString()).isBinary) {
                            null
                        } else {
                            // WHY: a nested file can be in a folder that the IDE does not know yet.
                            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(target)
                        }
                    }
                }
                withContext(Dispatchers.EDT) {
                    if (!current() || project.isDisposed) return@withContext
                    state.text = "Saved ${target.fileName}."
                    if (result != null) FileEditorManager.getInstance(project).openFile(result, true)
                }
            } catch (_: TimeoutCancellationException) {
                withContext(NonCancellable + Dispatchers.EDT) {
                    if (current()) {
                        state.text = "Download timed out."
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                withContext(Dispatchers.EDT) {
                    if (current()) {
                        state.text = "Download failed: ${error.message ?: error.javaClass.simpleName}"
                    }
                }
            } finally {
                withContext(NonCancellable + Dispatchers.EDT) {
                    downloading = false
                    if (current()) updateActions()
                }
            }
        }
    }

    private fun updateActions() {
        download.isEnabled = !downloading && !loading && list.selectedValue != null
        downloadAll.isEnabled = !downloading && !loading && artifacts.size() > 0
        operate.isEnabled = !loading && list.selectedValue?.let { OperateFormat.of(it.path) } != null
        retry.isEnabled = !downloading && !loading
    }

    private fun hasUnsavedEdits(target: Path): Boolean {
        return LocalFileSystem.getInstance().findFileByNioFile(target)?.let(FileDocumentManager.getInstance()::isFileModified) ?: false
    }

    override fun dispose() {
        disposed = true
        transfer?.cancel()
        scope.cancel()
    }
}

/**
 * Publishes only a complete download; every earlier failure leaves an existing destination untouched. A nested artifact
 * can need subfolders. This function creates them, and removes the ones it created when the download fails.
 * With [confinedTo] it refuses a destination that resolves outside that folder, also through a symbolic link made after the user chose.
 */
internal fun downloadAtomically(
    target: Path,
    keepGoing: () -> Boolean,
    hasUnsavedEdits: (Path) -> Boolean,
    confinedTo: Path? = null,
    download: (java.io.OutputStream, () -> Boolean) -> PlatformSessionFence,
) {
    val destination = target.toAbsolutePath()
    val parent = checkNotNull(destination.parent)
    confinedTo?.let { requireInside(it, parent) }
    val created = generateSequence(parent) { it.parent }.takeWhile { !Files.exists(it) }.toList()
    val temp = Files.createTempFile(Files.createDirectories(parent), ".datamimic-", ".tmp")
    var published = false
    try {
        val fence = Files.newOutputStream(temp).use { output -> download(output, keepGoing) }
        if (!keepGoing()) throw CancellationException("Artifact download cancelled.")
        val publish: () -> Unit = {
            if (!keepGoing()) throw CancellationException("Artifact download cancelled.")
            if (hasUnsavedEdits(destination)) throw IOException("The destination has unsaved IDE edits.")
            if (!keepGoing()) throw CancellationException("Artifact download cancelled.")
            Files.move(temp, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }
        if (ApplicationManager.getApplication() == null) {
            fence.publish(publish)
        } else {
            WriteAction.runAndWait<Exception> { fence.publish(publish) }
        }
        published = true
    } finally {
        Files.deleteIfExists(temp)
        // The nearest folder is first, so a folder is empty when the function reaches it. A folder with content stays.
        if (!published) created.forEach { runCatching { Files.deleteIfExists(it) } }
    }
}
