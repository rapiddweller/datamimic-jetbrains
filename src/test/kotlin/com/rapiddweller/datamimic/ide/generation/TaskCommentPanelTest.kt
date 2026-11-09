// DATAMIMIC for JetBrains IDEs
// Copyright (c) 2026 Rapiddweller Asia Co., Ltd.
// SPDX-License-Identifier: MIT

package com.rapiddweller.datamimic.ide.generation

import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.rapiddweller.datamimic.core.generation.GenerationTask
import com.rapiddweller.datamimic.core.generation.TaskComment
import com.rapiddweller.datamimic.core.generation.TaskStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class TaskCommentPanelTest : BasePlatformTestCase() {
    private val scope = CoroutineScope(SupervisorJob())
    private val saves = CopyOnWriteArrayList<Pair<String, String>>()
    private val rows = CopyOnWriteArrayList<Pair<String, TaskComment>>()
    private var failure: Exception? = null
    private var gate: CountDownLatch? = null
    private val ended = AtomicInteger()
    private lateinit var panel: TaskCommentPanel

    override fun setUp() {
        super.setUp()
        panel = TaskCommentPanel(
            scope,
            { id, text ->
                try {
                    gate?.await(5, TimeUnit.SECONDS)
                    failure?.let { throw it }
                    saves += id to text
                    TaskComment(text.ifEmpty { null }, "ada@example.com", "2026-11-08T10:15:00")
                } finally {
                    ended.incrementAndGet()
                }
            },
            { id, comment -> rows += id to comment },
        )
    }

    override fun tearDown() {
        try {
            scope.cancel()
        } finally {
            super.tearDown()
        }
    }

    fun `test the editor shows count, last editor and edit time, and keeps Save off while the text is unchanged`() {
        panel.bind(task("t1", TaskComment("Baseline", "ada@example.com", "2026-11-08T10:15:00")))

        assertEquals("Baseline", panel.area.text)
        assertEquals("8/500", panel.count.text)
        assertTrue(panel.info.text, panel.info.text.startsWith("Last edited by ada@example.com on "))
        assertFalse(panel.saveButton.isEnabled)
        assertFalse(panel.cancelButton.isEnabled)

        panel.area.text = "Baseline v2"
        assertEquals("11/500", panel.count.text)
        assertTrue(panel.saveButton.isEnabled)
        assertTrue(panel.cancelButton.isEnabled)

        panel.area.text = "Baseline"
        assertFalse(panel.saveButton.isEnabled)
        // WHY: the platform strips the text, so trailing spaces are not a change.
        panel.area.text = "  Baseline \n"
        assertFalse(panel.saveButton.isEnabled)
        assertEquals("8/500", panel.count.text)
    }

    fun `test a task without a comment says so and Save needs text`() {
        panel.bind(task("t1", TaskComment.NONE))

        assertEquals("", panel.area.text)
        assertEquals("No comment yet.", panel.info.text)
        assertFalse(panel.saveButton.isEnabled)
        panel.area.text = "First"
        assertTrue(panel.saveButton.isEnabled)
    }

    fun `test Save is off above 500 characters and the count counts the trimmed text`() {
        panel.bind(task("t1", TaskComment.NONE))

        panel.area.text = "x".repeat(501)
        assertEquals("501/500", panel.count.text)
        assertFalse(panel.saveButton.isEnabled)

        panel.area.text = "x".repeat(500) + "   \n"
        assertEquals("500/500", panel.count.text)
        assertTrue(panel.saveButton.isEnabled)

        // WHY: the platform counts characters, so one emoji is one.
        panel.area.text = "😀".repeat(500)
        assertEquals("500/500", panel.count.text)
        assertTrue(panel.saveButton.isEnabled)
    }

    fun `test Save sends the trimmed text and shows the answer of the platform`() {
        panel.bind(task("t1", TaskComment.NONE))
        panel.area.text = "  New note  "

        panel.saveButton.doClick()
        PlatformTestUtil.waitWithEventsDispatching("the comment was not saved", { rows.isNotEmpty() && panel.saveButton.isEnabled.not() }, 5)

        assertEquals(listOf("t1" to "New note"), saves)
        assertEquals(listOf("t1" to TaskComment("New note", "ada@example.com", "2026-11-08T10:15:00")), rows)
        assertEquals("New note", panel.area.text)
        assertEquals("", panel.error.text)
        assertTrue(panel.info.text, panel.info.text.startsWith("Last edited by ada@example.com on "))
        assertFalse(panel.saveButton.isEnabled)
        assertFalse(panel.cancelButton.isEnabled)
    }

    fun `test a blank text removes the comment`() {
        panel.bind(task("t1", TaskComment("Old", "bob@example.com", "2026-11-07T09:00:00")))
        panel.area.text = "   "
        assertTrue(panel.saveButton.isEnabled)

        panel.saveButton.doClick()
        PlatformTestUtil.waitWithEventsDispatching("the comment was not removed", { rows.isNotEmpty() }, 5)

        assertEquals(listOf("t1" to ""), saves)
        assertEquals("", panel.area.text)
    }

    fun `test a failed Save keeps the draft, shows the error and allows a retry`() {
        panel.bind(task("t1", TaskComment("Stored", null, null)))
        panel.area.text = "  My long draft  "
        failure = IllegalStateException("The comment was not saved (PUT /comment → 500)")

        panel.saveButton.doClick()
        PlatformTestUtil.waitWithEventsDispatching("the error was not shown", { panel.error.text.isNotBlank() && panel.saveButton.isEnabled }, 5)

        assertEquals("  My long draft  ", panel.area.text)
        assertTrue(panel.error.text, panel.error.text.contains("The comment was not saved (PUT /comment → 500)"))
        assertTrue(rows.isEmpty())
        assertTrue(panel.saveButton.isEnabled)

        failure = null
        panel.saveButton.doClick()
        PlatformTestUtil.waitWithEventsDispatching("the retry did not save", { rows.isNotEmpty() }, 5)

        assertEquals(listOf("t1" to "My long draft"), saves)
        assertEquals("", panel.error.text)
    }

    fun `test Cancel restores the saved text and clears the error`() {
        panel.bind(task("t1", TaskComment("Stored", null, null)))
        panel.area.text = "Changed my mind"

        panel.cancelButton.doClick()

        assertEquals("Stored", panel.area.text)
        assertFalse(panel.saveButton.isEnabled)
        assertFalse(panel.cancelButton.isEnabled)
        assertEquals("", panel.error.text)
    }

    fun `test an unsaved draft survives a refresh of the list and a switch to another task`() {
        panel.bind(task("t1", TaskComment("A", null, null)))
        panel.area.text = "my draft"

        // The list reloads and the platform has a newer comment.
        panel.bind(task("t1", TaskComment("B", "bob@example.com", "2026-11-08T11:00:00")))
        assertEquals("my draft", panel.area.text)
        assertTrue(panel.saveButton.isEnabled)

        panel.bind(task("t2", TaskComment("Other", null, null)))
        assertEquals("Other", panel.area.text)
        assertFalse(panel.saveButton.isEnabled)

        panel.bind(task("t1", TaskComment("B", "bob@example.com", "2026-11-08T11:00:00")))
        assertEquals("my draft", panel.area.text)

        panel.cancelButton.doClick()
        assertEquals("B", panel.area.text)
        panel.bind(task("t2", TaskComment("Other", null, null)))
        panel.bind(task("t1", TaskComment("B", "bob@example.com", "2026-11-08T11:00:00")))
        assertEquals("B", panel.area.text)
    }

    fun `test without a task in the list the editor is off`() {
        panel.bind(task("t1", TaskComment("A", null, null)))

        panel.bind(null)

        assertFalse(panel.area.isEnabled)
        assertFalse(panel.saveButton.isEnabled)
        assertFalse(panel.cancelButton.isEnabled)
        assertEquals("", panel.area.text)
    }

    fun `test the error of a failed Save and the draft come back when the user returns to the task`() {
        panel.bind(task("t1", TaskComment("Stored", null, null)))
        panel.area.text = "my draft"
        failure = IllegalStateException("The platform is down")
        panel.saveButton.doClick()
        PlatformTestUtil.waitWithEventsDispatching("the error was not shown", { panel.error.text.isNotBlank() }, 5)

        panel.bind(task("t2", TaskComment("Other", null, null)))
        assertEquals("", panel.error.text)
        panel.bind(task("t1", TaskComment("Stored", null, null)))

        assertEquals("my draft", panel.area.text)
        assertTrue(panel.error.text, panel.error.text.contains("The platform is down"))
    }

    fun `test a Save that fails after the user selected another task does not touch that task`() {
        panel.bind(task("t1", TaskComment("Stored", null, null)))
        panel.area.text = "my draft"
        val release = CountDownLatch(1)
        gate = release
        failure = IllegalStateException("The platform is down")
        panel.saveButton.doClick()

        panel.bind(task("t2", TaskComment("Other", null, null)))
        release.countDown()
        PlatformTestUtil.waitWithEventsDispatching("the Save did not end", { ended.get() == 1 }, 5)
        settle()

        assertEquals("Other", panel.area.text)
        assertEquals("", panel.error.text)
        panel.bind(task("t1", TaskComment("Stored", null, null)))
        assertEquals("my draft", panel.area.text)
        assertTrue(panel.error.text, panel.error.text.contains("The platform is down"))
    }

    fun `test a slow Save of one task does not block the editor of another task`() {
        panel.bind(task("t1", TaskComment("Stored", null, null)))
        panel.area.text = "my draft"
        gate = CountDownLatch(1)
        panel.saveButton.doClick()
        assertFalse(panel.area.isEditable)

        panel.bind(task("t2", TaskComment("Other", null, null)))
        panel.area.text = "second draft"

        assertTrue(panel.area.isEditable)
        assertTrue(panel.saveButton.isEnabled)
        gate?.countDown()
    }

    fun `test the platform time shows in the time zone of the user and keeps a text that is not a time`() {
        val vienna = ZoneId.of("Europe/Vienna")

        // WHY: newer JDKs put a narrow no-break space before AM or PM. Compare with a plain space.
        fun shown(value: String) = formatPlatformTime(value, vienna, Locale.US).replace(Regex("\\p{Zs}"), " ")

        assertEquals("Nov 8, 2026, 11:15:00 AM", shown("2026-11-08T10:15:00"))
        assertEquals("Nov 8, 2026, 11:15:00 AM", shown("2026-11-08T12:15:00+02:00"))
        assertEquals("yesterday", shown("yesterday"))
    }

    private fun settle() = repeat(10) {
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        Thread.sleep(30)
    }

    private fun task(id: String, comment: TaskComment) = GenerationTask(id, TaskStatus.SUCCESS, "GENERATE", "2026-11-08T10:00:00Z", comment)
}
