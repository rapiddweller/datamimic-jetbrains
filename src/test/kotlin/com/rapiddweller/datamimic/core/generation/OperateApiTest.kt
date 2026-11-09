// DATAMIMIC for JetBrains IDEs
// Copyright (c) 2026 Rapiddweller Asia Co., Ltd.
// SPDX-License-Identifier: MIT

package com.rapiddweller.datamimic.core.generation

import com.rapiddweller.datamimic.core.FakePlatform
import com.rapiddweller.datamimic.core.PlatformException
import com.rapiddweller.datamimic.core.PlatformHttp
import com.rapiddweller.datamimic.core.SessionExpiredException
import com.rapiddweller.datamimic.core.SessionService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.http.HttpClient

class OperateApiTest {
    private val platform = FakePlatform()
    private lateinit var client: HttpClient
    private lateinit var sessions: SessionService
    private lateinit var api: GenerationApi

    private val body = OperateRequest("xml/order.xml", NotMatchedAction.WARN, listOf(OperateRow("case-1", listOf("set(//order/id, '2')")))).body()

    @Before
    fun setUp() {
        client = HttpClient.newHttpClient()
        sessions = SessionService(client, { null }, {})
        sessions.login(platform.origin, "ada@example.com", "secret")
        api = GenerationApi(PlatformHttp(client, sessions, "operate-test"))
    }

    @After
    fun tearDown() {
        client.shutdownNow()
        platform.close()
    }

    @Test
    fun `the call posts the body to the operate route of the task and reads the resulting artifacts`() {
        val response = api.operate("p1", "generation-1", body)

        assertEquals(listOf("POST /api/v2/projects/p1/tasks/generation-1/artifacts/operate $body"), platform.operateRequests)
        assertEquals("generation-1", response.taskId)
        assertEquals("op-1", response.operateId)
        assertEquals("<order/>\n", response.artifacts.single().content)
        assertEquals("applied", response.artifacts.single().outcomes.single().status)
    }

    @Test
    fun `latest is a valid task and the platform names the task that it picked`() {
        api.operate("p1", "latest", body)

        assertEquals("POST /api/v2/projects/p1/tasks/latest/artifacts/operate $body", platform.operateRequests.single())
    }

    @Test
    fun `a row error of the platform reaches the caller with its detail`() {
        platform.operateStatus = 422
        platform.operateResponse =
            """{"detail":"Path matched nothing: //order/missing","code":"VALIDATION_ERROR","operate_id":"op-2","row_index":1,"operation_index":0,"kind":"not_matched"}"""

        val error = assertThrows(PlatformException::class.java) { api.operate("p1", "generation-1", body) }

        assertEquals(422, error.status)
        assertTrue(error.message!!, error.message!!.contains("Path matched nothing: //order/missing"))
    }

    @Test
    fun `the call uses the session of the user and holds no token`() {
        api.operate("p1", "generation-1", body)

        // The fake platform accepts the call only with the session cookie and the exact Origin.
        assertEquals(1, platform.operateRequests.size)
        sessions.logout()
        assertThrows(SessionExpiredException::class.java) { api.operate("p1", "generation-1", body) }
        assertEquals(1, platform.operateRequests.size)
    }
}
