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
import com.rapiddweller.datamimic.core.creation.street.CreatedFile
import com.rapiddweller.datamimic.core.creation.street.CreationRequest
import com.rapiddweller.datamimic.core.creation.street.createFile
import com.rapiddweller.datamimic.core.creation.street.nameProblem
import com.rapiddweller.datamimic.core.creation.street.targetPaths
import com.rapiddweller.datamimic.core.workspace.FileTemplate
import com.rapiddweller.datamimic.core.workspace.LocksApi
import com.rapiddweller.datamimic.core.workspace.NoEditor
import com.rapiddweller.datamimic.core.workspace.ProjectFolder
import com.rapiddweller.datamimic.core.workspace.StreamState
import com.rapiddweller.datamimic.core.workspace.WorkspaceApi
import com.rapiddweller.datamimic.core.workspace.WorkspaceSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
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

    @Test
    fun `a file from a template is created on the platform and pulled into the folder`() {
        val created = runBlocking { createFile(customers, api, session) }

        assertEquals(listOf(CreatedFile(path, folder.root.resolve(path))), created)
        assertEquals("name|surname|age\nPaul|Max|1", Files.readString(created.single().local!!))
        assertEquals(listOf(path), platform.templateCreates)
        assertTrue(platform.creates.isEmpty())
        assertFalse(platform.files.containsKey("from-template"))
    }

    @Test
    fun `an existing name fails with 412 and creates nothing locally`() {
        platform.files[path] = "old" to "etag-1"

        val failure = assertThrows(PlatformException::class.java) { runBlocking { createFile(customers, api, session) } }

        assertEquals(412, failure.status)
        assertEquals(PlatformErrorCode.FILE_DESTINATION_EXISTS, failure.code)
        assertFalse(Files.exists(folder.root.resolve(path)))
    }

    @Test
    fun `the file is pulled while the workspace event stream is not live`() {
        session.start()
        assertNotEquals(StreamState.LIVE, session.streamState)

        val created = runBlocking { createFile(customers, api, session) }

        assertEquals(folder.root.resolve(path), created.single().local)
        assertNotEquals(StreamState.LIVE, session.streamState)
    }

    @Test
    fun `a pull that fails after the create reports the file as not local and does not throw`() {
        platform.failingReads += path

        val created = runBlocking { createFile(customers, api, session) }

        assertEquals(listOf(CreatedFile(path, null)), created)
        assertTrue(path in platform.files)
        assertFalse(Files.exists(folder.root.resolve(path)))
    }

    @Test
    fun `a refresh that fails after the create reports the file as not local and does not throw`() {
        platform.failingTreeReads = 1

        val created = runBlocking { createFile(customers, api, session) }

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
}
