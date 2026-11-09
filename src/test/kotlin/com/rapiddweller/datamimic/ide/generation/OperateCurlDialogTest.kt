// DATAMIMIC for JetBrains IDEs
// Copyright (c) 2026 Rapiddweller Asia Co., Ltd.
// SPDX-License-Identifier: MIT

package com.rapiddweller.datamimic.ide.generation

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.rapiddweller.datamimic.core.PlatformOrigin
import com.rapiddweller.datamimic.core.generation.NotMatchedAction
import com.rapiddweller.datamimic.core.generation.OperateFormat
import com.rapiddweller.datamimic.core.generation.OperateResponse
import com.rapiddweller.datamimic.core.json
import com.rapiddweller.datamimic.core.workspace.FolderIdentity
import java.util.concurrent.CopyOnWriteArrayList

class OperateCurlDialogTest : BasePlatformTestCase() {
    private val calls = CopyOnWriteArrayList<Pair<String, String>>()
    private var failure: Exception? = null
    private lateinit var dialog: OperateCurlDialog

    private val response = json.decodeFromString<OperateResponse>(
        """{"task_id":"t-9","operate_id":"op-1","artifacts":[{"id":"case-1","content":"<order/>\n","media_type":"application/xml","outcomes":[
            {"operation_index":0,"command":"set(//order/id, '2')","status":"applied","path":null}]}]}""",
    )

    override fun setUp() {
        super.setUp()
        dialog = open("xml/order.xml", OperateFormat.XML)
    }

    override fun tearDown() {
        try {
            Disposer.dispose(dialog.disposable)
        } finally {
            super.tearDown()
        }
    }

    private fun open(artifact: String, format: OperateFormat) = OperateCurlDialog(
        project,
        artifact,
        format,
        "task-1",
        FolderIdentity(PlatformOrigin("https://dm.example.com"), "p1", "Payments"),
    ) { task, body ->
        failure?.let { throw it }
        calls += task to body
        response
    }

    fun `test the preview shows the command and the body from the first moment`() {
        assertTrue(dialog.curlPreview.text, dialog.curlPreview.text.startsWith("export DATAMIMIC_URL='https://dm.example.com'\nexport PROJECT_ID='p1'\nexport TASK_ID='task-1'\n"))
        assertTrue(dialog.curlPreview.text.endsWith("| jq --raw-output '.artifacts[0].content' > case-1.xml"))
        assertTrue(dialog.bodyPreview.text, dialog.bodyPreview.text.contains("\"artifact\": \"xml/order.xml\""))
        assertEquals("", dialog.problems.text)
        assertTrue(dialog.copyAction.isEnabled && dialog.saveAction.isEnabled && dialog.runAction.isEnabled)
    }

    fun `test the helper adds a command and the preview follows`() {
        dialog.operationBox.selectedItem = OperateFormat.XML.operations.first { it.name == "set" }
        dialog.pathField.text = "//order/id"
        dialog.valueField.text = "'2'"
        dialog.addOperation.doClick()

        assertEquals("set(//order/id, '2')", dialog.operationsArea.text)
        assertEquals("", dialog.helperMessage.text)
        assertTrue(dialog.bodyPreview.text, dialog.bodyPreview.text.contains("\"set(//order/id, '2')\""))

        dialog.operationBox.selectedItem = OperateFormat.XML.operations.first { it.name == "delete" }
        dialog.pathField.text = "//order/note"
        dialog.addOperation.doClick()

        assertEquals("set(//order/id, '2')\ndelete(//order/note)", dialog.operationsArea.text)
        assertTrue(dialog.bodyPreview.text, dialog.bodyPreview.text.contains("\"delete(//order/note)\""))
    }

    fun `test the helper names what is missing and a command with no value switches the value off`() {
        dialog.operationBox.selectedItem = OperateFormat.XML.operations.first { it.name == "set" }
        assertTrue(dialog.valueField.isEnabled)
        dialog.addOperation.doClick()
        assertEquals("Enter a path.", dialog.helperMessage.text)

        dialog.pathField.text = "//order/id"
        dialog.addOperation.doClick()
        assertTrue(dialog.helperMessage.text, dialog.helperMessage.text.startsWith("Enter a value."))
        assertEquals("", dialog.operationsArea.text)

        dialog.operationBox.selectedItem = OperateFormat.XML.operations.first { it.name == "delete" }
        assertFalse(dialog.valueField.isEnabled)
    }

    fun `test a JSON artifact offers the JSON operations`() {
        val json = open("customer.json", OperateFormat.JSON)
        try {
            val names = (0 until json.operationBox.itemCount).map { json.operationBox.getItemAt(it).name }

            assertTrue(names.contains("upsertProperty"))
            assertFalse(names.contains("upsertAttribute"))
            assertTrue(json.curlPreview.text.endsWith("> case-1.json"))
        } finally {
            Disposer.dispose(json.disposable)
        }
    }

