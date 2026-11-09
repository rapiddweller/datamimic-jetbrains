// DATAMIMIC for JetBrains IDEs
// Copyright (c) 2026 Rapiddweller Asia Co., Ltd.
// SPDX-License-Identifier: MIT

package com.rapiddweller.datamimic.ide.creation.truck

import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.ComboboxSpeedSearch
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.CollapsibleRow
import com.intellij.ui.dsl.builder.Row
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.intellij.util.ui.NamedColorUtil
import com.intellij.util.ui.UIUtil
import com.rapiddweller.datamimic.core.creation.Choice
import com.rapiddweller.datamimic.core.creation.CreationFlow
import com.rapiddweller.datamimic.core.creation.Field
import com.rapiddweller.datamimic.core.creation.FileInput
import com.rapiddweller.datamimic.core.creation.TextInput
import com.rapiddweller.datamimic.core.creation.street.CreationRequest
import com.rapiddweller.datamimic.core.creation.street.embeddedProblem
import com.rapiddweller.datamimic.core.creation.street.targetPaths
import javax.swing.JComponent
import javax.swing.event.DocumentEvent

/**
 * Asks for the type, the fields and the name of a new file in [dir]. It shows any [CreationFlow] and names none.
 *
 * @param dir the project folder of the new file, empty for the project root.
 * @param initialName the name to show first; empty takes the [CreationFlow.initialName] of the selected flow.
 * @param error a platform message about the last try, shown under the name.
 * @param initialValues the [values] of the last try.
 * @param source the path of the local source file, shown read-only; null shows no source row.
 */
class CreationDialog(
    project: Project,
    private val flows: List<CreationFlow>,
    private val dir: String,
    initialFlowId: String? = null,
    initialName: String = "",
    error: String? = null,
    initialValues: Map<String, String> = emptyMap(),
    dialogTitle: String = "New DATAMIMIC file",
    private val source: String? = null,
) : DialogWrapper(project) {
    private val flowBox = ComboBox(flows.toTypedArray()).apply {
        renderer = textListCellRenderer { it?.label.orEmpty() }
        flows.firstOrNull { it.id == initialFlowId }?.let { selectedItem = it }
        // WHY: the platform offers about 30 types; typing in the list jumps to a match, so nobody scrolls.
        ComboboxSpeedSearch.installSpeedSearch(this) { it.label }
    }
    private val widgets = flows.flatMap { it.fields }.distinct().associateWith { widget(project, it, initialValues[it.id]) }
    private val rows = mutableMapOf<Field, Row>()
    private var advancedGroup: CollapsibleRow? = null
    private val nameField = JBTextField(initialName.ifEmpty { flow.initialName })
    private val errorLabel = JBLabel(error.orEmpty()).apply {
        foreground = NamedColorUtil.getErrorForeground()
        isVisible = error != null
    }
    private val preview = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }

    /** The chosen flow; read it after OK. */
    val flow: CreationFlow get() = flowBox.item

    /** The entered name; read it after OK. */
    val fileName: String get() = nameField.text

    /**
     * The value of each [Field.id] of the chosen flow; read it after OK. A [FileInput] value is the path of the file, and
     * the caller turns it into the content.
     */
    val values: Map<String, String>
        get() = flow.fields.associate { it.id to widgets.getValue(it).value() }

    init {
        title = dialogTitle
        flowBox.addActionListener {
            if (rows.isNotEmpty()) {
                showFields()
                pack()
            }
            updatePreview()
        }
        widgets.values.forEach { it.onChange(::updatePreview) }
        nameField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                errorLabel.isVisible = false
                updatePreview()
            }
        })
        updatePreview()
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        source?.let { row("Source:") { cell(JBTextField(it).apply { isEditable = false }).align(AlignX.FILL) } }
        row("Type:") { cell(flowBox).align(AlignX.FILL) }
        for ((field, widget) in widgets.filterKeys { !it.isAdvanced }) {
            rows[field] = row("${field.label}:") { cell(widget.component).align(AlignX.FILL) }
        }
        row("Name:") { cell(nameField).align(AlignX.FILL) }
        row("") { cell(errorLabel) }
        advancedGroup = collapsibleGroup("Options") {
            for ((field, widget) in widgets.filterKeys { it.isAdvanced }) {
                rows[field] = row("${field.label}:") { cell(widget.component).align(AlignX.FILL) }
            }
        }
        row("Folder:") { cell(JBTextField(dir.ifEmpty { "." }).apply { isEditable = false }).align(AlignX.FILL) }
        row("") { cell(preview) }
    }.also { showFields() }

    override fun getPreferredFocusedComponent(): JComponent = nameField

    override fun doValidate(): ValidationInfo? {
        flow.nameProblem(fileName)?.let { return ValidationInfo(it, nameField) }
        for (field in flow.fields) {
            val widget = widgets.getValue(field)
            val value = widget.value().trim()
            val problem = when {
                field is TextInput && field.required && value.isEmpty() -> "Enter ${field.label.lowercase()}."
                field is FileInput && value.isEmpty() -> "Choose the ${field.label.lowercase()}."
                field is FileInput -> embeddedProblem(value)
                else -> null
            }
            if (problem != null) return ValidationInfo(problem, widget.component)
        }
        return null
    }

    private val Field.isAdvanced get() = this is TextInput && advanced

    private fun showFields() {
        rows.forEach { (field, row) -> row.visible(field in flow.fields) }
        advancedGroup?.visible(flow.fields.any { it.isAdvanced })
    }

    private fun updatePreview() {
        // WHY: a blank text collapses the row. The dialog then changes its height while the user types.
        preview.text = if (flow.nameProblem(fileName) != null) " " else describe(flow.toRequest(dir, fileName, values))
    }

    private fun describe(request: CreationRequest): String {
        val folders = (request as? CreationRequest.FromUpload)?.folders.orEmpty().map { "files in $it/" }
        return "Creates " + (request.targetPaths() + folders).joinToString(", ")
    }
}

/** A dialog widget for one [Field]: the component, the current value, and a hook for changes. */
private class Widget(val component: JComponent, val value: () -> String, val onChange: (() -> Unit) -> Unit)

private fun widget(project: Project, field: Field, initial: String?): Widget = when (field) {
    is Choice -> {
        val box = ComboBox(field.options.toTypedArray()).apply {
            renderer = textListCellRenderer { it?.label.orEmpty() }
            val value = initial?.takeIf { value -> field.options.any { it.value == value } } ?: field.default
            selectedItem = field.options.first { it.value == value }
        }
        Widget(box, { box.item.value }) { listener -> box.addActionListener { listener() } }
    }
    is TextInput -> {
        val text = JBTextField(initial ?: field.default).apply { emptyText.text = field.hint }
        Widget(text, { text.text }) { listener ->
            text.document.addDocumentListener(object : DocumentAdapter() {
                override fun textChanged(e: DocumentEvent) = listener()
            })
        }
    }
    is FileInput -> {
        val chooser = FileChooserDescriptor(true, false, false, false, false, false)
            .withFileFilter { file -> file.extension?.lowercase() in field.extensions }
        val path = TextFieldWithBrowseButton().apply {
            text = initial.orEmpty()
            // WHY: addBrowseFolderListener(Project, FileChooserDescriptor) does not exist in IDE 2024.2.
            addActionListener { FileChooser.chooseFile(chooser.withTitle(field.label), project, null)?.let { text = it.path } }
        }
        Widget(path, { path.text }) { listener ->
            path.textField.document.addDocumentListener(object : DocumentAdapter() {
                override fun textChanged(e: DocumentEvent) = listener()
            })
        }
    }
}
