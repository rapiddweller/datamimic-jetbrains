// DATAMIMIC for JetBrains IDEs
// Copyright (c) 2026 Rapiddweller Asia Co., Ltd.
// SPDX-License-Identifier: MIT

package com.rapiddweller.datamimic.core.generation

import com.rapiddweller.datamimic.core.FakePlatform
import com.rapiddweller.datamimic.core.PlatformException
import com.rapiddweller.datamimic.core.PlatformHttp
import com.rapiddweller.datamimic.core.SessionService
import com.rapiddweller.datamimic.core.json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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

    @Test
    fun `history sends only generation routes with newest first pagination and no artifacts`() {
        platform.generationHistoryResponse = """{
            "data":[
              {"task_id":"finalizing","status":"FINALIZING","name":"GENERATE","date_queued":"2026-09-27T12:00:00Z"},
              {"task_id":"new-status","status":"PAUSED"}
            ],
            "meta":{"pagination":{"current_page":2,"total_pages":4}}
        }"""

        val result = api.history("p1", 2)

        assertEquals(2, result.page)
        assertEquals(4, result.totalPages)
        assertEquals(listOf("finalizing", "new-status"), result.tasks.map { it.taskId })
        assertEquals(TaskStatus.FINALIZING, result.tasks[0].status)
        assertEquals(TaskStatus.UNKNOWN, result.tasks[1].status)
        assertEquals("GENERATE", result.tasks[0].name)
        assertEquals("2026-09-27T12:00:00Z", result.tasks[0].queuedAt)
        assertTrue(!result.tasks[0].status!!.terminal)

        val request = json.parseToJsonElement(platform.generationSearchRequests.single()).jsonObject
        val filters = request.getValue("filters").jsonObject
        assertEquals(false, filters.getValue("get_artifacts_metadata").jsonPrimitive.content.toBoolean())
        assertEquals(
            listOf(
                "datamimic.standard",
                "datamimic.infinite",
                "datamimic.timed_5min",
                "datamimic.timed_30min",
                "datamimic.timed_1hour",
                "datamimic.timed_4hour",
                "datamimic.timed_8hour",
                "datamimic.timed_24hour",
            ),
            filters.getValue("routing_keys").jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals(2, request.getValue("pagination").jsonObject.getValue("page").jsonPrimitive.content.toInt())
        assertEquals(20, request.getValue("pagination").jsonObject.getValue("per_page").jsonPrimitive.content.toInt())
        assertEquals("id", request.getValue("sorting").jsonObject.getValue("sort_by").jsonPrimitive.content)
        assertEquals("desc", request.getValue("sorting").jsonObject.getValue("sort_order").jsonPrimitive.content)
    }

    @Test
    fun `missing task row is unavailable while an unknown status remains observable`() {
        platform.generationSearchResponse = """{"data":[]}"""
        assertEquals(TaskObservation.Unavailable, api.observe("p1", "generation-1"))

        platform.generationSearchResponse = """{"data":[{"task_id":"generation-1","status":"PAUSED"}]}"""
        assertEquals(TaskObservation.Unknown, api.observe("p1", "generation-1"))
        assertEquals(TaskStatus.UNKNOWN, api.status("p1", "generation-1"))

        val statusRequest = json.parseToJsonElement(platform.generationSearchRequests.last()).jsonObject
        assertEquals(
            "generation-1",
            statusRequest.getValue("filters").jsonObject.getValue("task_id").jsonPrimitive.content,
        )
        assertEquals(
            false,
            statusRequest.getValue("filters").jsonObject.getValue("get_artifacts_metadata").jsonPrimitive.content.toBoolean(),
        )
    }
}
