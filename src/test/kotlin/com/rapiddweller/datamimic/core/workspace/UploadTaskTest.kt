// DATAMIMIC for JetBrains IDEs
// Copyright (c) 2026 Rapiddweller Asia Co., Ltd.
// SPDX-License-Identifier: MIT

package com.rapiddweller.datamimic.core.workspace

import com.rapiddweller.datamimic.core.FakePlatform
import com.rapiddweller.datamimic.core.PlatformErrorCode
import com.rapiddweller.datamimic.core.PlatformException
import com.rapiddweller.datamimic.core.PlatformHttp
import com.rapiddweller.datamimic.core.SessionService
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.http.HttpClient

class UploadTaskTest {
    private val platform = FakePlatform()
    private var stored: String? = null
    private val http = HttpClient.newHttpClient()
    private val sessions = SessionService(http, { stored }, { stored = it })
    private val transport = PlatformHttp(http, sessions, "binding-1")
    private val uploads = UploadTaskApi(transport)
    private val fields = buildJsonObject { put("file_name", "customers"); put("generation_mode", "mirror") }

    // Not valid UTF-8, and holds CR LF, so a text round trip would change it.
    private val source = byteArrayOf(0, -1, 13, 10, 127, -128)

    @Before
    fun signIn() {
        sessions.login(platform.origin, "ada@example.com", "secret")
    }

    @After
    fun tearDown() = platform.close()

    private fun start(type: UploadFileType = UploadFileType.JSON_TEMPLATE) =
        uploads.start("p1", type, "data", "customers.json", source, fields)

    @Test
    fun `a start sends the type, folder, fields, source name and the exact bytes`() {
        assertEquals("t1", start())

        val sent = platform.uploadTaskStarts.single()
        assertEquals("json_generate_template", sent.fileType)
        assertEquals("data", sent.dir)
        assertEquals(fields.toString(), sent.customFields)
        assertEquals("customers.json", sent.sourceName)
        assertEquals(source.size, sent.size)
        assertArrayEquals(source, sent.bytes)
    }

    @Test
    fun `a task is running for the configured polls, then succeeds with its paths`() {
        platform.uploadTaskPolls = 2
        val id = start()

        assertEquals(UploadStatus.Running, uploads.status("p1", id))
        assertEquals(UploadStatus.Running, uploads.status("p1", id))
        assertEquals(
            UploadStatus.Succeeded(listOf("data/customers.template.json", "data/customers.xml")),
            uploads.status("p1", id),
        )
        assertEquals("{}" to "etag-1", platform.files["data/customers.template.json"])
        assertEquals("<setup/>" to "etag-1", platform.files["data/customers.xml"])
    }

    @Test
    fun `a failed task gives the text of the platform`() {
        platform.uploadTaskPolls = 0
        platform.uploadTaskFailure = "Invalid JSON data: \"x\""
        val id = start(UploadFileType.JSON_MODEL)

        assertEquals(UploadStatus.Failed("Invalid JSON data: \"x\""), uploads.status("p1", id))
        assertFalse(platform.files.containsKey("data/customers.xml"))
    }

    @Test
    fun `an unknown task is not found`() {
        val error = assertThrows(PlatformException::class.java) { uploads.status("p1", "t9") }

        assertEquals(404, error.status)
    }

    @Test
    fun `a refused start throws the platform error and records no task`() {
        platform.uploadStartRefusal = Triple(409, "UPLOAD_GENERATE_ALREADY_RUNNING", "A run is in progress")

        val error = assertThrows(PlatformException::class.java) { start() }

        assertEquals(409, error.status)
        assertEquals(PlatformErrorCode.UPLOAD_GENERATE_ALREADY_RUNNING, error.code)
        assertEquals("A run is in progress", error.detail)
        assertTrue(platform.uploadTaskStarts.isEmpty())
    }

    @Test
    fun `the start uses the exact route and not the file create route`() {
        start()

        assertEquals(1, platform.uploadTaskStarts.size)
        assertFalse(platform.files.containsKey("upload"))
    }

    @Test
    fun `a plain file create still works through the extended multipart body`() {
        WorkspaceApi(transport).create(DocumentRef("p1", "data/new.txt"), "hello".toByteArray())

        assertEquals("hello" to "etag-1", platform.files["data/new.txt"])
    }
}
