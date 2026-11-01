// DATAMIMIC for JetBrains IDEs
// Copyright (c) 2026 Rapiddweller Asia Co., Ltd.
// SPDX-License-Identifier: MIT

package com.rapiddweller.datamimic.ide.creation.truck

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.intellij.util.ui.NamedColorUtil
import com.intellij.util.ui.UIUtil
import com.rapiddweller.datamimic.core.creation.CreationFlow
import com.rapiddweller.datamimic.core.creation.street.nameProblem
import com.rapiddweller.datamimic.core.creation.street.targetPaths
import javax.swing.JComponent
import javax.swing.event.DocumentEvent

/**
 * Asks for the type and the name of a new file in [dir]. It shows any [CreationFlow] and names none.
 *
 * @param dir the project folder of the new file, empty for the project root.
 * @param error a platform message about the last try, shown under the name.
 */
class CreationDialog(
    project: Project,
    flows: List<CreationFlow>,
    private val dir: String,
    initialFlowId: String? = null,
    initialName: String = "",
    error: String? = null,
) : DialogWrapper(project) {
    private val flowBox = ComboBox(flows.toTypedArray()).apply {
        renderer = textListCellRenderer { it?.label.orEmpty() }
        flows.firstOrNull { it.id == initialFlowId }?.let { selectedItem = it }
    }
    private val nameField = JBTextField(initialName)
    private val errorLabel = JBLabel(error.orEmpty()).apply {
        foreground = NamedColorUtil.getErrorForeground()
        isVisible = error != null
    }
    private val preview = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }

    /** The chosen flow; read it after OK. */
    val flow: CreationFlow get() = flowBox.item

    /** The entered name; read it after OK. */
    val fileName: String get() = nameField.text

    init {
        title = "New DATAMIMIC file"
        flowBox.addActionListener { updatePreview() }
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
        row("Type:") { cell(flowBox).align(AlignX.FILL) }
        row("Name:") { cell(nameField).align(AlignX.FILL) }
        row("") { cell(errorLabel) }
        row("Folder:") { cell(JBTextField(dir.ifEmpty { "." }).apply { isEditable = false }).align(AlignX.FILL) }
        row("") { cell(preview) }
    }

    override fun getPreferredFocusedComponent(): JComponent = nameField

    override fun doValidate(): ValidationInfo? = nameProblem(fileName)?.let { ValidationInfo(it, nameField) }

    private fun updatePreview() {
        // WHY: a blank text collapses the row, so the dialog would change its height while the user types.
        preview.text = if (nameProblem(fileName) != null) " " else "Creates " + flow.toRequest(dir, fileName).targetPaths().joinToString()
    }
}
