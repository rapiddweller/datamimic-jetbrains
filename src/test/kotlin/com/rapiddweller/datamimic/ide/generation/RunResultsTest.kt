// DATAMIMIC for JetBrains IDEs
// Copyright (c) 2026 Rapiddweller Asia Co., Ltd.
// SPDX-License-Identifier: MIT

package com.rapiddweller.datamimic.ide.generation

import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.runInEdtAndWait
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import org.junit.Assert.assertTrue
import java.util.concurrent.atomic.AtomicBoolean

class RunResultsTest : BasePlatformTestCase() {
    fun `test log update retains manual scroll position and follows the end`() {
        lateinit var area: JBTextArea
        lateinit var scroll: JBScrollPane
        var middle = 0
        val retained = AtomicBoolean()
        runInEdtAndWait {
            area = JBTextArea(lines(100))
            scroll = JBScrollPane(area).apply { setSize(200, 100) }
            scroll.doLayout()
            val bar = scroll.verticalScrollBar

            bar.value = bar.maximum / 2
            middle = bar.value
            updateOutput(scroll, area, lines(110))
            javax.swing.SwingUtilities.invokeLater {
                scroll.doLayout()
                retained.set(middle == scroll.verticalScrollBar.value)
            }
        }
        PlatformTestUtil.waitWithEventsDispatching("manual scroll position was not retained", retained::get, 5)

        val followed = AtomicBoolean()
        runInEdtAndWait {
            val bar = scroll.verticalScrollBar
            bar.value = bar.maximum
            updateOutput(scroll, area, lines(120))
            javax.swing.SwingUtilities.invokeLater {
                scroll.doLayout()
                followed.set(bar.maximum - bar.visibleAmount == bar.value)
            }
        }
        PlatformTestUtil.waitWithEventsDispatching("log did not follow its end", followed::get, 5)
        assertTrue(followed.get())
    }

    private fun lines(count: Int) = (1..count).joinToString("\n") { "line $it" }
}
