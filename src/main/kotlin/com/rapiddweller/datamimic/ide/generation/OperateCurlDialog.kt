// DATAMIMIC for JetBrains IDEs
// Copyright (c) 2026 Rapiddweller Asia Co., Ltd.
// SPDX-License-Identifier: MIT

package com.rapiddweller.datamimic.ide.generation

import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.text.StringUtil.escapeXmlEntities
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.CollectionListModel
import com.intellij.ui.IdeBorderFactory
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.components.JBList
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.rapiddweller.datamimic.core.generation.NotMatchedAction
import com.rapiddweller.datamimic.core.generation.OperateFormat
import com.rapiddweller.datamimic.core.generation.OperateOperation
import com.rapiddweller.datamimic.core.generation.OperateRequest
import com.rapiddweller.datamimic.core.generation.OperateResponse
import com.rapiddweller.datamimic.core.generation.OperateRow
import com.rapiddweller.datamimic.core.generation.operateCommand
import com.rapiddweller.datamimic.core.workspace.FolderIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridLayout
import java.awt.datatransfer.StringSelection
import java.awt.event.ActionEvent
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.AbstractAction
import javax.swing.Action
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent

/** One row of the dialog. [operations] is the text of the editor: one operation for each line. */
internal class OperateRowEdit(var id: String, var operations: String = "") {
    override fun toString() = id.ifBlank { "(no ID)" }
}

/**
 * Builds a `curl` command for the Artifact Operate API of one artifact. The command holds the platform URL, the project
 * and the task, and reads the token from `$PROJECT_TOKEN`. The dialog never handles a token. **Run now** sends the call
 * through the session of the IDE, like every other call of the plugin, and shows the response. [runCall] runs off the UI thread.
 */
