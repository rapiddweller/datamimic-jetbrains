// DATAMIMIC for JetBrains IDEs
// Copyright (c) 2026 Rapiddweller Asia Co., Ltd.
// SPDX-License-Identifier: MIT

package com.rapiddweller.datamimic.ide.generation

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil.escapeXmlEntities
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.rapiddweller.datamimic.core.generation.GenerationRun
import com.rapiddweller.datamimic.core.generation.GenerationTask
import com.rapiddweller.datamimic.core.generation.TaskComment
import com.rapiddweller.datamimic.core.generation.TaskStatus
import com.rapiddweller.datamimic.core.workspace.FolderIdentity
import com.rapiddweller.datamimic.ide.AuthState
import com.rapiddweller.datamimic.ide.DatamimicPlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridLayout
import java.awt.event.HierarchyEvent
import javax.swing.JButton
import javax.swing.JList
import javax.swing.JLabel
import javax.swing.ListCellRenderer
import javax.swing.JPanel
import javax.swing.JSplitPane
import javax.swing.ScrollPaneConstants
import javax.swing.DefaultListModel

private data class GenerationBinding(val identity: FolderIdentity, val auth: AuthState.SignedIn)

private class GenerationTaskRenderer : JPanel(BorderLayout(JBUI.scale(12), 0)), ListCellRenderer<GenerationTask> {
    private val primary = JLabel()
    private val secondary = JLabel()
    // WHY: a long comment is cut with "…" and shows whole in the tooltip.
    private val comment = JLabel().apply {
        font = font.deriveFont(Font.ITALIC)
        // WHY: other project members write comments. A text that starts with <html> must not render.
        putClientProperty("html.disable", true)
    }

    init {
        border = JBUI.Borders.empty(4, 6)
        add(JPanel(GridLayout(2, 1)).apply {
            isOpaque = false
            add(primary)
            add(secondary)
        }, BorderLayout.WEST)
        add(comment, BorderLayout.CENTER)
    }

    override fun getListCellRendererComponent(
        list: JList<out GenerationTask>,
        value: GenerationTask,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean,
    ): Component {
        primary.text = listOfNotNull(value.status?.name, value.name).joinToString(" · ")
        primary.icon = when (value.status) {
            TaskStatus.SUCCESS -> AllIcons.RunConfigurations.TestPassed
            TaskStatus.SUCCESS_WITH_WARNING -> AllIcons.General.Warning
            TaskStatus.WORKER_FAILURE, TaskStatus.DISPATCH_FAILURE, TaskStatus.FINALIZATION_FAILURE -> AllIcons.RunConfigurations.TestFailed
            TaskStatus.CANCELLED, TaskStatus.DELETED -> AllIcons.RunConfigurations.TestTerminated
            else -> AllIcons.RunConfigurations.TestNotRan
        }
        secondary.text = listOfNotNull(value.queuedAt, value.taskId.take(8)).joinToString(" · ")
        // WHY: a comment can have several lines. The list cell shows the first line.
        comment.text = value.comment.text?.lineSequence()?.firstOrNull().orEmpty()
        toolTipText = value.comment.text
            ?.let { "<html><body style='width: ${JBUI.scale(320)}px'>${escapeXmlEntities(value.taskId)}<br><br>${escapeXmlEntities(it).replace("\n", "<br>")}</body></html>" }
            ?: value.taskId
        background = if (isSelected) list.selectionBackground else list.background
        primary.foreground = if (isSelected) list.selectionForeground else list.foreground
        secondary.foreground = if (isSelected) list.selectionForeground else UIUtil.getContextHelpForeground()
        comment.foreground = if (isSelected) list.selectionForeground else UIUtil.getContextHelpForeground()
        isOpaque = true
        return this
    }
}

internal class GenerationTasksView(private val project: Project, parentScope: CoroutineScope) : JPanel(BorderLayout()), Disposable {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext.job))
    private val tasks = DefaultListModel<GenerationTask>()
    private val list = JList(tasks).apply { cellRenderer = GenerationTaskRenderer() }
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
    // WHY: one editor for each binding. Its drafts end with the binding, and a save always goes to the project it started in.
    private var commentPanel: TaskCommentPanel? = null

    init {
        val controls = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(previous)
            add(next)
            add(refresh)
        }
        val taskList = JPanel(BorderLayout()).apply {
            add(taskState, BorderLayout.NORTH)
            add(JBScrollPane(list).apply { horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER }, BorderLayout.CENTER)
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
        commentPanel = TaskCommentPanel(
            scope,
            { taskId, text ->
                DatamimicPlatform.getInstance().generation.boundTo(target.identity.origin).updateComment(target.identity.projectId, taskId, text)
            },
            { taskId, comment ->
                if (binding == target) {
                    showComment(taskId, comment)
                    // WHY: a load that started before this save can still bring the old comment.
                    if (historyJob?.isActive == true) load(target, page)
                }
            },
        )
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
        commentPanel?.let {
            it.bind(row?.let(tasks::getElementAt))
            details.add(it, BorderLayout.SOUTH)
        }
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
        showDetails("Select a task.")
    }

    private fun clear(message: String) {
        binding = null
        ++historyRequest
        ++selectionRequest
        historyJob?.cancel()
        detail?.dispose()
        detail = null
        commentPanel = null
        selectedTaskId = null
        tasks.removeAllElements()
        list.clearSelection()
        taskState.text = message
        refresh.isEnabled = false
        updatePaging(1, 0)
        showDetails(message)
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
                    commentPanel?.bind(result.tasks.getOrNull(index))
                } ?: showDetails(if (result.tasks.isEmpty()) "No generation tasks." else "Select a task.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (binding != target || !authAllows(target) || historyRequest != request) return@launch
                tasks.removeAllElements()
                val message = "Tasks unavailable: ${e.message ?: e.javaClass.simpleName}"
                taskState.text = message
                showDetails(message)
                updatePaging(requestedPage, 0)
            } finally {
                if (binding == target && authAllows(target) && historyRequest == request) refresh.isEnabled = true
            }
        }
    }

    /** A saved comment replaces the one in its list row. The editor shows it already. */
    private fun showComment(taskId: String, comment: TaskComment) {
        val row = (0 until tasks.size()).firstOrNull { tasks.getElementAt(it).taskId == taskId } ?: return
        tasks.set(row, tasks.getElementAt(row).copy(comment = comment))
    }

    private fun updatePaging(currentPage: Int, totalPages: Int) {
        previous.isEnabled = currentPage > 1
        next.isEnabled = totalPages > currentPage
    }

    private fun showDetails(message: String) {
        details.removeAll()
        details.add(JLabel(message), BorderLayout.CENTER)
        details.revalidate()
        details.repaint()
    }

    private fun authAllows(target: GenerationBinding): Boolean = DatamimicPlatform.getInstance().state.value == target.auth

    override fun dispose() {
        historyJob?.cancel()
        detail?.dispose()
        scope.cancel()
    }
}
