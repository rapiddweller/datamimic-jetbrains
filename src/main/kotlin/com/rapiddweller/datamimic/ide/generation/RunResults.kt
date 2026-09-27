// DATAMIMIC for JetBrains IDEs
// Copyright (c) 2026 Rapiddweller Asia Co., Ltd.
// SPDX-License-Identifier: MIT

package com.rapiddweller.datamimic.ide.generation

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
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
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.table.DefaultTableModel

internal const val GENERATION_TOOL_WINDOW_ID = "DATAMIMIC Generation"

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
            val view = RunResultView(projectName, run, parentScope) {
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
    private val previewTabs = mutableSetOf<String>()
    private var observation: Job? = null
    private var actionError: Throwable? = null
    private var stopping = false

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
        tabs.addTab("Log", JBScrollPane(log))
        tabs.addTab("Errors", JBScrollPane(errorOutput))
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
        stop.isEnabled = false
        retry.isEnabled = false
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
        state.text = "Status: ${snapshot.status ?: "unavailable"} · Elapsed: ${snapshot.elapsedMillis / 1_000}s"
        stop.isEnabled = snapshot.active && !stopping
        retry.isEnabled = !stopping
        update(log, snapshot.log.ifBlank { "No log output." })
        update(errorOutput, errors(snapshot).ifBlank { "No errors." })
        snapshot.previews.filter { previewTabs.add(it.name) }.forEach { tabs.addTab(it.name, component(it)) }
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

    private fun update(area: JBTextArea, value: String) {
        if (area.text == value) return
        val start = area.selectionStart.coerceAtMost(value.length)
        val end = area.selectionEnd.coerceAtMost(value.length)
        area.text = value
        area.select(start, end)
    }

    private companion object {
        const val REFRESH_INTERVAL_MS = 3_000L
    }
}
