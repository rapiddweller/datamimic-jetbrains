// DATAMIMIC for JetBrains IDEs
// Copyright (c) 2026 Rapiddweller Asia Co., Ltd.
// SPDX-License-Identifier: MIT

package com.rapiddweller.datamimic.core.creation

import com.rapiddweller.datamimic.core.FakePlatform
import com.rapiddweller.datamimic.core.PlatformErrorCode
import com.rapiddweller.datamimic.core.PlatformException
import com.rapiddweller.datamimic.core.PlatformHttp
import com.rapiddweller.datamimic.core.SessionService
import com.rapiddweller.datamimic.core.creation.Choice
import com.rapiddweller.datamimic.core.creation.FileInput
import com.rapiddweller.datamimic.core.creation.TextInput
import com.rapiddweller.datamimic.core.creation.container.detectDelimiter
import com.rapiddweller.datamimic.core.creation.container.templateFlows
import com.rapiddweller.datamimic.core.creation.container.modelFlows
import com.rapiddweller.datamimic.core.creation.container.migrationFlows
import com.rapiddweller.datamimic.core.creation.street.CreatedFile
import com.rapiddweller.datamimic.core.creation.street.CreatedFiles
import com.rapiddweller.datamimic.core.creation.street.CreationRequest
import com.rapiddweller.datamimic.core.creation.street.MAX_EMBEDDED_BYTES
import com.rapiddweller.datamimic.core.creation.street.MAX_UPLOAD_BYTES
import com.rapiddweller.datamimic.core.creation.street.PlatformResult
import com.rapiddweller.datamimic.core.creation.street.UploadFailedException
import com.rapiddweller.datamimic.core.creation.street.createOnPlatform
import com.rapiddweller.datamimic.core.creation.street.embeddedProblem
import com.rapiddweller.datamimic.core.creation.street.existingTargets
import com.rapiddweller.datamimic.core.creation.street.pullCreated
import com.rapiddweller.datamimic.core.creation.street.nameProblem
import com.rapiddweller.datamimic.core.creation.street.sourceProblem
import com.rapiddweller.datamimic.core.creation.street.targetPaths
import com.rapiddweller.datamimic.core.creation.street.withEmbeddedFiles
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

    private fun flow(source: String, label: String): CreationFlow = modelFlows(source).single { it.label == label }

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
        val labels = { source: String -> modelFlows(source).map { it.label } }

        assertEquals(listOf("Model from JSON", "Model and Template from JSON"), labels("/data/customers.json"))
        assertEquals(listOf("Model from JSON", "Model and Template from JSON"), labels("/data/customers.JSON"))
        assertEquals(listOf("Model from XML", "Model and Template from XML"), labels("/data/customers.xml"))
        assertEquals(listOf("Model from CSV"), labels("C:\\data\\customers.csv"))
        assertEquals(emptyList<String>(), labels("/data/customers.txt"))
        assertEquals(emptyList<String>(), labels("/data/json"))
        assertEquals(5, (modelFlows("a.json") + modelFlows("a.xml") + modelFlows("a.csv")).map { it.id }.toSet().size)
    }

    @Test
    fun `the initial name is the file name without its last extension`() {
        assertEquals(listOf("customers.v2", "customers.v2"), modelFlows("/data/customers.v2.json").map { it.initialName })
        assertEquals("orders", modelFlows("C:\\data\\orders.csv").single().initialName)
    }

    @Test
    fun `a name that replaces the source file is refused, and the initial name avoids it`() {
        val inProject = { source: String, dir: String -> modelFlows(source, dir, source.removePrefix("/")) }
        val xml = inProject("/data/customers.xml", "data")

        assertEquals(listOf("customers-model", "customers-model"), xml.map { it.initialName })
        xml.forEach { assertNotNull(it.nameProblem("customers")) }
        assertNull(xml.first().nameProblem("customers-model"))
        assertNotNull("case does not matter", inProject("/data/Customers.XML", "data").first().nameProblem("customers"))
        val template = inProject("/data/orders.template.xml", "data")
        assertEquals("orders.template-model", template.first().initialName)
        assertNull("a JSON source never meets its own model name", inProject("/data/customers.json", "data").first().nameProblem("customers"))
    }

    @Test
    fun `a source outside the project, or in another folder, is never replaced`() {
        val outside = modelFlows("/home/me/customers.xml", "data", null)
        val otherFolder = modelFlows("/p/customers.xml", "data", "other/customers.xml")

        for (flows in listOf(outside, otherFolder)) {
            assertEquals(listOf("customers", "customers"), flows.map { it.initialName })
            flows.forEach { assertNull(it.nameProblem("customers")) }
        }
    }

    @Test
    fun `only the model flows of JSON and XML offer the generation mode, with mirror first`() {
        val modes = (modelFlows("a.json") + modelFlows("a.xml") + modelFlows("a.csv")).filter { flow -> flow.fields.any { it.id == "generation_mode" } }

        assertEquals(listOf("Model from JSON", "Model from XML"), modes.map { it.label })
        val mode = modes.first().fields.filterIsInstance<Choice>().single()
        assertEquals(listOf("mirror", "synthetic"), mode.options.map { it.value })
        assertEquals(listOf("Preserve exact values", "Infer generators & protect PII"), mode.options.map { it.label })
        assertEquals("mirror", mode.default)
    }

    @Test
    fun `the delimiter is detected from the sample of a CSV source`() {
        assertEquals('|', detectDelimiter("id|name|city\n1|Ann|Köln\n2|Bob|Bonn\n"))
        assertEquals(';', detectDelimiter("id;name\n1;Ann\n"))
        assertEquals(',', detectDelimiter("id,name\n1,Ann\n"))
        assertEquals('\t', detectDelimiter("id\tname\n1\tAnn\n"))
        assertEquals("a delimiter inside quotes does not count", ';', detectDelimiter("id;note\n1;\"a,b,c\"\n2;\"d,e\"\n"))
        assertEquals("a decimal comma does not beat the delimiter", ';', detectDelimiter("id;price\n1;2,5\n2;3,5\n"))
        assertEquals("the unterminated last line is ignored", '|', detectDelimiter("a|b\n1|2\n3|"))
        assertEquals('|', detectDelimiter("a|b"))
        assertNull(detectDelimiter(""))
        assertNull("one column", detectDelimiter("id\n1\n2\n"))
        assertNull("rows with different counts", detectDelimiter("a;b\n1;2;3\n"))
    }

    @Test
    fun `the CSV flow selects the detected delimiter and sends it, and the project default sends none`() {
        val csv = { sample: String -> modelFlows("/in/a.csv", "d", null, sample).single() }
        val delimiter = { sample: String -> csv(sample).fields.filterIsInstance<Choice>().single() }

        assertEquals("|", delimiter("a|b\n1|2\n").default)
        assertEquals("", delimiter("a\n1\n").default)
        assertEquals(listOf(";", ",", "|", "\t", ""), delimiter("a|b\n").options.map { it.value })
        assertTrue(delimiter("a|b\n").options.single { it.value == "|" }.label.endsWith("detected"))
        assertTrue(delimiter("a\n").options.single { it.value == "" }.label.contains("no delimiter detected"))

        val sent = { sample: String, picked: Map<String, String> -> (csv(sample).toRequest("d", "people", picked) as CreationRequest.FromUpload).customFields }
        assertEquals(buildJsonObject { put("file_name", "people"); put("delimiter", "|") }, sent("a|b\n", emptyMap()))
        assertEquals(buildJsonObject { put("file_name", "people"); put("delimiter", ";") }, sent("a|b\n", mapOf("delimiter" to ";")))
        assertEquals(buildJsonObject { put("file_name", "people") }, sent("a|b\n", mapOf("delimiter" to "")))
        assertEquals(buildJsonObject { put("file_name", "people") }, sent("a\n", emptyMap()))
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
        val rule = modelFlows("a.json").first()
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

    private fun migration(name: String): CreationFlow = migrationFlows("/in/$name").single()

    private fun upload(request: CreationRequest) = request as CreationRequest.FromUpload

    @Test
    fun `GWA and AMA files get a migration flow and other files get none`() {
        assertEquals(listOf("GWA migration"), migrationFlows("/in/Cases.XLSM").map { it.label })
        assertEquals(listOf("AMA migration"), migrationFlows("C:\\in\\app.ama").map { it.label })
        assertEquals(emptyList<String>(), migrationFlows("/in/a.json").map { it.label } + migrationFlows("/in/a.xlsx").map { it.label })
        assertEquals(emptyList<String>(), modelFlows("/in/a.xlsm").map { it.label } + modelFlows("/in/a.ama").map { it.label })
        assertEquals("Cases", migration("Cases.xlsm").initialName)
    }

    @Test
    fun `the GWA flow needs the schema version and sends only the texts that the user entered`() {
        val flow = migration("cases.xlsm")
        val schema = flow.fields.filterIsInstance<TextInput>().single { it.required }

        assertEquals("schemaVersion", schema.id)
        assertEquals(7, flow.fields.filterIsInstance<TextInput>().count { it.advanced })
        val request = upload(flow.toRequest("d", "people", mapOf("schemaVersion" to " 2024.1 ", "sheetName" to "Cases", "idColumn" to " ", "templateDirectory" to "")))

        assertEquals(UploadFileType.GWA_MIGRATION, request.type)
        assertEquals(
            buildJsonObject {
                put("file_name", "people")
                put("gwaConfig", buildJsonObject { put("schemaVersion", "2024.1"); put("sheetName", "Cases") })
            },
            request.customFields,
        )
        assertEquals(listOf("d/people.opctl.csv"), request.targets)
        assertEquals(emptyList<String>(), request.folders)
        assertNotNull(flow.nameProblem("a b"))
    }

    @Test
    fun `the AMA flow sends the INI content and the template folder, and names the folder it can fill`() {
        val flow = migration("app.ama")
        val ini = flow.fields.filterIsInstance<FileInput>().single()
        val folder = flow.fields.filterIsInstance<TextInput>().single()

        assertEquals(listOf("ini"), ini.extensions)
        assertEquals("template", folder.default)
        val request = upload(flow.toRequest("d", "app", mapOf("iniContentB64" to "SU5J", "templateDirectory" to "tpl")))

        assertEquals(UploadFileType.AMA_MIGRATION, request.type)
        assertEquals(
            buildJsonObject {
                put("file_name", "app")
                put("amaConfig", buildJsonObject { put("iniContentB64", "SU5J"); put("templateDirectory", "tpl") })
            },
            request.customFields,
        )
        assertEquals(listOf("d/app.xml"), request.targets)
        assertEquals(listOf("d/tpl"), request.folders)
        assertEquals(emptyList<String>(), upload(flow.toRequest("", "app", mapOf("iniContentB64" to "SU5J", "templateDirectory" to " "))).folders)
        assertEquals(listOf("tpl"), upload(flow.toRequest("", "app", mapOf("templateDirectory" to "tpl"))).folders)
    }

    @Test
    fun `a file field gives the Base64 content to the flow, and a missing or large file is a problem`() {
        val ini = Files.writeString(temp.newFile("a.ini").toPath(), "[a]").toString()
        val fields = migration("app.ama").fields

        assertEquals(mapOf("iniContentB64" to "W2Fd", "templateDirectory" to "template"), withEmbeddedFiles(fields, mapOf("iniContentB64" to ini, "templateDirectory" to "template")))
        assertEquals("a blank path stays blank, and the flow sends nothing for it", " ", withEmbeddedFiles(fields, mapOf("iniContentB64" to " "))["iniContentB64"])
        assertThrows(java.io.IOException::class.java) { withEmbeddedFiles(fields, mapOf("iniContentB64" to temp.root.resolve("missing.ini").path)) }
        assertNull(embeddedProblem(ini))
        assertNotNull(embeddedProblem(temp.root.resolve("missing.ini").path))
        val large = temp.newFile("large.ini")
        RandomAccessFile(large, "rw").use { it.setLength(MAX_EMBEDDED_BYTES + 1) }
        assertNotNull(embeddedProblem(large.path))
    }

    @Test
    fun `existing targets include the files in the folders that the task can fill`() {
        val request = upload(migration("app.ama").toRequest("d", "app", mapOf("templateDirectory" to "tpl")))
        platform.files["d/app.xml"] = "<setup/>" to "etag-1"
        platform.files["d/tpl/old.edi"] = "edi" to "etag-1"
        platform.files["d/other/keep.edi"] = "edi" to "etag-1"
        platform.files["d/tplx/keep.edi"] = "edi" to "etag-1"

        assertEquals(listOf("d/app.xml", "d/tpl/old.edi"), runBlocking { existingTargets(request, api, session) }.sorted())
    }

    @Test
    fun `an AMA migration writes the model and the templates and all of them are pulled`() {
        val request = upload(migration("app.ama").toRequest("d", "app", mapOf("iniContentB64" to "SU5J")))
        val file = source("app.ama", "archive")

        val created = runBlocking { create(request.copy(source = file)) }

        assertEquals(listOf("d/app.xml", "d/template/a.edi", "d/template/b.edi"), created.map { it.path })
        assertTrue(created.all { it.local != null })
        assertEquals("ama_migration", platform.uploadTaskStarts.single().fileType)
    }

    @Test
    fun `a GWA migration writes one opctl file`() {
        val request = upload(migration("cases.xlsm").toRequest("", "cases", mapOf("schemaVersion" to "1")))

        val created = runBlocking { create(request.copy(source = source("cases.xlsm", "workbook"))) }

        assertEquals(listOf("cases.opctl.csv"), created.map { it.path })
        assertNotNull(created.single().local)
    }
}
