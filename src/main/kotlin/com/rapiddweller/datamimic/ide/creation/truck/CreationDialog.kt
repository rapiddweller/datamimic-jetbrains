// DATAMIMIC for JetBrains IDEs
// Copyright (c) 2026 Rapiddweller Asia Co., Ltd.
// SPDX-License-Identifier: MIT

package com.rapiddweller.datamimic.ide.creation.truck

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.ComboboxSpeedSearch
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.intellij.util.ui.NamedColorUtil
import com.intellij.util.ui.UIUtil
import com.intellij.ui.dsl.builder.Row
import com.rapiddweller.datamimic.core.creation.Choice
import com.rapiddweller.datamimic.core.creation.CreationFlow
import com.rapiddweller.datamimic.core.creation.street.targetPaths
import javax.swing.JComponent
import javax.swing.event.DocumentEvent

/**
 * Asks for the type, the choices and the name of a new file in [dir]. It shows any [CreationFlow] and names none.
 *
 * @param dir the project folder of the new file, empty for the project root.
 * @param initialName the name to show first; empty takes the [CreationFlow.initialName] of the selected flow.
 * @param error a platform message about the last try, shown under the name.
 * @param initialChoices the selected [Choice.Option.value] for each [Choice.id] of the last try.
 * @param source the name of the local source file, shown read-only; null shows no source row.
 */
class CreationDialog(
    project: Project,
    private val flows: List<CreationFlow>,
    private val dir: String,
    initialFlowId: String? = null,
    initialName: String = "",
    error: String? = null,
    initialChoices: Map<String, String> = emptyMap(),
    dialogTitle: String = "New DATAMIMIC file",
    private val source: String? = null,
) : DialogWrapper(project) {
    private val flowBox = ComboBox(flows.toTypedArray()).apply {
        renderer = textListCellRenderer { it?.label.orEmpty() }
        flows.firstOrNull { it.id == initialFlowId }?.let { selectedItem = it }
        // WHY: the platform offers about 30 types; typing in the list jumps to a match, so nobody scrolls.
        ComboboxSpeedSearch.installSpeedSearch(this) { it.label }
    }
    private val choiceBoxes = flows.flatMap { it.choices }.distinct().associateWith { choice ->
        ComboBox(choice.options.toTypedArray()).apply {
            renderer = textListCellRenderer { it?.label.orEmpty() }
            val value = initialChoices[choice.id]?.takeIf { value -> choice.options.any { it.value == value } } ?: choice.default
            selectedItem = choice.options.first { it.value == value }
        }
    }
    private val choiceRows = mutableMapOf<Choice, Row>()
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

    /** The selected [Choice.Option.value] for each [Choice.id] of the chosen flow; read it after OK. */
    val choices: Map<String, String>
        get() = flow.choices.associate { it.id to choiceBoxes.getValue(it).item.value }

    init {
        title = dialogTitle
        flowBox.addActionListener {
            if (choiceRows.isNotEmpty()) {
                showChoices()
                pack()
            }
            updatePreview()
        }
        choiceBoxes.values.forEach { box -> box.addActionListener { updatePreview() } }
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
        for ((choice, box) in choiceBoxes) {
            choiceRows[choice] = row("${choice.label}:") { cell(box).align(AlignX.FILL) }
        }
        row("Name:") { cell(nameField).align(AlignX.FILL) }
        row("") { cell(errorLabel) }
        row("Folder:") { cell(JBTextField(dir.ifEmpty { "." }).apply { isEditable = false }).align(AlignX.FILL) }
        row("") { cell(preview) }
    }.also { showChoices() }

    override fun getPreferredFocusedComponent(): JComponent = nameField

    override fun doValidate(): ValidationInfo? = flow.nameProblem(fileName)?.let { ValidationInfo(it, nameField) }

    private fun showChoices() {
        choiceRows.forEach { (choice, row) -> row.visible(choice in flow.choices) }
    }

    private fun updatePreview() {
        // WHY: a blank text collapses the row, so the dialog would change its height while the user types.
        preview.text = if (flow.nameProblem(fileName) != null) " " else "Creates " + flow.toRequest(dir, fileName, choices).targetPaths().joinToString(", ")
    }
}
