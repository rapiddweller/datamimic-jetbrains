// DATAMIMIC for JetBrains IDEs
// Copyright (c) 2026 Rapiddweller Asia Co., Ltd.
// SPDX-License-Identifier: MIT

package com.rapiddweller.datamimic.ide.generation

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.execution.ui.ExecutionConsole
import com.intellij.openapi.wm.ToolWindowAnchor
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBFont
import com.rapiddweller.datamimic.core.generation.GenerationRun
import com.rapiddweller.datamimic.core.generation.GenerationSnapshot
import com.rapiddweller.datamimic.core.generation.PreviewContent
import com.rapiddweller.datamimic.core.generation.TaskStatus
import com.rapiddweller.datamimic.ide.ToolWindowScope
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
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingUtilities
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

/** Opens a closeable, bottom result view. Closing it only stops local observation. */
internal object RunResults {
    fun show(project: Project, projectName: String, run: GenerationRun, parentScope: CoroutineScope) {
        val manager = ToolWindowManager.getInstance(project)
        manager.invokeLater {
            if (project.isDisposed) return@invokeLater
            val toolWindow = manager.getToolWindow(GENERATION_TOOL_WINDOW_ID)
                // The documented builder compiles to an @Internal method in the 2024.2 runtime.
                ?: manager.registerToolWindow(
                    GENERATION_TOOL_WINDOW_ID,
                    true,
                    ToolWindowAnchor.BOTTOM,
                    project.getService(ToolWindowScope::class.java),
                    true,
                )
            lateinit var content: com.intellij.ui.content.Content
            val view = RunResultView(projectName, run, parentScope, null, {}) {
                toolWindow.contentManager.removeContent(content, true)
            }
            content = ContentFactory.getInstance().createContent(view, "$projectName · ${run.taskId.take(8)}", false).apply {
                isCloseable = true
                setDisposer(view)
            }
            toolWindow.contentManager.addContent(content)
            toolWindow.contentManager.setSelectedContent(content)
            toolWindow.activate(null)
            view.start()
        }
    }
}

private class RunResultView(
    projectName: String,
    private val run: GenerationRun,
    parentScope: CoroutineScope,
    private val nativeStop: (() -> Unit)?,
    private val completed: (TaskStatus) -> Unit,
    private val closeView: () -> Unit,
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
        if (stopping) return
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
        if (stopping) return
        observation?.cancel()
        observation = scope.launch {
            do {
                val snapshot = withContext(Dispatchers.IO) { run.refresh() }
                withContext(Dispatchers.EDT) { render(snapshot) }
                if (!snapshot.needsRefresh) return@launch
                delay(REFRESH_INTERVAL_MS)
            } while (true)
        }
    }

    private fun render(snapshot: GenerationSnapshot) {
        active = snapshot.active
        state.text = "Status: ${snapshot.status ?: "unavailable"} · Elapsed: ${snapshot.elapsedMillis / 1_000}s"
        stop.isEnabled = snapshot.active && !stopping && !stopRequested
        retry.isEnabled = !stopping
        updateOutput(logScroll, log, snapshot.log.ifBlank { "No log output." })
        updateOutput(errorScroll, errorOutput, errors(snapshot).ifBlank { "No errors." })
        snapshot.previews.filter { previewTabs.add(it.name) }.forEach { tabs.addTab(it.name, component(it)) }
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
