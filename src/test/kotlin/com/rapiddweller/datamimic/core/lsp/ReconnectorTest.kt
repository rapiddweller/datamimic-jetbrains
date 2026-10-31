// DATAMIMIC for JetBrains IDEs
// Copyright (c) 2026 Rapiddweller Asia Co., Ltd.
// SPDX-License-Identifier: MIT

package com.rapiddweller.datamimic.core.lsp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ReconnectorTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val connects = LinkedBlockingQueue<Long>()

    @After
    fun tearDown() = scope.cancel()

    private fun reconnector(vararg waitsMs: Long, onConnect: () -> Unit = {}) =
        Reconnector(scope, waitsMs.toList()) { connects.add(System.nanoTime()); onConnect() }

    @Test
    fun `every retry waits at least as long as the step for its attempt, and the last step repeats`() {
        val waits = longArrayOf(30, 60, 90)
        lateinit var reconnector: Reconnector
        val calls = AtomicInteger()
        reconnector = reconnector(*waits) { if (calls.incrementAndGet() < 5) reconnector.retry() }
        val start = System.nanoTime()
        reconnector.retry()

        val times = List(5) { connects.poll(5, TimeUnit.SECONDS)!! }

        val gaps = listOf(start).plus(times).zipWithNext { a, b -> TimeUnit.NANOSECONDS.toMillis(b - a) }
        val expected = listOf(30L, 60L, 90L, 90L, 90L)
        gaps.zip(expected).forEach { (gap, wait) -> assertTrue("gap $gap ms, wait $wait ms", gap >= wait) }
        assertTrue(reconnector.retrying)
    }

    @Test
    fun `reset starts the waits from the first step again`() {
        val reconnector = reconnector(10, 2_000)
        reconnector.retry()
        connects.poll(5, TimeUnit.SECONDS)!!
        assertTrue(reconnector.retrying)

        reconnector.reset()
        assertFalse(reconnector.retrying)
        reconnector.retry()

        assertTrue("the second retry waited for the second step", connects.poll(1, TimeUnit.SECONDS) != null)
    }

    @Test
    fun `stop cancels a retry that is waiting`() {
        val reconnector = reconnector(100)
        reconnector.retry()

        reconnector.stop()

        assertFalse(reconnector.retrying)
        assertNull(connects.poll(400, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `a new retry replaces the one that is waiting`() {
        val reconnector = reconnector(50)
        reconnector.retry()
        reconnector.retry()

        connects.poll(5, TimeUnit.SECONDS)!!

        assertNull("one connection attempt, not two", connects.poll(300, TimeUnit.MILLISECONDS))
    }
}
