// DATAMIMIC for JetBrains IDEs
// Copyright (c) 2026 Rapiddweller Asia Co., Ltd.
// SPDX-License-Identifier: MIT

package com.rapiddweller.datamimic.ide.generation

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.execution.ui.ExecutionConsole
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBFont
import com.rapiddweller.datamimic.core.generation.GenerationRun
import com.rapiddweller.datamimic.core.generation.GenerationSnapshot
import com.rapiddweller.datamimic.core.generation.GenerationTask
import com.rapiddweller.datamimic.core.generation.PreviewContent
import com.rapiddweller.datamimic.core.generation.TaskStatus
import com.rapiddweller.datamimic.core.workspace.FolderIdentity
import com.rapiddweller.datamimic.ide.AuthState
import com.rapiddweller.datamimic.ide.DatamimicPlatform
import com.rapiddweller.datamimic.ide.ToolWindowScope
import com.rapiddweller.datamimic.ide.activeProject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Point
import java.awt.event.HierarchyEvent
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSplitPane
import javax.swing.SwingUtilities
import javax.swing.DefaultListModel
import javax.swing.table.DefaultTableModel

internal const val GENERATION_TOOL_WINDOW_ID = "DATAMIMIC Generation"

internal fun updateOutput(scroll: JBScrollPane, area: JBTextArea, value: String) {
    if (area.text == value) return
    val bar = scroll.verticalScrollBar
    val atEnd = bar.value + bar.visibleAmount >= bar.maximum
    val position = Point(scroll.viewport.viewPosition)
    val start = area.selectionStart.coerceAtMost(value.length)
    val end = area.selectionEnd.coerceAtMost(value.length)
    area.text = value
    area.select(start, end)
    scroll.viewport.viewSize = area.preferredSize
    SwingUtilities.invokeLater {
        if (atEnd) {
            scroll.verticalScrollBar.value = scroll.verticalScrollBar.maximum
        } else {
            val maximum = (scroll.verticalScrollBar.maximum - scroll.verticalScrollBar.visibleAmount).coerceAtLeast(0)
            scroll.viewport.viewPosition = Point(position.x, position.y.coerceAtMost(maximum))
        }
    }
}

/** Shows one fixed, Platform-backed task history in the bottom tool window. */
internal object RunResults {
    fun show(project: Project, identity: FolderIdentity, run: GenerationRun, parentScope: CoroutineScope) {
        val manager = ToolWindowManager.getInstance(project)
        manager.invokeLater {
            if (project.isDisposed) return@invokeLater
            val toolWindow = manager.getToolWindow(GENERATION_TOOL_WINDOW_ID)
            if (toolWindow == null) {
                run.close()
                return@invokeLater
            }
            val view = tasksView(project, toolWindow, parentScope)
            toolWindow.contentManager.setSelectedContent(toolWindow.contentManager.contents.first { it.component === view })
            toolWindow.activate(null)
            view.show(identity, run)
        }
    }

    /** Updates task history without taking focus from the native Run console. */
    fun select(project: Project, identity: FolderIdentity, taskId: String, parentScope: CoroutineScope) {
        ToolWindowManager.getInstance(project).invokeLater {
            if (project.isDisposed) return@invokeLater
            val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(GENERATION_TOOL_WINDOW_ID) ?: return@invokeLater
            tasksView(project, toolWindow, parentScope).show(identity, taskId)
        }
    }

    private fun tasksView(project: Project, toolWindow: ToolWindow, parentScope: CoroutineScope): GenerationTasksView {
        val content = toolWindow.contentManager.contents.firstOrNull { it.displayName == "Tasks" }
        return when (val component = content?.component) {
            is GenerationTasksView -> component
            else -> GenerationTasksView(project, parentScope).also { tasks ->
                ContentFactory.getInstance().createContent(tasks, "Tasks", false).apply {
                    isCloseable = false
                    setDisposer(tasks)
                }.also(toolWindow.contentManager::addContent)
            }
        }
    }
}

class GenerationTasksToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun shouldBeAvailable(project: Project): Boolean = project.activeProject().identity != null

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val view = GenerationTasksView(project, project.getService(ToolWindowScope::class.java).scope)
        toolWindow.contentManager.addContent(ContentFactory.getInstance().createContent(view, "Tasks", false).apply {
            isCloseable = false
            setDisposer(view)
        })
        project.activeProject().identity?.let(view::show)
    }
}

private data class GenerationBinding(val identity: FolderIdentity, val auth: AuthState.SignedIn)

