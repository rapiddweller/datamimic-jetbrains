// DATAMIMIC for JetBrains IDEs
// Copyright (c) 2026 Rapiddweller Asia Co., Ltd.
// SPDX-License-Identifier: MIT

package com.rapiddweller.datamimic.core.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LatestResultTest {
    @Test
    fun `nothing is stored before the first answer`() {
        assertNull(LatestResult<String>().current)
    }

    @Test
    fun `answers that arrive in order replace each other`() {
        val result = LatestResult<String>()
        val first = result.begin()
        val second = result.begin()

        assertEquals("a", result.offer(first, "a"))
        assertEquals("b", result.offer(second, "b"))
        assertEquals("b", result.current)
    }

    @Test
    fun `a slow earlier request does not replace the answer of a later one`() {
        val result = LatestResult<String>()
        val slow = result.begin()
        val fast = result.begin()

        assertEquals("new", result.offer(fast, "new"))
        assertEquals("the caller gets the newest result", "new", result.offer(slow, "old"))
        assertEquals("new", result.current)
    }
}
