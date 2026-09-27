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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
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
                ?: manager.registerToolWindow(GENERATION_TOOL_WINDOW_ID, true, ToolWindowAnchor.BOTTOM, project, true)
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
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob())
    private val task = JLabel("Task ID: ${run.taskId}")
    private val state = JLabel("Status: observing · Elapsed: 0s")
    private val stop = JButton("Stop Server Run")
    private val retry = JButton("Retry")
    private val close = JButton("Close View")
    private val tabs = JBTabbedPane()
    private var observation: Job? = null
    private var actionError: Throwable? = null

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
        add(tabs, BorderLayout.CENTER)
        stop.addActionListener { stopServerRun() }
        retry.addActionListener { observe() }
        close.addActionListener { closeView() }
    }

    fun start() = observe()

    private fun stopServerRun() {
        stop.isEnabled = false
        scope.launch {
            actionError = runCatching { withContext(Dispatchers.IO) { run.stop() } }.exceptionOrNull()
            observe()
        }
    }

    private fun observe() {
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
        stop.isEnabled = snapshot.active
        tabs.removeAll()
        tabs.addTab("Log", text(snapshot.log.ifBlank { "No log output." }))
        snapshot.previews.forEach { tabs.addTab(it.name, component(it)) }
        errors(snapshot).takeIf { it.isNotEmpty() }?.let { tabs.addTab("Errors", text(it)) }
    }

    private fun errors(snapshot: GenerationSnapshot): String = listOfNotNull(
        snapshot.statusError?.let { "Status refresh failed: ${it.message ?: it.javaClass.simpleName}" },
        snapshot.logError?.let { "Log refresh failed: ${it.message ?: it.javaClass.simpleName}" },
        snapshot.previewError?.let { "Preview refresh failed: ${it.message ?: it.javaClass.simpleName}" },
        actionError?.let { "Stop Server Run failed: ${it.message ?: it.javaClass.simpleName}" },
    ).joinToString("\n\n")

    override fun dispose() {
        run.close()
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

    private fun text(value: String): JComponent = JBScrollPane(
        JBTextArea(value).apply {
            isEditable = false
            font = JBFont.create(Font(Font.MONOSPACED, Font.PLAIN, font.size))
        },
    )

    private companion object {
        const val REFRESH_INTERVAL_MS = 3_000L
    }
}
