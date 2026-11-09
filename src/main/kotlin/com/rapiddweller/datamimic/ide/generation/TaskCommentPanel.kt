// DATAMIMIC for JetBrains IDEs
// Copyright (c) 2026 Rapiddweller Asia Co., Ltd.
// SPDX-License-Identifier: MIT

package com.rapiddweller.datamimic.ide.generation

import com.intellij.openapi.application.EDT
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.rapiddweller.datamimic.core.generation.GenerationTask
import com.rapiddweller.datamimic.core.generation.MAX_COMMENT_LENGTH
import com.rapiddweller.datamimic.core.generation.TaskComment
import com.rapiddweller.datamimic.core.parsePlatformInstant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.event.DocumentEvent

/**
 * Edits the comment of the selected task. A draft stays when the save fails, when the task list reloads, and when the
 * user selects another task and comes back. [save] runs off the UI thread. [saved] runs on the UI thread after a save.
 */
internal class TaskCommentPanel(
    private val scope: CoroutineScope,
    private val save: (taskId: String, text: String) -> TaskComment,
    private val saved: (taskId: String, comment: TaskComment) -> Unit,
) : JPanel(BorderLayout()) {
    private class Draft(val text: String, val error: String)

    internal val area = JBTextArea(3, 40).apply {
        lineWrap = true
        wrapStyleWord = true
    }
    internal val count = JLabel()
    internal val info = JLabel().apply { foreground = UIUtil.getContextHelpForeground() }
    internal val error = JLabel().apply { foreground = JBColor.RED }
    internal val saveButton = JButton("Save")
    internal val cancelButton = JButton("Cancel")
    private val drafts = HashMap<String, Draft>()
    private var taskId: String? = null
    private var comment = TaskComment.NONE
    private val savingIds = HashSet<String>()
    private val saving get() = taskId in savingIds

    init {
        border = JBUI.Borders.empty(4)
        val header = JPanel(BorderLayout()).apply {
            add(JLabel("Comment").apply { font = font.deriveFont(java.awt.Font.BOLD) }, BorderLayout.WEST)
            add(count, BorderLayout.EAST)
        }
        val messages = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(info)
            add(error)
        }
        val footer = JPanel(BorderLayout()).apply {
            add(messages, BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply {
                add(saveButton)
                add(cancelButton)
            }, BorderLayout.EAST)
        }
        add(header, BorderLayout.NORTH)
        add(JBScrollPane(area), BorderLayout.CENTER)
        add(footer, BorderLayout.SOUTH)
        area.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = refresh()
        })
        saveButton.addActionListener { submit() }
        cancelButton.addActionListener { cancel() }
        refresh()
    }

    /** Shows [task]. A draft of this task stays. A task that is not in the list (null) switches the editor off. */
    fun bind(task: GenerationTask?) {
        rememberDraft()
        taskId = task?.taskId
        comment = task?.comment ?: TaskComment.NONE
        val draft = taskId?.let(drafts::get)
        setText(draft?.text ?: comment.text.orEmpty())
        error.text = draft?.error.orEmpty()
        refresh()
    }

    private fun submit() {
        val id = taskId ?: return
        val text = area.text.trim()
        savingIds += id
        error.text = ""
        refresh()
        scope.launch(Dispatchers.EDT) {
            val result = try {
                withContext(Dispatchers.IO) { save(id, text) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                savingIds -= id
                val message = "The comment is not saved: ${e.message ?: e.javaClass.simpleName}"
                if (taskId == id) error.text = message else drafts[id] = Draft(text, message)
                refresh()
                return@launch
            }
            savingIds -= id
            drafts.remove(id)
            saved(id, result)
            if (taskId == id) {
                comment = result
                setText(result.text.orEmpty())
                error.text = ""
            }
            refresh()
        }
    }

    private fun cancel() {
        val id = taskId ?: return
        drafts.remove(id)
        setText(comment.text.orEmpty())
        error.text = ""
        refresh()
    }

    private fun rememberDraft() {
        val id = taskId ?: return
        if (changed()) drafts[id] = Draft(area.text, error.text) else drafts.remove(id)
    }

    // WHY: do not replace an equal text, because that moves the caret of the user.
    private fun setText(value: String) {
        if (area.text != value) area.text = value
    }

    private fun changed() = taskId != null && area.text.trim() != comment.text.orEmpty()

    private fun length(): Int = area.text.trim().let { it.codePointCount(0, it.length) }

    private fun refresh() {
        val bound = taskId != null
        val length = length()
        val changed = changed()
        area.isEnabled = bound
        area.isEditable = bound && !saving
        count.text = if (bound) "$length/$MAX_COMMENT_LENGTH" else ""
        count.foreground = if (length > MAX_COMMENT_LENGTH) JBColor.RED else UIUtil.getContextHelpForeground()
        info.text = when {
            !bound -> "The comment is available when the task is in the list."
            comment.updatedBy != null || comment.updatedAt != null ->
                "Last edited by ${comment.updatedBy ?: "an unknown user"}" + (comment.updatedAt?.let { " on ${formatPlatformTime(it)}" } ?: "")
            comment.text == null -> "No comment yet."
            else -> ""
        }
        error.isVisible = error.text.isNotBlank()
        error.toolTipText = error.text.takeIf(String::isNotBlank)
        saveButton.isEnabled = changed && !saving && length <= MAX_COMMENT_LENGTH
        cancelButton.isEnabled = changed && !saving
    }
}

/** A time of the platform in the time zone of the user. A text that is not a time stays as the platform sent it. */
internal fun formatPlatformTime(value: String, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String =
    runCatching { DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withLocale(locale).format(parsePlatformInstant(value).atZone(zone)) }
        .getOrDefault(value)
