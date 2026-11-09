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
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.http.HttpClient

class TaskCommentTest {
    private val platform = FakePlatform()
    private lateinit var client: HttpClient
    private lateinit var api: GenerationApi

    @Before
    fun setUp() {
        client = HttpClient.newHttpClient()
        val sessions = SessionService(client, { null }, {})
        sessions.login(platform.origin, "ada@example.com", "secret")
        api = GenerationApi(PlatformHttp(client, sessions, "comment-test"))
    }

    @After
    fun tearDown() {
        client.shutdownNow()
        platform.close()
    }

    @Test
    fun `history reads the comment fields of each task`() {
        platform.generationHistoryResponse = """{
            "data":[
              {"task_id":"t1","status":"SUCCESS","comment":"Nightly baseline","comment_updated_by":"ada@example.com","comment_updated_at":"2026-11-08T10:15:00"},
              {"task_id":"t2","status":"SUCCESS","comment":null,"comment_updated_by":"bob@example.com","comment_updated_at":"2026-11-07T09:00:00.123456"},
              {"task_id":"t3","status":"RUNNING"}
            ],
            "meta":{"pagination":{"current_page":1,"total_pages":1}}
        }"""

        val tasks = api.history("p1", 1).tasks

        assertEquals(TaskComment("Nightly baseline", "ada@example.com", "2026-11-08T10:15:00"), tasks[0].comment)
        // WHY: the platform keeps the last editor and time after it removes a comment.
        assertEquals(TaskComment(null, "bob@example.com", "2026-11-07T09:00:00.123456"), tasks[1].comment)
        // WHY: Platform 4.0.1 and 4.1.0 send no comment fields.
        assertEquals(TaskComment.NONE, tasks[2].comment)
        assertNull(tasks[2].comment.text)
    }

    @Test
    fun `saving sends the trimmed text with PUT to the comment route and reads the answer`() {
        val saved = api.updateComment("p1", "generation-1", "  Baseline for release 4.2  \n")

        assertEquals(listOf("""PUT /api/v2/projects/p1/tasks/generation-1/comment {"comment":"Baseline for release 4.2"}"""), platform.taskCommentRequests)
        assertEquals(TaskComment("Baseline for release 4.2", "ada@example.com", "2026-11-08T10:15:00"), saved)
    }

    @Test
    fun `a blank text is sent as an empty comment and removes it`() {
        api.updateComment("p1", "generation-1", "Keep me")

        val removed = api.updateComment("p1", "generation-1", " \t\n ")

        assertTrue(platform.taskCommentRequests.last().endsWith("""{"comment":""}"""))
        assertNull(removed.text)
        // WHY: the platform keeps the editor and time of the removal.
        assertEquals("ada@example.com", removed.updatedBy)
    }

    @Test
    fun `a failed save raises the platform error and leaves the stored comment`() {
        api.updateComment("p1", "generation-1", "Stored")
        platform.taskCommentStatus = 404

        val error = assertThrows(PlatformException::class.java) { api.updateComment("p1", "generation-1", "Lost") }

        assertEquals(404, error.status)
        assertTrue(error.message!!.contains("The comment was not saved"))
        assertEquals("Stored", platform.taskComment)
    }

    @Test
    fun `the platform refuses more than 500 characters and its error reaches the caller`() {
        val error = assertThrows(PlatformException::class.java) { api.updateComment("p1", "generation-1", "x".repeat(501)) }

        assertEquals(422, error.status)
        assertTrue(error.message!!, error.message!!.contains("comment: String should have at most 500 characters"))
        assertNull(platform.taskComment)
    }
}
