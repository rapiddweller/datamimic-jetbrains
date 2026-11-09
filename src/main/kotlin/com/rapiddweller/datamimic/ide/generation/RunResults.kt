// DATAMIMIC for JetBrains IDEs
// Copyright (c) 2026 Rapiddweller Asia Co., Ltd.
// SPDX-License-Identifier: MIT

package com.rapiddweller.datamimic.ide.generation

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.execution.ui.ExecutionConsole
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.rapiddweller.datamimic.core.generation.GenerationRun
import com.rapiddweller.datamimic.core.generation.GenerationSnapshot
import com.rapiddweller.datamimic.core.generation.PreviewContent
import com.rapiddweller.datamimic.core.generation.TaskStatus
import com.rapiddweller.datamimic.core.workspace.FolderIdentity
import com.rapiddweller.datamimic.ide.ToolWindowScope
import com.rapiddweller.datamimic.ide.activeProject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Point
import java.awt.datatransfer.StringSelection
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.DefaultComboBoxModel
import javax.swing.table.DefaultTableModel

internal const val GENERATION_TOOL_WINDOW_ID = "Generation Tasks"

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
                    icon = AllIcons.Actions.ListFiles
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
            icon = AllIcons.Actions.ListFiles
            isCloseable = false
            setDisposer(view)
        })
        project.activeProject().identity?.let(view::show)
    }
}

internal class RunResultView(
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
    private val task = JLabel("Task ${run.taskId.take(8)}").apply { toolTipText = run.taskId }
    private val state = JLabel("Status: observing · Elapsed: 0s")
    private val copyTask = JButton(AllIcons.Actions.Copy).apply { toolTipText = "Copy task ID" }
    private val stop = JButton("Stop", AllIcons.Actions.Cancel)
    private val retry = JButton("Retry", AllIcons.Actions.Refresh)
    private val close = JButton("Close", AllIcons.Actions.Close)
    private val tabs = JBTabbedPane()
    private val log = output()
    private val errorOutput = output()
    private val logScroll = JBScrollPane(log)
    private val errorScroll = JBScrollPane(errorOutput)
    private val previewSelector = JComboBox<String>().apply {
        isVisible = false
        maximumRowCount = 10
    }
    private val previewContent = JPanel(BorderLayout()).apply {
        add(text("Preview available after the task finishes."), BorderLayout.CENTER)
    }
    private val preview = JPanel(BorderLayout()).apply {
        add(previewSelector, BorderLayout.NORTH)
        add(previewContent, BorderLayout.CENTER)
    }
    private var previewNames = emptyList<String>()
    private var previewsByName = emptyMap<String, PreviewContent>()
    private var previewsRendered = false
    private var artifactBrowser: ArtifactBrowser? = null
    private var observation: Job? = null
    private var actionError: Throwable? = null
    private var stopping = false
    private var stopRequested = false
    private var completionReported = false
    // UNKNOWN/no snapshot is active, like GenerationSnapshot.active.
    private var active = true

    init {
        val identity = JPanel(FlowLayout(FlowLayout.LEFT, 6, 2)).apply {
            add(JLabel("$projectName generation"))
            add(task)
            add(copyTask)
            add(state)
        }
        val actions = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 2)).apply {
            add(stop)
            add(retry)
            add(close)
        }
        val header = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(2)
            add(identity, BorderLayout.CENTER)
            add(actions, BorderLayout.EAST)
        }
        add(header, BorderLayout.NORTH)
        tabs.addTab("Log", AllIcons.Nodes.Console, logScroll)
        tabs.addTab("Errors", AllIcons.General.Error, errorScroll)
        tabs.addTab("Preview", AllIcons.Actions.Preview, preview)
        tabs.addChangeListener { if (tabs.selectedComponent === artifactBrowser) artifactBrowser?.load() }
        add(tabs, BorderLayout.CENTER)
        copyTask.addActionListener { CopyPasteManager.getInstance().setContents(StringSelection(run.taskId)) }
        previewSelector.addActionListener { renderPreview(previewSelector.selectedItem as? String) }
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
        renderPreviews(snapshot.previews, snapshot.previewsLoaded)
        if (snapshot.status?.succeeded == true && artifactBrowser == null && artifactProject != null) {
            ArtifactBrowser(artifactProject, run, scope, current).also {
                artifactBrowser = it
                tabs.addTab("Artifacts", AllIcons.Nodes.Artifact, it)
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

    private fun renderPreviews(previews: List<PreviewContent>, loaded: Boolean) {
        if (!loaded) return
        val unique = previews.distinctBy(PreviewContent::name)
        val names = unique.map(PreviewContent::name)
        if (previewsRendered && names == previewNames) return
        val selected = previewSelector.selectedItem as? String
        previewsRendered = true
        previewNames = names
        previewsByName = unique.associateBy(PreviewContent::name)
        previewSelector.model = DefaultComboBoxModel(names.toTypedArray())
        previewSelector.isVisible = names.size > 1
        val target = selected?.takeIf(previewsByName::containsKey) ?: names.firstOrNull()
        if (target != null) previewSelector.selectedItem = target
        renderPreview(target)
    }

    private fun renderPreview(name: String?) {
        previewContent.removeAll()
        previewContent.add(name?.let(previewsByName::get)?.let(::component) ?: text("Preview unavailable."), BorderLayout.CENTER)
        previewContent.revalidate()
        previewContent.repaint()
    }

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
        val result = RunResultView(projectName, run, scope, handler::requestStop, handler::completed, ::dispose, artifactProject = project)
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
