// DATAMIMIC for JetBrains IDEs
// Copyright (c) 2026 Rapiddweller Asia Co., Ltd.
// SPDX-License-Identifier: MIT

package com.rapiddweller.datamimic.core.generation

import com.rapiddweller.datamimic.core.FakePlatform
import com.rapiddweller.datamimic.core.PlatformHttp
import com.rapiddweller.datamimic.core.SessionService
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.http.HttpClient

class GenerationRunTest {
    private val platform = FakePlatform()
    private val http = HttpClient.newHttpClient()
    private var stored: String? = null
    private val sessions = SessionService(http, { stored }, { stored = it })
    private val api = GenerationApi(PlatformHttp(http, sessions, "binding"))

    @After
    fun tearDown() = platform.close()

    @Test
    fun `refresh observes status and stops reading a completed log`() = runBlocking {
        sessions.login(platform.origin, "ada@example.com", "secret")
        platform.generationLog = "started"
        val run = GenerationRun(api, "p1", "generation-1", startedAt = 100, now = { 2_100 })

        val running = run.refresh()
        assertEquals(TaskStatus.RUNNING, running.status)
        assertEquals("started", running.log)
        assertFalse(running.logCompleted)
        assertEquals(2_000, running.elapsedMillis)

        platform.generationLog = "finished"
        platform.generationLogCompleted = true
        val terminal = run.refresh()
        assertEquals("finished", terminal.log)
        assertTrue(terminal.logCompleted)
        val reads = platform.generationLogReads
        platform.generationLog = "must not replace completed log"
        run.refresh()
        assertEquals(reads, platform.generationLogReads)
    }

    @Test
    fun `elapsed includes dispatch wait and freezes at terminal status`() = runBlocking {
        sessions.login(platform.origin, "ada@example.com", "secret")
        var clock = 2_100L
        val run = GenerationRun(api, "p1", "generation-1", startedAt = 100, now = { clock })

        val running = run.refresh()
        assertEquals(2_000, running.elapsedMillis)

        platform.generationStatus = "SUCCESS"
        clock = 3_100
        val terminal = run.refresh()
        assertEquals(3_000, terminal.elapsedMillis)

        clock = 4_100
        assertEquals(3_000, run.refresh().elapsedMillis)
    }

    @Test
    fun `close only stops observation while stop requests platform cancellation`() = runBlocking {
        sessions.login(platform.origin, "ada@example.com", "secret")
        val run = GenerationRun(api, "p1", "generation-1")

        run.close()
        assertTrue(platform.cancelledGenerationTasks.isEmpty())
        run.stop()

        assertEquals(listOf("generation-1"), platform.cancelledGenerationTasks)
        assertEquals(TaskStatus.CANCELLED, GenerationRun(api, "p1", "generation-1").refresh().status)
    }

    @Test
    fun `bound generation recovers after same origin session replacement`() = runBlocking {
        platform.sessionId = "first"
        sessions.login(platform.origin, "ada@example.com", "secret")
        val run = GenerationRun(api.boundTo(platform.origin), "p1", "generation-1")

        platform.sessionId = "replacement"
        sessions.login(platform.origin, "ada@example.com", "secret")
        val snapshot = run.refresh()

        assertEquals(TaskStatus.RUNNING, snapshot.status)
        assertEquals(1, platform.generationStatusReads)
    }

    @Test
    fun `bound generation fails closed after session switches to another origin`() = runBlocking {
        val other = FakePlatform()
        try {
            sessions.login(platform.origin, "ada@example.com", "secret")
            val run = GenerationRun(api.boundTo(platform.origin), "p1", "generation-1")

            sessions.login(other.origin, "ada@example.com", "secret")
            val snapshot = run.refresh()

            assertNotNull(snapshot.statusError)
            assertEquals(0, platform.generationStatusReads)
            assertEquals(0, other.generationStatusReads)
        } finally {
            other.close()
        }
    }

    @Test
    fun `bound generation fails closed after logout`() = runBlocking {
        sessions.login(platform.origin, "ada@example.com", "secret")
        val run = GenerationRun(api.boundTo(platform.origin), "p1", "generation-1")

        sessions.logout()
        val snapshot = run.refresh()

        assertNotNull(snapshot.statusError)
        assertEquals(0, platform.generationStatusReads)
    }

    @Test
    fun `log and preview failures remain visible for retry`() = runBlocking {
        sessions.login(platform.origin, "ada@example.com", "secret")
        platform.generationStatus = "SUCCESS"
        platform.failingGenerationLogReads = 1
        platform.failingGenerationPreviewReads = 1
        val run = GenerationRun(api, "p1", "generation-1")

        val failed = run.refresh()
        assertEquals(TaskStatus.SUCCESS, failed.status)
        assertNotNull(failed.logError)
        assertNotNull(failed.previewError)
        val retried = run.refresh()

        assertEquals(TaskStatus.SUCCESS, retried.status)
        assertNull(retried.logError)
        assertNull(retried.previewError)
    }
}