private class GenerationTasksView(private val project: Project, parentScope: CoroutineScope) : JPanel(BorderLayout()), Disposable {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext.job))
    private val tasks = DefaultListModel<GenerationTask>()
    private val list = JList(tasks)
    private val taskState = JLabel("No task selected.")
    private val previous = JButton("Previous")
    private val next = JButton("Next")
    private val refresh = JButton("Refresh")
    private val details = JPanel(BorderLayout())
    private var requestedIdentity: FolderIdentity? = null
    @Volatile
    private var binding: GenerationBinding? = null
    private var page = 1
    @Volatile
    private var selectedTaskId: String? = null
    private var historyRequest = 0L
    @Volatile
    private var selectionRequest = 0L
    private var historyJob: Job? = null
    private var detail: RunResultView? = null

    init {
        val controls = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(previous)
            add(next)
            add(refresh)
        }
        val taskList = JPanel(BorderLayout()).apply {
            add(taskState, BorderLayout.NORTH)
            add(JBScrollPane(list), BorderLayout.CENTER)
            add(controls, BorderLayout.SOUTH)
        }
        details.add(JLabel("Select a task."), BorderLayout.CENTER)
        add(JSplitPane(JSplitPane.HORIZONTAL_SPLIT, taskList, details).apply { resizeWeight = 0.35 }, BorderLayout.CENTER)
        list.addListSelectionListener { event ->
            if (!event.valueIsAdjusting) list.selectedValue?.let(::select)
        }
        addHierarchyListener { event ->
            if (event.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L && isShowing) binding?.let { load(it, page) }
        }
        previous.addActionListener { binding?.let { load(it, page - 1) } }
        next.addActionListener { binding?.let { load(it, page + 1) } }
        refresh.addActionListener { binding?.let { load(it, page) } }
        scope.launch(Dispatchers.EDT) {
            DatamimicPlatform.getInstance().state.collect { state ->
                when (state) {
                    is AuthState.SignedIn -> bind(state)
                    AuthState.Unknown, is AuthState.SignedOut -> clear("Tasks unavailable. Sign in to the active project.")
                }
            }
        }
        updatePaging(1, 0)
    }

    fun show(identity: FolderIdentity, run: GenerationRun) {
        requestedIdentity = identity
        val state = DatamimicPlatform.getInstance().state.value
        if (state is AuthState.SignedIn && state.origin == identity.origin) {
            bind(state)
            binding?.let { select(it, run.taskId, run) }
        } else {
            clear("Tasks unavailable. Sign in to the active project.")
        }
    }

    fun show(identity: FolderIdentity) {
        requestedIdentity = identity
        when (val state = DatamimicPlatform.getInstance().state.value) {
            is AuthState.SignedIn -> if (state.origin == identity.origin) bind(state) else clear("Tasks unavailable. Sign in to the active project.")
            AuthState.Unknown, is AuthState.SignedOut -> clear("Tasks unavailable. Sign in to the active project.")
        }
    }

    fun show(identity: FolderIdentity, taskId: String) {
        requestedIdentity = identity
        val state = DatamimicPlatform.getInstance().state.value
        if (state is AuthState.SignedIn && state.origin == identity.origin) {
            bind(state)
            binding?.let { select(it, taskId, null) }
        } else {
            clear("Tasks unavailable. Sign in to the active project.")
        }
    }

    private fun bind(auth: AuthState.SignedIn) {
        val identity = requestedIdentity ?: return
        if (auth.origin != identity.origin) {
            clear("Tasks unavailable. Sign in to the active project.")
            return
        }
        val target = GenerationBinding(identity, auth)
        if (binding == target) return
        clear("Loading tasks…")
        binding = target
        page = 1
        load(target, page)
    }

    private fun select(task: GenerationTask) {
        val target = binding ?: return
        select(target, task.taskId, null)
    }

    private fun select(target: GenerationBinding, taskId: String, suppliedRun: GenerationRun?) {
        if (binding != target || selectedTaskId == taskId && detail != null) return
        selectedTaskId = taskId
        val row = (0 until tasks.size()).firstOrNull { tasks.getElementAt(it).taskId == taskId }
        if (row == null) list.clearSelection() else list.selectedIndex = row
        val request = ++selectionRequest
        detail?.dispose()
        val run = suppliedRun ?: GenerationRun(
            DatamimicPlatform.getInstance().generation.boundTo(target.identity.origin),
            target.identity.projectId,
            taskId,
        )
        val view = RunResultView(
            target.identity.projectName,
            run,
            scope,
            null,
            { load(target, page) },
            ::clearSelection,
            refreshWhenVisible = true,
            current = { binding == target && authAllows(target) && selectedTaskId == taskId && selectionRequest == request },
            showElapsed = false,
            artifactProject = project,
        )
        detail = view
        details.removeAll()
        details.add(view, BorderLayout.CENTER)
        details.revalidate()
        details.repaint()
        view.start()
    }

    private fun clearSelection() {
        selectedTaskId = null
        ++selectionRequest
        detail?.dispose()
        detail = null
        list.clearSelection()
        details.removeAll()
        details.add(JLabel("Select a task."), BorderLayout.CENTER)
        details.revalidate()
        details.repaint()
    }

    private fun clear(message: String) {
        binding = null
        ++historyRequest
        ++selectionRequest
        historyJob?.cancel()
        detail?.dispose()
        detail = null
        selectedTaskId = null
        tasks.removeAllElements()
        list.clearSelection()
        taskState.text = message
        refresh.isEnabled = false
        updatePaging(1, 0)
        details.removeAll()
        details.add(JLabel(message), BorderLayout.CENTER)
        details.revalidate()
        details.repaint()
    }

    private fun load(target: GenerationBinding, requestedPage: Int) {
        val request = ++historyRequest
        historyJob?.cancel()
        taskState.text = "Loading tasks…"
        refresh.isEnabled = false
        updatePaging(requestedPage, 0)
        historyJob = scope.launch(Dispatchers.EDT) {
            try {
                val result = withContext(Dispatchers.IO) {
                    DatamimicPlatform.getInstance().generation.boundTo(target.identity.origin).history(target.identity.projectId, requestedPage)
                }
                if (binding != target || !authAllows(target) || historyRequest != request) return@launch
                page = result.page
                tasks.removeAllElements()
                result.tasks.forEach(tasks::addElement)
                taskState.text = if (result.tasks.isEmpty()) "No generation tasks." else "Tasks · page ${result.page} of ${result.totalPages}"
                updatePaging(result.page, result.totalPages)
                selectedTaskId?.let { selected ->
                    val index = result.tasks.indexOfFirst { it.taskId == selected }
                    if (index >= 0) list.selectedIndex = index
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (binding != target || !authAllows(target) || historyRequest != request) return@launch
                tasks.removeAllElements()
                taskState.text = "Tasks unavailable: ${e.message ?: e.javaClass.simpleName}"
                updatePaging(requestedPage, 0)
            } finally {
                if (binding == target && authAllows(target) && historyRequest == request) refresh.isEnabled = true
            }
        }
    }

    private fun updatePaging(currentPage: Int, totalPages: Int) {
        previous.isEnabled = currentPage > 1
        next.isEnabled = totalPages > currentPage
    }

    private fun authAllows(target: GenerationBinding): Boolean = DatamimicPlatform.getInstance().state.value == target.auth

    override fun dispose() {
        historyJob?.cancel()
        detail?.dispose()
        scope.cancel()
    }
}