    fun `test rows can be added and removed and each row keeps its own operations`() {
        dialog.operationsArea.text = "delete(//a)"
        dialog.addRow.doClick()

        assertEquals(2, dialog.rows.size)
        assertEquals("case-2", dialog.idField.text)
        assertEquals("", dialog.operationsArea.text)
        dialog.operationsArea.text = "delete(//b)"
        assertTrue(dialog.curlPreview.text, dialog.curlPreview.text.contains("--output response.json"))

        dialog.rowList.selectedIndex = 0
        assertEquals("delete(//a)", dialog.operationsArea.text)
        dialog.rowList.selectedIndex = 1
        assertEquals("delete(//b)", dialog.operationsArea.text)

        dialog.removeRow.doClick()
        assertEquals(1, dialog.rows.size)
        assertFalse(dialog.removeRow.isEnabled)
        assertEquals("delete(//a)", dialog.operationsArea.text)
    }

    fun `test an unsafe row ID stops the command but not the body`() {
        dialog.idField.text = "bad id; rm -rf ~"

        assertTrue(dialog.problems.text, dialog.problems.text.contains("must start with a letter or digit"))
        assertEquals("Fix the problems above to see the command.", dialog.curlPreview.text)
        assertFalse(dialog.copyAction.isEnabled)
        // WHY: the platform accepts the ID. Only the file name in the command is unsafe.
        assertTrue(dialog.saveAction.isEnabled)
        assertTrue(dialog.runAction.isEnabled)

        dialog.idField.text = "case-1"
        assertEquals("", dialog.problems.text)
        assertTrue(dialog.copyAction.isEnabled)
    }

    fun `test the platform limits stop Save and Run too`() {
        dialog.operationsArea.text = "set(//a, '${"x".repeat(1_048_576)}')"

        assertTrue(dialog.problems.text, dialog.problems.text.contains("larger than 1 MiB"))
        assertFalse(dialog.copyAction.isEnabled)
        assertFalse(dialog.saveAction.isEnabled)
        assertFalse(dialog.runAction.isEnabled)
    }

    fun `test the task can be the selected task or latest, and the action for no match is in the body`() {
        assertEquals(listOf("task-1", "latest"), (0 until dialog.taskBox.itemCount).map { dialog.taskBox.getItemAt(it) })
        assertEquals(NotMatchedAction.WARN, dialog.actionBox.selectedItem)

        dialog.taskBox.selectedItem = "latest"
        dialog.actionBox.selectedItem = NotMatchedAction.ERROR

        assertTrue(dialog.curlPreview.text, dialog.curlPreview.text.contains("export TASK_ID='latest'"))
        assertTrue(dialog.bodyPreview.text, dialog.bodyPreview.text.contains("\"operation_not_matched_action\": \"error\""))
    }

    fun `test the command and the body never hold a token`() {
        val text = dialog.curlPreview.text + dialog.bodyPreview.text

        assertEquals(1, Regex("Bearer").findAll(text).count())
        assertTrue(text.contains("Bearer \$PROJECT_TOKEN"))
    }

    fun `test Run now sends the request body for the chosen task and shows the response`() {
        dialog.operationsArea.text = "set(//order/id, '2')"
        dialog.taskBox.selectedItem = "latest"

        dialog.runAction.actionPerformed(null)
        PlatformTestUtil.waitWithEventsDispatching("the response was not shown", { dialog.response.text.startsWith("Task: t-9") }, 5)

        assertEquals(1, calls.size)
        assertEquals("latest", calls.single().first)
        assertTrue(calls.single().second, calls.single().second.contains("\"set(//order/id, '2')\""))
        assertTrue(dialog.response.text, dialog.response.text.contains("All operations applied.\n<order/>"))
        assertTrue(dialog.runAction.isEnabled)
    }

    fun `test a failed Run now shows the error and can run again`() {
        failure = IllegalStateException("Path matched nothing: //order/missing (POST /operate → 422)")

        dialog.runAction.actionPerformed(null)
        PlatformTestUtil.waitWithEventsDispatching("the error was not shown", { dialog.response.text.startsWith("Run failed") && dialog.runAction.isEnabled }, 5)

        assertEquals("Run failed: Path matched nothing: //order/missing (POST /operate → 422)", dialog.response.text)

        failure = null
        dialog.runAction.actionPerformed(null)
        PlatformTestUtil.waitWithEventsDispatching("the retry did not run", { dialog.response.text.startsWith("Task: t-9") }, 5)
    }
}
