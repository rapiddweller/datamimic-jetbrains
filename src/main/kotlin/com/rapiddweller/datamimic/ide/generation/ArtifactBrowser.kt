// DATAMIMIC for JetBrains IDEs
// Copyright (c) 2026 Rapiddweller Asia Co., Ltd.
// SPDX-License-Identifier: MIT

package com.rapiddweller.datamimic.ide.generation

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.components.JBScrollPane
import com.rapiddweller.datamimic.core.DOWNLOAD_TIMEOUT
import com.rapiddweller.datamimic.core.PlatformSessionFence
import com.rapiddweller.datamimic.core.generation.GenerationRun
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
        }, BorderLayout.SOUTH)
        retry.addActionListener { load(force = true) }
        download.addActionListener { list.selectedValue?.let { save(it.entityName) { output, keepGoing -> run.downloadArtifact(it.entityName, output, keepGoing) } } }
        downloadAll.addActionListener { save("${run.taskId}_artifacts.zip") { output, keepGoing -> run.downloadArtifacts(output, keepGoing) } }
        list.addListSelectionListener { if (!it.valueIsAdjusting) updateActions() }
        updateActions()
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

    private fun save(name: String, downloadAction: (java.io.OutputStream, () -> Boolean) -> PlatformSessionFence) {
        if (!current() || project.isDisposed || transfer?.isActive == true) return
        val target = FileChooserFactory.getInstance()
            .createSaveFileDialog(FileSaverDescriptor("Save artifact", "Save generated DATAMIMIC artifact", *emptyArray()), project)
            .save(Path.of(project.basePath ?: System.getProperty("user.home")), name)
            ?.file
            ?.toPath()
            ?: return
        if (!current()) return
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
                            downloadAction,
                        )
                        if (FileTypeManager.getInstance().getFileTypeByFileName(target.fileName.toString()).isBinary) {
                            null
                        } else {
                            LocalFileSystem.getInstance().run {
                                refreshNioFiles(listOf(target), false, false, null)
                                findFileByNioFile(target)
                            }
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

/** Publishes only a complete download; every earlier failure leaves an existing destination untouched. */
internal fun downloadAtomically(
    target: Path,
    keepGoing: () -> Boolean,
    hasUnsavedEdits: (Path) -> Boolean,
    download: (java.io.OutputStream, () -> Boolean) -> PlatformSessionFence,
) {
    val destination = target.toAbsolutePath()
    val temp = Files.createTempFile(checkNotNull(destination.parent), ".datamimic-", ".tmp")
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
    } finally {
        Files.deleteIfExists(temp)
    }
}
