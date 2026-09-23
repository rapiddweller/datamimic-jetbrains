// DATAMIMIC for JetBrains IDEs
// Copyright (c) 2026 Rapiddweller Asia Co., Ltd.
// SPDX-License-Identifier: MIT

package com.rapiddweller.datamimic.ide.generation

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBFont
import com.rapiddweller.datamimic.core.generation.PreviewContent
import com.rapiddweller.datamimic.core.generation.RunOutcome
import java.awt.Font
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import javax.swing.JComponent
import javax.swing.table.DefaultTableModel

/** The DATAMIMIC tool window id; plugin.xml registers the window under the same id. */
internal const val TOOL_WINDOW_ID = "DATAMIMIC"

/** Shows a finished (or still running) generation as a closeable tab: the task log and one tab per product preview. */
internal object RunResults {
    fun show(project: Project, projectName: String, outcome: RunOutcome, logs: String, previews: List<PreviewContent>) {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID) ?: return
        val tabs = JBTabbedPane()
        previews.forEach { tabs.addTab(it.name, component(it)) }
        tabs.addTab("Log", text(logs.ifBlank { outcome.message ?: "No log output." }))
        val title = "$projectName ${LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))} · ${outcome.status}"
        val content = ContentFactory.getInstance().createContent(tabs, title, false).apply { isCloseable = true }
        toolWindow.contentManager.addContent(content)
        toolWindow.contentManager.setSelectedContent(content)
        toolWindow.activate(null)
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
}
