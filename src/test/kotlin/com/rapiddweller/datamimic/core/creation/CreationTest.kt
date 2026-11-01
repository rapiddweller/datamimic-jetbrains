// DATAMIMIC for JetBrains IDEs
// Copyright (c) 2026 Rapiddweller Asia Co., Ltd.
// SPDX-License-Identifier: MIT

package com.rapiddweller.datamimic.core.creation

import com.rapiddweller.datamimic.core.FakePlatform
import com.rapiddweller.datamimic.core.PlatformErrorCode
import com.rapiddweller.datamimic.core.PlatformException
import com.rapiddweller.datamimic.core.PlatformHttp
import com.rapiddweller.datamimic.core.SessionService
import com.rapiddweller.datamimic.core.creation.container.templateFlows
import com.rapiddweller.datamimic.core.creation.container.uploadFlows
import com.rapiddweller.datamimic.core.creation.street.CreatedFile
import com.rapiddweller.datamimic.core.creation.street.CreatedFiles
import com.rapiddweller.datamimic.core.creation.street.CreationRequest
import com.rapiddweller.datamimic.core.creation.street.MAX_UPLOAD_BYTES
import com.rapiddweller.datamimic.core.creation.street.PlatformResult
import com.rapiddweller.datamimic.core.creation.street.UploadFailedException
import com.rapiddweller.datamimic.core.creation.street.createOnPlatform
import com.rapiddweller.datamimic.core.creation.street.existingTargets
import com.rapiddweller.datamimic.core.creation.street.pullCreated
import com.rapiddweller.datamimic.core.creation.street.nameProblem
import com.rapiddweller.datamimic.core.creation.street.sourceProblem
import com.rapiddweller.datamimic.core.creation.street.targetPaths
import com.rapiddweller.datamimic.core.workspace.FileTemplate
import com.rapiddweller.datamimic.core.workspace.LocksApi
import com.rapiddweller.datamimic.core.workspace.NoEditor
import com.rapiddweller.datamimic.core.workspace.ProjectFolder
import com.rapiddweller.datamimic.core.workspace.StreamState
import com.rapiddweller.datamimic.core.workspace.UploadFileType
import com.rapiddweller.datamimic.core.workspace.UploadTaskApi
import com.rapiddweller.datamimic.core.workspace.WorkspaceApi
import com.rapiddweller.datamimic.core.workspace.WorkspaceSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.RandomAccessFile
import java.net.http.HttpClient
import java.nio.file.Files

class CreationTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val platform = FakePlatform()
    private var stored: String? = null
    private val http = HttpClient.newHttpClient()
    private val sessions = SessionService(http, { stored }, { stored = it })
    private val transport = PlatformHttp(http, sessions, "binding-1")
    private val api = WorkspaceApi(transport)
    private val uploads = UploadTaskApi(transport)
    private val scope = CoroutineScope(SupervisorJob())
    private lateinit var folder: ProjectFolder
    private lateinit var session: WorkspaceSession

    private val customers = CreationRequest.FromTemplate("data", "customers", ".ent.csv")
    private val path = "data/customers.ent.csv"

    @Before
    fun setUp() {
        sessions.login(platform.origin, "ada@example.com", "secret")
        folder = ProjectFolder(temp.newFolder("p1").toPath())
        session = WorkspaceSession("p1", folder, api, LocksApi(transport), http, sessions, "binding-1", scope, NoEditor)
    }

    @After
    fun tearDown() {
        // WHY: the event stream runs on its own thread, which cancelling the scope does not stop.
        session.beginShutdown(release = false)
        scope.cancel()
        platform.close()
    }

    private suspend fun platformStep(request: CreationRequest, deadlineMs: Long = 10_000): PlatformResult =
        createOnPlatform(request, api, uploads, session, pollIntervalMs = 10, deadlineMs = deadlineMs)

    private suspend fun create(request: CreationRequest): CreatedFiles = when (val result = platformStep(request)) {
        is PlatformResult.Created -> pullCreated(result.paths, session)
        is PlatformResult.StillRunning -> throw AssertionError("The task still runs: $result")
    }

    private fun source(name: String, content: String = """{"id":1}"""): String =
        temp.newFile(name).toPath().also { Files.writeString(it, content) }.toString()

    private fun flow(source: String, label: String): CreationFlow = uploadFlows(source).single { it.label == label }

    @Test
    fun `a file from a template is created on the platform and pulled into the folder`() {
        val created = runBlocking { create(customers) }

        assertEquals(listOf(CreatedFile(path, folder.root.resolve(path))), created)
        assertEquals("name|surname|age\nPaul|Max|1", Files.readString(created.single().local!!))
        assertEquals(listOf(path), platform.templateCreates)
        assertTrue(platform.creates.isEmpty())
        assertFalse(platform.files.containsKey("from-template"))
    }

    @Test
    fun `an existing name fails with 412 and creates nothing locally`() {
        platform.files[path] = "old" to "etag-1"

        val failure = assertThrows(PlatformException::class.java) { runBlocking { create(customers) } }

        assertEquals(412, failure.status)
        assertEquals(PlatformErrorCode.FILE_DESTINATION_EXISTS, failure.code)
        assertFalse(Files.exists(folder.root.resolve(path)))
    }

    @Test
    fun `the file is pulled while the workspace event stream is not live`() {
        session.start()
        assertNotEquals(StreamState.LIVE, session.streamState)

        val created = runBlocking { create(customers) }

        assertEquals(folder.root.resolve(path), created.single().local)
        assertNotEquals(StreamState.LIVE, session.streamState)
    }

    @Test
    fun `the platform call and the pull can run as two steps`() {
        val result = runBlocking { platformStep(customers) }

        assertEquals(PlatformResult.Created(listOf(path)), result)
        assertFalse(Files.exists(folder.root.resolve(path)))

        val pulled = runBlocking { pullCreated(listOf(path), session) }

        assertEquals(listOf(CreatedFile(path, folder.root.resolve(path))), pulled)
    }

    @Test
    fun `a pull that fails after the create reports the file as not local and does not throw`() {
        platform.failingReads += path

        val created = runBlocking { create(customers) }

        assertEquals(listOf(CreatedFile(path, null)), created)
        assertTrue(path in platform.files)
        assertFalse(Files.exists(folder.root.resolve(path)))
    }

    @Test
    fun `a refresh that fails after the create reports the file as not local and does not throw`() {
        platform.failingTreeReads = 1

        val created = runBlocking { create(customers) }

        assertEquals(listOf(CreatedFile(path, null)), created)
        assertTrue(path in platform.files)
    }

    @Test
    fun `template flows keep the platform labels and build template requests`() {
        val flows = templateFlows(listOf(FileTemplate("Ent Csv (.ent.csv)", ".ent.csv"), FileTemplate("Scr Py (.scr.py)", ".scr.py")))

        assertEquals(listOf("Ent Csv (.ent.csv)", "Scr Py (.scr.py)"), flows.map { it.label })
        assertEquals(2, flows.map { it.id }.toSet().size)
        assertEquals(customers, flows.first().toRequest("data", "customers"))
    }

    @Test
    fun `the target path joins folder, name and extension, without a leading slash in the root`() {
        assertEquals(listOf(path), customers.targetPaths())
        assertEquals(listOf("customers.ent.csv"), customers.copy(dir = "").targetPaths())
    }

    @Test
    fun `the dialog rejects only blank names and names with a slash`() {
        listOf("", "  ", "a/b", "a\\b").forEach { assertNotNull(it, nameProblem(it)) }
        listOf("customers", "my customers", "..x", "Kunden-ä").forEach { assertNull(it, nameProblem(it)) }
    }

    @Test
    fun `the upload flows depend on the extension, in any case, and keep the labels of the web UI`() {
        val labels = { source: String -> uploadFlows(source).map { it.label } }

        assertEquals(listOf("Model from JSON", "Model and Template from JSON"), labels("/data/customers.json"))
        assertEquals(listOf("Model from JSON", "Model and Template from JSON"), labels("/data/customers.JSON"))
        assertEquals(listOf("Model from XML", "Model and Template from XML"), labels("/data/customers.xml"))
        assertEquals(listOf("Model from CSV"), labels("C:\\data\\customers.csv"))
        assertEquals(emptyList<String>(), labels("/data/customers.txt"))
        assertEquals(emptyList<String>(), labels("/data/json"))
        assertEquals(5, (uploadFlows("a.json") + uploadFlows("a.xml") + uploadFlows("a.csv")).map { it.id }.toSet().size)
    }

    @Test
    fun `the initial name is the file name without its last extension`() {
        assertEquals(listOf("customers.v2", "customers.v2"), uploadFlows("/data/customers.v2.json").map { it.initialName })
        assertEquals("orders", uploadFlows("C:\\data\\orders.csv").single().initialName)
    }

    @Test
    fun `a name that replaces the source file is refused, and the initial name avoids it`() {
        val xml = uploadFlows("/data/customers.xml")

        assertEquals(listOf("customers-model", "customers-model"), xml.map { it.initialName })
        xml.forEach { assertNotNull(it.nameProblem("customers")) }
        assertNull(xml.first().nameProblem("customers-model"))
        assertNotNull("case does not matter", uploadFlows("/data/Customers.XML").first().nameProblem("customers"))
        val template = uploadFlows("/data/orders.template.xml")
        assertEquals("orders.template-model", template.first().initialName)
        assertNull("a JSON source never meets its own model name", uploadFlows("/data/customers.json").first().nameProblem("customers"))
    }

    @Test
    fun `only the model flows of JSON and XML offer the generation mode, with mirror first`() {
        val modes = (uploadFlows("a.json") + uploadFlows("a.xml") + uploadFlows("a.csv")).filter { it.choices.isNotEmpty() }

        assertEquals(listOf("Model from JSON", "Model from XML"), modes.map { it.label })
        val mode = modes.first().choices.single()
        assertEquals(listOf("mirror", "synthetic"), mode.options.map { it.value })
        assertEquals(listOf("Preserve exact values", "Infer generators & protect PII"), mode.options.map { it.label })
        assertEquals("mirror", mode.default)
    }

    @Test
    fun `an upload request names its targets, with and without a folder, and its custom fields`() {
        val name = buildJsonObject { put("file_name", "people") }
        val mirror = buildJsonObject { put("file_name", "people"); put("generation_mode", "mirror") }
        val synthetic = buildJsonObject { put("file_name", "people"); put("generation_mode", "synthetic") }
        val upload = { dir: String, type: UploadFileType, fields: JsonObject, targets: List<String> ->
            CreationRequest.FromUpload(dir, "people", "/in/a.json", type, fields, targets)
        }

        assertEquals(
            upload("data", UploadFileType.JSON_TEMPLATE, name, listOf("data/people.xml", "data/people.template.json")),
            flow("/in/a.json", "Model and Template from JSON").toRequest("data", "people"),
        )
        assertEquals(
            upload("", UploadFileType.JSON_MODEL, mirror, listOf("people.xml")),
            flow("/in/a.json", "Model from JSON").toRequest("", "people"),
        )
        assertEquals(listOf("people.xml", "people.template.xml"), flow("a.xml", "Model and Template from XML").toRequest("", "people").targetPaths())
        assertEquals(
            upload("d", UploadFileType.XML_MODEL, synthetic, listOf("d/people.xml")).copy(source = "a.xml"),
            flow("a.xml", "Model from XML").toRequest("d", "people", mapOf("generation_mode" to "synthetic")),
        )
        assertEquals(
            upload("d", UploadFileType.CSV_MODEL, name, listOf("d/people.xml")).copy(source = "a.csv"),
            flow("a.csv", "Model from CSV").toRequest("d", "people"),
        )

        assertThrows(IllegalArgumentException::class.java) { flow("a.json", "Model from JSON").toRequest("", "p", mapOf("generation_mode" to "MIRROR")) }
    }

    @Test
    fun `the upload name rule accepts only names that the platform keeps unchanged`() {
        val rule = uploadFlows("a.json").first()
        listOf("customers", "my-data_1.v2", "a", "A9").forEach { assertNull(it, rule.nameProblem(it)) }
        listOf("", "_x", "x_", "a..b", "a--b", "a b", "Straße", ".x", "x-", "a/b").forEach { assertNotNull(it, rule.nameProblem(it)) }
        assertNull(templateFlows(listOf(FileTemplate("Ent", ".ent.csv"))).single().nameProblem("my customers"))
    }

    @Test
    fun `existing targets come from a fresh tree and list only existing files`() {
        val request = flow("a.json", "Model and Template from JSON").toRequest("data", "customers")
        platform.files["data/customers.xml"] = "<setup/>" to "etag-1"

        assertEquals(listOf("data/customers.xml"), runBlocking { existingTargets(request, api, session) })

        // WHY: tree() fills the cache and, unlike refreshTree(), starts no sync pass that refreshes it again.
        session.tree()
        platform.files["data/customers.template.json"] = "{}" to "etag-1"

        assertNull(session.tree().entry("data/customers.template.json"))
        assertEquals(listOf("data/customers.xml", "data/customers.template.json"), runBlocking { existingTargets(request, api, session) })
    }

    @Test
    fun `a source file must exist and have at most 100 MB`() {
        assertNotNull(sourceProblem(temp.root.resolve("missing.json").path))
        assertNull(sourceProblem(source("empty.json", "")))

        val large = temp.newFile("large.json")
        RandomAccessFile(large, "rw").use { it.setLength(MAX_UPLOAD_BYTES) }
        assertNull(sourceProblem(large.path))
        RandomAccessFile(large, "rw").use { it.setLength(MAX_UPLOAD_BYTES + 1) }
        assertNotNull(sourceProblem(large.path))
    }

    @Test
    fun `a model and template upload creates both files and pulls them into the folder`() {
        val input = source("orders.json")
        val request = flow(input, "Model and Template from JSON").toRequest("data", "customers")
        platform.uploadTaskPolls = 2

        val created = runBlocking { create(request) }

        val paths = listOf("data/customers.template.json", "data/customers.xml")
        assertEquals(paths.map { CreatedFile(it, folder.root.resolve(it)) }, created)
        assertEquals("<setup/>", Files.readString(folder.root.resolve("data/customers.xml")))
        val start = platform.uploadTaskStarts.single()
        assertEquals("json_generate_template", start.fileType)
        assertEquals("data", start.dir)
        assertEquals("""{"file_name":"customers"}""", start.customFields)
        assertEquals("orders.json", start.sourceName)
        assertEquals("""{"id":1}""", String(start.bytes))
        assertEquals(3, platform.uploadPolls["t1"])
    }

    @Test
    fun `a model upload sends the generation mode in lower case`() {
        val request = flow(source("orders.xml"), "Model from XML").toRequest("", "orders", mapOf("generation_mode" to "synthetic"))

        assertEquals(PlatformResult.Created(listOf("orders.xml")), runBlocking { platformStep(request) })

        val start = platform.uploadTaskStarts.single()
        assertEquals("xml_generate_model", start.fileType)
        assertEquals("", start.dir)
        assertEquals("""{"file_name":"orders","generation_mode":"synthetic"}""", start.customFields)
    }

    @Test
    fun `a failed upload task throws the text of the platform`() {
        platform.uploadTaskFailure = "Invalid JSON data: line 1"
        val request = flow(source("orders.json"), "Model from JSON").toRequest("data", "orders")

        val failure = assertThrows(UploadFailedException::class.java) { runBlocking { platformStep(request) } }

        assertEquals("Invalid JSON data: line 1", failure.message)
    }

    @Test
    fun `a run in progress for the same target is a platform error with its code`() {
        platform.uploadStartRefusal = Triple(409, "UPLOAD_GENERATE_ALREADY_RUNNING", "A run is in progress")
        val request = flow(source("orders.json"), "Model from JSON").toRequest("data", "orders")

        val failure = assertThrows(PlatformException::class.java) { runBlocking { platformStep(request) } }

        assertEquals(409, failure.status)
        assertEquals(PlatformErrorCode.UPLOAD_GENERATE_ALREADY_RUNNING, failure.code)
        assertEquals("A run is in progress", failure.detail)
    }

    @Test
    fun `an upload task that outlives the deadline is still running and nothing is pulled`() {
        // WHY: 100 polls take more than 1 s, so a deadline that has no effect ends the test with a wrong result, not a hang.
        platform.uploadTaskPolls = 100
        val request = flow(source("orders.json"), "Model from JSON").toRequest("data", "orders")

        val result = runBlocking { platformStep(request, deadlineMs = 200) }

        assertEquals(PlatformResult.StillRunning("t1"), result)
        assertTrue(platform.uploadPolls.getValue("t1") > 1)
        assertFalse(Files.exists(folder.root.resolve("data/orders.xml")))
    }

    @Test
    fun `a short outage while the upload task runs does not end the wait`() {
        platform.uploadStatusOutages = 3
        val request = flow(source("orders.json"), "Model from JSON").toRequest("data", "orders")

        val result = runBlocking { platformStep(request) }

        assertEquals(PlatformResult.Created(listOf("data/orders.xml")), result)
    }

    @Test
    fun `a long outage while the upload task runs reports that the task still runs`() {
        platform.uploadStatusOutages = Int.MAX_VALUE
        val request = flow(source("orders.json"), "Model from JSON").toRequest("data", "orders")

        val result = runBlocking { platformStep(request) }

        assertEquals(PlatformResult.StillRunning("t1"), result)
    }

    @Test
    fun `a cancel while the upload task runs propagates and stops the polling`() {
        platform.uploadTaskPolls = Int.MAX_VALUE
        val request = flow(source("orders.json"), "Model from JSON").toRequest("data", "orders")
        var returned: PlatformResult? = null
        var cancelled: CancellationException? = null

        runBlocking {
            val job = launch {
                try {
                    returned = platformStep(request, deadlineMs = 60_000)
                } catch (e: CancellationException) {
                    cancelled = e
                    throw e
                }
            }
            withTimeout(10_000) { while ((platform.uploadPolls["t1"] ?: 0) < 3) delay(10) }
            job.cancelAndJoin()
        }
        val polls = platform.uploadPolls.getValue("t1")
        Thread.sleep(100)

        assertNull(returned)
        assertNotNull(cancelled)
        assertEquals(polls, platform.uploadPolls.getValue("t1"))
    }
}
