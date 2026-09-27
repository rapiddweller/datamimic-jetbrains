// DATAMIMIC for JetBrains IDEs
// Copyright (c) 2026 Rapiddweller Asia Co., Ltd.
// SPDX-License-Identifier: MIT

package com.rapiddweller.datamimic.core.generation

import com.rapiddweller.datamimic.core.FakePlatform
import com.rapiddweller.datamimic.core.PlatformException
import com.rapiddweller.datamimic.core.PlatformHttp
import com.rapiddweller.datamimic.core.SessionService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.http.HttpClient

class LiveGenerationContractTest {
    private val platform = FakePlatform()
    private lateinit var client: HttpClient
    private lateinit var api: GenerationApi

    @Before
    fun setUp() {
        client = HttpClient.newHttpClient()
        val sessions = SessionService(client, { null }, {})
        sessions.login(platform.origin, "ada@example.com", "secret")
        api = GenerationApi(PlatformHttp(client, sessions, "live-run-test"))
    }

    @After
    fun tearDown() {
        client.shutdownNow()
        platform.close()
    }

    @Test
    fun `dispatch returns the platform task id without observing the running task`() {
        val task = api.dispatch("p1", TaskType.STANDARD)

        assertEquals("generation-1", task.taskId)
        assertTrue(platform.generationRequests.single().contains("\"task_type\":\"standard\""))
        assertEquals(0, platform.generationStatusReads)
        assertEquals(0, platform.generationLogReads)
        assertEquals(0, platform.generationPreviewReads)
        assertTrue(platform.cancelledGenerationTasks.isEmpty())
    }

    @Test
    fun `status and log observation do not cancel a running task and retain log completion`() {
        platform.generationLog = "worker is running"
        platform.generationLogCompleted = true

        assertEquals(TaskStatus.RUNNING, api.status("p1", "generation-1"))
        val log = api.logs("p1", "generation-1")

        assertEquals("worker is running", log.content)
        assertTrue(log.completed)
        assertEquals(1, platform.generationStatusReads)
        assertEquals(1, platform.generationLogReads)
        assertTrue(platform.cancelledGenerationTasks.isEmpty())
    }

    @Test
    fun `stopping a run explicitly calls the platform cancel endpoint`() {
        api.stop("generation-1")

        assertEquals(listOf("generation-1"), platform.cancelledGenerationTasks)
    }

    @Test
    fun `a failed log or preview fetch remains retryable`() {
        platform.failingGenerationLogReads = 1
        platform.failingGenerationPreviewReads = 1

        assertThrows(PlatformException::class.java) { api.logs("p1", "generation-1") }
        assertThrows(PlatformException::class.java) { api.previews("p1", "generation-1") }

        assertEquals("", api.logs("p1", "generation-1").content)
        assertEquals(emptyList<PreviewContent>(), api.previews("p1", "generation-1"))
        assertEquals(2, platform.generationLogReads)
        assertEquals(2, platform.generationPreviewReads)
    }
}
