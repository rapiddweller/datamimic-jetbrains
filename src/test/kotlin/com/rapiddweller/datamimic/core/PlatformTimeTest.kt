// DATAMIMIC for JetBrains IDEs
// Copyright (c) 2026 Rapiddweller Asia Co., Ltd.
// SPDX-License-Identifier: MIT

package com.rapiddweller.datamimic.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.Instant
import java.time.format.DateTimeParseException

class PlatformTimeTest {
    private val expected = Instant.parse("2026-11-08T10:15:00Z")

    @Test
    fun `a time without an offset is UTC`() {
        assertEquals(expected, parsePlatformInstant("2026-11-08T10:15:00"))
        assertEquals(expected.plusNanos(123_456_000), parsePlatformInstant("2026-11-08T10:15:00.123456"))
    }

    @Test
    fun `a time with an offset keeps its instant`() {
        assertEquals(expected, parsePlatformInstant("2026-11-08T10:15:00Z"))
        assertEquals(expected, parsePlatformInstant("2026-11-08T12:15:00+02:00"))
    }

    @Test
    fun `text that is not a time is refused`() {
        assertThrows(DateTimeParseException::class.java) { parsePlatformInstant("yesterday") }
    }
}