private class RunResultView(
    projectName: String,
    private val run: GenerationRun,
    parentScope: CoroutineScope,
    private val nativeStop: (() -> Unit)?,
    private val completed: (TaskStatus) -> Unit,
    private val closeView: () -> Unit,
    private val refreshWhenVisible: Boolean = false,
    private val current: () -> Boolean = { true },
    private val showElapsed: Boolean = true,
    private val artifactProject: Project? = null,
) : JPanel(BorderLayout()), Disposable {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext.job))
    private val task = JLabel("Task ID: ${run.taskId}")
    private val state = JLabel("Status: observing · Elapsed: 0s")
    private val stop = JButton("Stop Server Run")
    private val retry = JButton("Retry")
    private val close = JButton("Close View")
    private val tabs = JBTabbedPane()
    private val log = output()
    private val errorOutput = output()
    private val logScroll = JBScrollPane(log)
    private val errorScroll = JBScrollPane(errorOutput)
    private val previewTabs = mutableSetOf<String>()
    private var artifactBrowser: ArtifactBrowser? = null
    private var observation: Job? = null
    private var actionError: Throwable? = null
    private var stopping = false
    private var stopRequested = false
    private var completionReported = false
    // UNKNOWN/no snapshot is active, like GenerationSnapshot.active.
    private var active = true

    init {
        val header = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(JLabel("$projectName generation"))
            add(task)
            add(state)
            add(stop)
            add(retry)
            add(close)
        }
        add(header, BorderLayout.NORTH)
        tabs.addTab("Log", logScroll)
        tabs.addTab("Errors", errorScroll)
        tabs.addChangeListener { if (tabs.selectedComponent === artifactBrowser) artifactBrowser?.load() }
        add(tabs, BorderLayout.CENTER)
        stop.addActionListener { stopServerRun() }
        retry.addActionListener {
            actionError = null
            observe()
        }
        close.addActionListener { closeView() }
    }

    fun start() = observe()

    private fun stopServerRun() {
        if (stopping || !current()) return
        stopping = true
        stopRequested = nativeStop != null
        stop.isEnabled = false
        retry.isEnabled = false
        nativeStop?.let { it(); return }
        observation?.cancel()
        scope.launch {
            val error = runCatching { withContext(Dispatchers.IO) { run.stop() } }.exceptionOrNull()
            withContext(Dispatchers.EDT) {
                stopping = false
                actionError = error
                retry.isEnabled = true
                observe()
            }
        }
    }

    private fun observe() {
        if (stopping || !current()) return
        observation?.cancel()
        observation = scope.launch {
            do {
                if (refreshWhenVisible && !withContext(Dispatchers.EDT) { isShowing }) {
                    delay(REFRESH_INTERVAL_MS)
                    continue
                }
                val snapshot = withContext(Dispatchers.IO) { run.refresh() }
                val stillCurrent = withContext(Dispatchers.EDT) { current().also { if (it) render(snapshot) } }
                if (!stillCurrent) return@launch
                if (!snapshot.needsRefresh) return@launch
                delay(REFRESH_INTERVAL_MS)
            } while (true)
        }
    }

    private fun render(snapshot: GenerationSnapshot) {
        if (!current()) return
        active = snapshot.active
        state.text = "Status: ${snapshot.status ?: "unavailable"}" + if (showElapsed) " · Elapsed: ${snapshot.elapsedMillis / 1_000}s" else ""
        stop.isEnabled = snapshot.active && !stopping && !stopRequested
        retry.isEnabled = !stopping
        updateOutput(logScroll, log, snapshot.log.ifBlank { "No log output." })
        updateOutput(errorScroll, errorOutput, errors(snapshot).ifBlank { "No errors." })
        snapshot.previews.filter { previewTabs.add(it.name) }.forEach { tabs.addTab("Preview sample: ${it.name}", component(it)) }
        if (snapshot.previewsLoaded && snapshot.previews.isEmpty() && previewTabs.add("Preview unavailable")) {
            tabs.addTab("Preview sample", text("Preview unavailable."))
        }
        if (snapshot.status?.succeeded == true && artifactBrowser == null && artifactProject != null) {
            ArtifactBrowser(artifactProject, run, scope, current).also {
                artifactBrowser = it
                tabs.addTab("Artifacts", it)
            }
        }
        if (snapshot.status?.succeeded != true) {
            artifactBrowser?.let {
                tabs.remove(it)
                it.dispose()
                artifactBrowser = null
            }
        }
        if (snapshot.status?.terminal == true && !completionReported) {
            completionReported = true
            completed(checkNotNull(snapshot.status))
        }
    }

    fun stopFailed(error: Throwable) {
        actionError = error
        stopping = false
        stopRequested = false
        stop.isEnabled = active
        retry.isEnabled = true
        updateOutput(errorScroll, errorOutput, "Stop Server Run failed: ${error.message ?: error.javaClass.simpleName}")
    }

    private fun errors(snapshot: GenerationSnapshot): String = listOfNotNull(
        snapshot.statusError?.let { "Status refresh failed: ${it.message ?: it.javaClass.simpleName}" },
        snapshot.logError?.let { "Log refresh failed: ${it.message ?: it.javaClass.simpleName}" },
        snapshot.previewError?.let { "Preview refresh failed: ${it.message ?: it.javaClass.simpleName}" },
        actionError?.let { "Stop Server Run failed: ${it.message ?: it.javaClass.simpleName}" },
    ).joinToString("\n\n")

    override fun dispose() {
        run.close()
        observation?.cancel()
        artifactBrowser?.dispose()
        scope.cancel()
    }

    private fun component(preview: PreviewContent): JComponent = when (preview) {
        is PreviewContent.Table -> JBScrollPane(
            JBTable(
                object : DefaultTableModel(preview.rows.map { it.toTypedArray<Any>() }.toTypedArray(), preview.columns.toTypedArray<Any>()) {
                    override fun isCellEditable(row: Int, column: Int) = false
                },
            ),
        )
        is PreviewContent.Text -> text(preview.text)
    }

    private fun output(): JBTextArea = JBTextArea().apply {
        isEditable = false
        font = JBFont.create(Font(Font.MONOSPACED, Font.PLAIN, font.size))
    }

    private fun text(value: String): JComponent = JBScrollPane(output().apply { text = value })

    private companion object {
        const val REFRESH_INTERVAL_MS = 3_000L
    }
}