internal class OperateCurlDialog(
    private val project: Project,
    private val artifact: String,
    private val format: OperateFormat,
    selectedTask: String,
    private val identity: FolderIdentity,
    private val runCall: (task: String, body: String) -> OperateResponse,
) : DialogWrapper(project, true) {
    private val scope = CoroutineScope(SupervisorJob())
    internal val taskBox = JComboBox(DefaultComboBoxModel(arrayOf(selectedTask, LATEST).distinct().toTypedArray()))
    internal val actionBox = JComboBox(DefaultComboBoxModel(NotMatchedAction.entries.toTypedArray())).apply {
        renderer = SimpleListCellRenderer.create("") { it.wire }
        toolTipText = "warn: the response names an operation without effect. error: the call fails with 422."
    }
    internal val rows = CollectionListModel(OperateRowEdit("case-1"))
    internal val rowList = JBList(rows).apply { selectionMode = ListSelectionModel.SINGLE_SELECTION }
    internal val addRow = JButton("Add Row")
    internal val removeRow = JButton("Remove Row")
    internal val idField = JBTextField()
    internal val operationsArea = monospace(JBTextArea(5, 40))
    internal val operationBox = JComboBox(DefaultComboBoxModel(format.operations.toTypedArray())).apply {
        renderer = SimpleListCellRenderer.create("") { it.name }
    }
    internal val pathField = JBTextField(24).apply { emptyText.text = "${format.pathKind}, for example ${if (format == OperateFormat.XML) "//order/id" else "\$.customer.name"}" }
    internal val valueField = JBTextField(16).apply { emptyText.text = "Value, for example '2'" }
    internal val addOperation = JButton("Add Operation")
    internal val helperMessage = JBLabel().apply { foreground = JBColor.RED }
    internal val problems = JBLabel().apply { foreground = JBColor.RED }
    internal val curlPreview = monospace(JBTextArea(10, 60)).apply { isEditable = false }
    internal val bodyPreview = monospace(JBTextArea(10, 40)).apply { isEditable = false }
    internal val response = monospace(JBTextArea(8, 60)).apply { isEditable = false }
    private val responsePane = JBScrollPane(response).apply {
        border = IdeBorderFactory.createTitledBorder("Response")
        isVisible = false
    }
    internal val copyAction = action("Copy") { CopyPasteManager.getInstance().setContents(StringSelection(curlPreview.text)) }
    internal val saveAction = action("Save as request.json…") { saveBody() }
    internal val runAction = action("Run now") { runNow() }
    private var loading = false
    private var running = false

    init {
        title = "Build Operate curl"
        setOKButtonText("Close")
        init()
        rowList.cellRenderer = SimpleListCellRenderer.create("") { it.toString() }
        rowList.selectedIndex = 0
        loadRow()
        wire()
        refresh()
    }

    override fun createCenterPanel(): JComponent {
        val settings = FormBuilder.createFormBuilder()
            .addLabeledComponent("Artifact:", JBLabel(artifact).apply { putClientProperty("html.disable", true) })
            .addLabeledComponent("Task:", taskBox)
            .addLabeledComponent("If an operation matches nothing:", actionBox)
            .panel
        val rowButtons = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
            add(addRow)
            add(removeRow)
        }
        val rowChooser = JPanel(BorderLayout()).apply {
            border = IdeBorderFactory.createTitledBorder("Rows")
            add(JBScrollPane(rowList), BorderLayout.CENTER)
            add(rowButtons, BorderLayout.SOUTH)
            preferredSize = Dimension(JBUI.scale(190), JBUI.scale(200))
        }
        val helper = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
            add(operationBox)
            add(pathField)
            add(valueField)
            add(addOperation)
        }
        val rowDetail = JPanel(BorderLayout()).apply {
            border = IdeBorderFactory.createTitledBorder("Row")
            add(FormBuilder.createFormBuilder().addLabeledComponent("ID:", idField).panel, BorderLayout.NORTH)
            add(JBScrollPane(operationsArea).apply { border = IdeBorderFactory.createTitledBorder("Operations, one on each line") }, BorderLayout.CENTER)
            add(
                FormBuilder.createFormBuilder()
                    .addComponent(helper)
                    .addComponent(helperMessage)
                    .addComponent(JBLabel(pathRules()).apply { foreground = JBColor.GRAY })
                    .panel,
                BorderLayout.SOUTH,
            )
        }
        val editor = JPanel(BorderLayout()).apply {
            add(rowChooser, BorderLayout.WEST)
            add(rowDetail, BorderLayout.CENTER)
        }
        val previews = JPanel(GridLayout(1, 2)).apply {
            add(JBScrollPane(curlPreview).apply { border = IdeBorderFactory.createTitledBorder("curl command") })
            add(JBScrollPane(bodyPreview).apply { border = IdeBorderFactory.createTitledBorder("request.json") })
        }
        val lower = JPanel(BorderLayout()).apply {
            add(problems, BorderLayout.NORTH)
            add(previews, BorderLayout.CENTER)
            add(responsePane, BorderLayout.SOUTH)
        }
        return JPanel(BorderLayout(0, JBUI.scale(6))).apply {
            add(settings, BorderLayout.NORTH)
            add(editor, BorderLayout.CENTER)
            add(lower, BorderLayout.SOUTH)
            preferredSize = Dimension(JBUI.scale(980), JBUI.scale(760))
        }
    }

    override fun createActions(): Array<Action> = arrayOf(okAction)

    override fun createLeftSideActions(): Array<Action> = arrayOf(copyAction, saveAction, runAction)

    override fun dispose() {
        scope.cancel()
        super.dispose()
    }

    private fun wire() {
        taskBox.addActionListener { refresh() }
        actionBox.addActionListener { refresh() }
        rowList.addListSelectionListener { if (!it.valueIsAdjusting) loadRow() }
        addRow.addActionListener {
            rows.add(OperateRowEdit(nextRowId()))
            rowList.selectedIndex = rows.size - 1
            refresh()
        }
        removeRow.addActionListener {
            val index = rowList.selectedIndex
            if (index >= 0 && rows.size > 1) {
                rows.remove(index)
                rowList.selectedIndex = index.coerceAtMost(rows.size - 1)
                refresh()
            }
        }
        idField.document.addDocumentListener(onChange { selectedRow()?.let { it.id = idField.text }; rows.contentsChanged(selectedRow() ?: return@onChange); refresh() })
        operationsArea.document.addDocumentListener(onChange { selectedRow()?.operations = operationsArea.text; refresh() })
        operationBox.addActionListener { valueField.isEnabled = (operationBox.selectedItem as OperateOperation).takesValue }
        addOperation.addActionListener { addOperationToRow() }
    }

    private fun onChange(action: () -> Unit) = object : DocumentAdapter() {
        override fun textChanged(e: DocumentEvent) {
            if (!loading) action()
        }
    }

    private fun selectedRow(): OperateRowEdit? = rowList.selectedValue

    private fun loadRow() {
        loading = true
        try {
            val row = selectedRow()
            idField.text = row?.id.orEmpty()
            operationsArea.text = row?.operations.orEmpty()
            idField.isEnabled = row != null
            operationsArea.isEnabled = row != null
            addOperation.isEnabled = row != null
        } finally {
            loading = false
        }
        refresh()
    }

    private fun nextRowId(): String {
        val used = rows.items.map(OperateRowEdit::id).toSet()
        return generateSequence(rows.size + 1) { it + 1 }.map { "case-$it" }.first { it !in used }
    }

    private fun addOperationToRow() {
        val row = selectedRow() ?: return
        val command = try {
            operateCommand(operationBox.selectedItem as OperateOperation, pathField.text, valueField.text)
        } catch (error: IllegalArgumentException) {
            helperMessage.text = error.message
            return
        }
        helperMessage.text = ""
        val current = operationsArea.text
        operationsArea.text = if (current.isBlank()) command else current.trimEnd('\n') + "\n" + command
        row.operations = operationsArea.text
        valueField.text = ""
        refresh()
    }

    private fun request() = OperateRequest(
        artifact,
        actionBox.selectedItem as NotMatchedAction,
        rows.items.map { row -> OperateRow(row.id.trim(), row.operations.lines().map(String::trim).filter(String::isNotEmpty)) },
    )

    private fun refresh() {
        val request = request()
        // WHY: the platform limits stop Save and Run. The rules for the file names of the script stop only the script.
        val bodyProblems = request.problems()
        val found = bodyProblems + request.scriptProblems()
        problems.text = if (found.isEmpty()) "" else "<html>" + found.joinToString("<br>") { escapeXmlEntities(it) } + "</html>"
        bodyPreview.text = request.body()
        curlPreview.text = if (found.isEmpty()) {
            request.script(identity.origin.value, identity.projectId, taskBox.selectedItem as String)
        } else {
            "Fix the problems above to see the command."
        }
        curlPreview.caretPosition = 0
        bodyPreview.caretPosition = 0
        removeRow.isEnabled = rows.size > 1
        copyAction.isEnabled = found.isEmpty()
        saveAction.isEnabled = bodyProblems.isEmpty()
        runAction.isEnabled = bodyProblems.isEmpty() && !running
    }

    private fun saveBody() {
        val request = request()
        if (request.problems().isNotEmpty()) return
        val target = FileChooserFactory.getInstance()
            .createSaveFileDialog(FileSaverDescriptor("Save request.json", "Save the request body of the Operate call", *arrayOf("json")), project)
            .save(project.basePath?.let { Path.of(it) }, "request.json")
            ?.file
            ?.toPath()
            ?: return
        try {
            Files.writeString(target, request.body(), Charsets.UTF_8)
        } catch (error: IOException) {
            Messages.showErrorDialog(project, "Cannot save $target: ${error.message}", "Save request.json")
        }
    }

    private fun runNow() {
        val request = request()
        if (running || request.problems().isNotEmpty()) return
        val task = taskBox.selectedItem as String
        running = true
        runAction.isEnabled = false
        responsePane.isVisible = true
        response.text = "Running…"
        revalidateResponse()
        // WHY: the dialog is modal. Without its modality state the UI thread can resume this block only after the dialog closes.
        scope.launch(Dispatchers.EDT + ModalityState.current().asContextElement()) {
            val text = try {
                withContext(Dispatchers.IO) { runCall(task, request.body()) }.render().let {
                    if (it.length > MAX_RESPONSE_CHARS) it.take(MAX_RESPONSE_CHARS) + "\n… The text is longer. Run the curl command to get all of it." else it
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                "Run failed: ${error.message ?: error.javaClass.simpleName}"
            }
            running = false
            response.text = text
            response.caretPosition = 0
            revalidateResponse()
            refresh()
        }
    }

    private fun revalidateResponse() {
        // WHY: revalidate() reaches the nearest validate root, so the dialog does not need to be on screen yet.
        responsePane.revalidate()
        responsePane.repaint()
    }

    private fun pathRules(): String {
        val shared = "A path that starts with <code>/</code>, <code>.</code>, <code>*</code>, <code>@</code> or <code>(</code> is read from the root as written. " +
            "A path that starts with a name is read like <code>//path</code>. A path cannot contain a comma. " +
            "Quote a string value, for example <code>'53111'</code>. An unquoted value is read as JSON when it can be: <code>5</code>, <code>true</code>, <code>null</code>. " +
            "For the value of each operation, see the operation reference in the platform documentation."
        val specific = if (format == OperateFormat.XML) {
            "XML uses XPath. A path must select elements. To change an attribute, select its element and use <code>upsertAttribute</code>."
        } else {
            "JSON uses JSONPath, for example <code>set(\$.a.b, 5)</code>."
        }
        return "<html><body style='width: ${JBUI.scale(900)}px'>$specific $shared</body></html>"
    }

    private fun monospace(area: JBTextArea): JBTextArea = area.apply { font = JBFont.create(Font(Font.MONOSPACED, Font.PLAIN, font.size)) }

    private fun action(name: String, run: () -> Unit): AbstractAction = object : AbstractAction(name) {
        override fun actionPerformed(e: ActionEvent?) = run()
    }

    private companion object {
        const val LATEST = "latest"
        const val MAX_RESPONSE_CHARS = 200_000
    }
}