/** On-demand artifact listing and downloads for one successful task. */
private class ArtifactBrowser(
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

    private fun save(name: String, downloadAction: (java.io.OutputStream, () -> Boolean) -> Unit) {
        if (!current() || project.isDisposed || transfer?.isActive == true) return
        val target = FileChooserFactory.getInstance()
            .createSaveFileDialog(FileSaverDescriptor("Save artifact", "Save generated DATAMIMIC artifact"), project)
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
                val result = runInterruptible(Dispatchers.IO) {
                    downloadAtomically(
                        target,
                        { !disposed && scope.isActive && !project.isDisposed && current() },
                        ::hasUnsavedEdits,
                        downloadAction,
                    )
                    if (FileTypeManager.getInstance().getFileTypeByFileName(target.fileName.toString()).isBinary) {
                        null
                    } else {
                        LocalFileSystem.getInstance().run {
                            refreshNioFiles(listOf(target), true, false, null)
                            findFileByNioFile(target)
                        }
                    }
                }
                withContext(Dispatchers.EDT) {
                    if (!current() || project.isDisposed) return@withContext
                    state.text = "Saved ${target.fileName}."
                    if (result != null) FileEditorManager.getInstance(project).openFile(result, true)
                }
            } catch (error: CancellationException) {
                withContext(Dispatchers.EDT) {
                    if (current()) {
                        state.text = "Download cancelled."
                    }
                }
                throw error
            } catch (error: Exception) {
                withContext(Dispatchers.EDT) {
                    if (current()) {
                        state.text = "Download failed: ${error.message ?: error.javaClass.simpleName}"
                    }
                }
            } finally {
                withContext(Dispatchers.EDT) {
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
        return ReadAction.compute<Boolean, RuntimeException> {
            LocalFileSystem.getInstance().findFileByNioFile(target)?.let(FileDocumentManager.getInstance()::isFileModified) ?: false
        }
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
    download: (java.io.OutputStream, () -> Boolean) -> Unit,
) {
    val temp = Files.createTempFile(checkNotNull(target.toAbsolutePath().parent), ".datamimic-", ".tmp")
    try {
        Files.newOutputStream(temp).use { output -> download(output, keepGoing) }
        if (!keepGoing()) throw CancellationException("Artifact download cancelled.")
        if (hasUnsavedEdits(target)) throw IOException("The destination has unsaved IDE edits.")
        if (!keepGoing()) throw CancellationException("Artifact download cancelled.")
        Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } finally {
        Files.deleteIfExists(temp)
    }
}

/** The Run-tool-window console for a native configuration; disposing it only detaches local observation. */
internal class NativeGenerationConsole(
    private val project: Project,
    private val projectName: String,
    private val scope: CoroutineScope,
    private val handler: ServerGenerationProcessHandler,
) : JPanel(BorderLayout()), ExecutionConsole {
    private var detached = false
    private var view: RunResultView? = null
    private var pendingStopError: Throwable? = null

    init {
        add(JLabel("Starting $projectName generation…"), BorderLayout.NORTH)
    }

    fun attach(run: GenerationRun): Boolean {
        if (detached || project.isDisposed) {
            run.close()
            dispose()
            return false
        }
        val result = RunResultView(projectName, run, scope, handler::requestStop, handler::completed, ::dispose)
        view = result
        removeAll()
        add(result, BorderLayout.CENTER)
        pendingStopError?.let(result::stopFailed)
        revalidate()
        repaint()
        result.start()
        return true
    }

    fun stopFailed(error: Throwable) {
        ApplicationManager.getApplication().invokeLater {
            val result = view
            if (result == null) pendingStopError = error else result.stopFailed(error)
        }
    }

    fun detach() {
        if (detached) return
        detached = true
        view?.dispose()
        view = null
    }

    override fun getComponent(): JComponent = this

    override fun getPreferredFocusableComponent(): JComponent = view ?: this

    override fun dispose() {
        if (!detached) handler.detachObservation()
        detach()
    }
}
