// DATAMIMIC for JetBrains IDEs
// Copyright (c) 2026 Rapiddweller Asia Co., Ltd.
// SPDX-License-Identifier: MIT

package com.rapiddweller.datamimic.core.workspace

import com.rapiddweller.datamimic.core.FakePlatform
import com.rapiddweller.datamimic.core.PlatformHttp
import com.rapiddweller.datamimic.core.SessionService
import com.rapiddweller.datamimic.core.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CountDownLatch
import java.net.http.HttpClient

class WorkspaceTest {
    private val platform = FakePlatform()
    private var stored: String? = null
    private val http = HttpClient.newHttpClient()
    private val sessions = SessionService(http, { stored }, { stored = it })
    private val transport = PlatformHttp(http, sessions, "binding-1")
    private val workspace = WorkspaceApi(transport)
    private val scope = CoroutineScope(SupervisorJob())
    private val locks = LockService("p1", LocksApi(transport), scope, stillWanted = { true }) {}

    @get:Rule
    val temp = TemporaryFolder()
    private val folder by lazy { ProjectFolder(temp.newFolder("p1").toPath()) }
    private val bases by lazy { FileBases(folder) }
    private val saver by lazy {
        FileSaver(
            "p1",
            workspace,
            locks,
            bases,
            refreshedEtag = { workspace.tree("p1").entry(it)?.etag },
            scope,
            onStateChanged = {},
            onIdle = {},
        )
    }

    @Before
    fun signIn() {
        sessions.login(platform.origin, "ada@example.com", "secret")
    }

    /** A full session of project "p1" without its live stream, for the flows that span locks and uploads. */
    private val session by lazy {
        WorkspaceSession("p1", folder, workspace, LocksApi(transport), http, sessions, "binding-1", scope, NoEditor)
    }

    @After
    fun tearDown() {
        scope.cancel()
        platform.close()
    }

    @Test
    fun `reads base64 content with its version`() {
        val content = workspace.read(DocumentRef("p1", "model/datamimic.xml"))

        assertEquals("<setup/>", String(content.bytes))
        assertEquals("etag-1", content.etag)
    }

    @Test
    fun `a save uploads against the version it is based on, under the lease, and adopts the new version`() {
        bases.set(PATH, BASE)

        saver.save(PATH, "<setup a='1'/>".toByteArray())
        awaitIdle()

        assertEquals(UploadState.Synced, saver.state(PATH))
        assertEquals("<setup a='1'/>" to "etag-2", platform.files[PATH])
        assertEquals(Triple(PATH, "etag-1", "1"), platform.uploads.single())
        assertEquals(FileBase("etag-2", sha256("<setup a='1'/>".toByteArray())), bases.get(PATH))
    }

    @Test
    fun `a foreign change on the platform is never overwritten and the local bytes are kept`() {
        bases.set(PATH, BASE)
        platform.files[PATH] = "<setup other='1'/>" to "etag-9"

        saver.save(PATH, "<setup mine='1'/>".toByteArray())
        awaitIdle()

        val state = saver.state(PATH)
        assertTrue(state is UploadState.Failed && state.failure == UploadFailure.CONCURRENT_CHANGE)
        assertTrue(PATH in saver.unconfirmedPaths())
        assertEquals("<setup other='1'/>", platform.files.getValue(PATH).first)
    }

    @Test
    fun `edits saved after the session ended are kept and upload after signing in again`() {
        bases.set(PATH, BASE)
        sessions.expire(sessions.current()!!)

        saver.save(PATH, "<setup offline='1'/>".toByteArray())
        awaitIdle()
        val failed = saver.state(PATH)
        assertTrue(failed is UploadState.Failed && failed.failure == UploadFailure.SESSION_ENDED)
        assertTrue(PATH in saver.unconfirmedPaths())

        sessions.login(platform.origin, "ada@example.com", "secret")
        saver.retry(PATH)
        awaitIdle()

        assertEquals(UploadState.Synced, saver.state(PATH))
        assertEquals("<setup offline='1'/>", platform.files.getValue(PATH).first)
    }

    @Test
    fun `only transient failed uploads are retried`() {
        bases.set(PATH, BASE)
        bases.set(OTHER, FileBase("etag-old", "sha-old"))
        platform.files[OTHER] = "<other/>" to "etag-9"
        saver.save(OTHER, "<other mine='1'/>".toByteArray())
        awaitIdle()
        sessions.expire(sessions.current()!!)
        saver.save(PATH, "<setup offline='1'/>".toByteArray())
        awaitIdle()

        sessions.login(platform.origin, "ada@example.com", "secret")
        saver.retryTransientFailures()
        awaitIdle()

        assertEquals(UploadState.Synced, saver.state(PATH))
        assertEquals("<setup offline='1'/>", platform.files.getValue(PATH).first)
        val conflict = saver.state(OTHER)
        assertTrue(conflict is UploadState.Failed && conflict.failure == UploadFailure.CONCURRENT_CHANGE)
        assertEquals("<other/>", platform.files.getValue(OTHER).first)
    }

    @Test
    fun `a live recovery retries a late transient upload failure once`() {
        bases.set(PATH, BASE)
        val gate = CountDownLatch(1)
        platform.uploadGate = gate
        platform.failingUploads = 2

        saver.save(PATH, "<setup retry='1'/>".toByteArray())
        awaitUntil { platform.uploadsReceived == 1 }
        saver.retryTransientFailures()
        gate.countDown()
        awaitIdle()

        val failed = saver.state(PATH)
        assertTrue("state: $failed", failed is UploadState.Failed && failed.failure == UploadFailure.OTHER)
        assertEquals("one recovery retry", 2, platform.uploadsReceived)
    }

    @Test
    fun `a live retry requested as an upload fails is recovered`() {
        bases.set(PATH, BASE)
        platform.failingUploads = 1
        lateinit var racing: FileSaver
        racing = FileSaver(
            "p1",
            workspace,
            locks,
            bases,
            refreshedEtag = { workspace.tree("p1").entry(it)?.etag },
            scope,
            onStateChanged = { path -> if (racing.state(path) is UploadState.Failed) racing.retryTransientFailures() },
            onIdle = {},
        )

        racing.save(PATH, "<setup retry='1'/>".toByteArray())
        awaitUntil { !racing.isUploading() }

        assertEquals(UploadState.Synced, racing.state(PATH))
        assertEquals("one recovery retry", 2, platform.uploadsReceived)
    }

    @Test
    fun `a lock loss clears a pending live recovery`() {
        bases.set(PATH, BASE)
        val gate = CountDownLatch(1)
        platform.uploadGate = gate
        lateinit var lockLossSaver: FileSaver
        val lockLossLocks = LockService(
            "p1",
            LocksApi(transport),
            scope,
            stillWanted = { true },
            onAccessChanged = { path -> if (lockLossSaver.state(path) is UploadState.Failed) lockLossSaver.retryTransientFailures() },
        )
        lockLossSaver = FileSaver(
            "p1",
            workspace,
            lockLossLocks,
            bases,
            refreshedEtag = { workspace.tree("p1").entry(it)?.etag },
            scope,
            onStateChanged = {},
            onIdle = {},
        )

        lockLossSaver.save(PATH, "<setup retry='1'/>".toByteArray())
        awaitUntil { platform.uploadsReceived == 1 }
        platform.lockOwner = "another-client"
        platform.lockGeneration = "99"
        gate.countDown()
        awaitUntil { !lockLossSaver.isUploading() }

        platform.lockOwner = null
        platform.lockGeneration = null
        platform.uploadsReceived = 0
        platform.failingUploads = 1
        platform.uploadFailureStatus = 503
        lockLossSaver.save(PATH, "<setup retry='2'/>".toByteArray())
        awaitUntil { !lockLossSaver.isUploading() }

        val failed = lockLossSaver.state(PATH)
        assertTrue("state: $failed", failed is UploadState.Failed && failed.failure == UploadFailure.OTHER)
        assertEquals("no inherited recovery retry", 1, platform.uploadsReceived)
    }

    @Test
    fun `a save arriving while an upload runs is uploaded right after it`() {
        bases.set(PATH, BASE)
        val gate = CountDownLatch(1)
        platform.uploadGate = gate

        saver.save(PATH, "<setup v='1'/>".toByteArray())
        awaitUntil { platform.uploadsReceived == 1 }
        saver.save(PATH, "<setup v='2'/>".toByteArray())
        gate.countDown()
        awaitIdle()

        assertEquals(UploadState.Synced, saver.state(PATH))
        assertEquals("<setup v='2'/>", platform.files.getValue(PATH).first)
        assertEquals(listOf("etag-1", "etag-2"), platform.uploads.map { it.second })
    }

    @Test
    fun `a version read that fails once after a successful upload is retried, without uploading twice`() {
        bases.set(PATH, BASE)
        platform.failingTreeReads = 1

        saver.save(PATH, "<setup v='1'/>".toByteArray())
        awaitIdle()

        assertEquals(UploadState.Synced, saver.state(PATH))
        assertEquals("etag-2", bases.get(PATH)?.etag)
        assertEquals(1, platform.uploads.size)
    }

    @Test
    fun `when the new version stays unreadable, closing the editor gives back the lease and the next save cannot overwrite`() {
        session.bases.set(PATH, BASE)
        platform.failingTreeReads = 100

        session.saver.save(PATH, "<setup v='1'/>".toByteArray())
        awaitUntil { !session.saver.isUploading() }
        val failed = session.saver.state(PATH)
        assertTrue(failed is UploadState.Failed && failed.message.startsWith("Saved, but"))
        assertFalse("the bytes are on the platform", PATH in session.saver.unconfirmedPaths())
        assertEquals("the lease is kept while the version is unknown", "binding-1", platform.lockOwner)

        session.editorHidden(PATH, unsavedEdits = false)
        awaitUntil { platform.lockOwner == null }
        assertEquals(null, platform.lockOwner)

        platform.failingTreeReads = 0
        session.saver.save(PATH, "<setup v='2'/>".toByteArray())
        awaitUntil { !session.saver.isUploading() }
        val refused = session.saver.state(PATH)
        assertTrue(refused is UploadState.Failed && refused.failure == UploadFailure.CONCURRENT_CHANGE)
        assertEquals("without a known base the save may only create", listOf(PATH), platform.creates)
        assertEquals("<setup v='1'/>", platform.files.getValue(PATH).first)
    }

    @Test
    fun `a lease that arrives after its editor was hidden is given back`() {
        val gate = CountDownLatch(1)
        platform.acquireGate = gate
        session.locks.onLocks(mapOf(PATH to LockProjection.Free))

        session.editorShown(PATH)
        session.editorHidden(PATH, unsavedEdits = false)
        gate.countDown()

        awaitUntil { platform.acquireCount == 1 && platform.lockOwner == null }
        assertEquals(1, platform.acquireCount)
        assertEquals(null, platform.lockOwner)
        assertEquals(null, session.locks.generation(PATH))
    }

    @Test
    fun `this client's own lock from before signing in again is taken back instead of blocking`() {
        LocksApi(transport).acquire(DocumentRef("p1", PATH))
        val generation = platform.lockGeneration!!

        locks.onLocks(mapOf(PATH to held(OwnerRelation.THIS_CLIENT, generation)))
        assertEquals(WriteAccess.Acquiring, locks.access(PATH))

        assertEquals(generation, locks.lease(PATH).generation)
        assertEquals(WriteAccess.Allowed, locks.access(PATH))
    }

    @Test
    fun `write access follows the live lock projection`() {
        assertEquals(WriteAccess.WaitingForLiveUpdates, locks.access(PATH))

        locks.onLocks(mapOf(PATH to LockProjection.Free))
        assertEquals(WriteAccess.Acquiring, locks.access(PATH))

        locks.onLocks(mapOf(PATH to held(OwnerRelation.OTHER_USER, "7", owner = "Grace")))
        assertEquals(WriteAccess.LockedBy("Grace", OwnerRelation.OTHER_USER, "7"), locks.access(PATH))
    }

    @Test
    fun `a lease survives a stale free projection but not a foreign holder`() {
        locks.onLocks(mapOf(PATH to LockProjection.Free))
        val grant = locks.lease(PATH)
        assertEquals(WriteAccess.Allowed, locks.access(PATH))

        locks.onLocks(mapOf(PATH to LockProjection.Free))
        assertEquals("a projection older than the grant must not drop it", WriteAccess.Allowed, locks.access(PATH))

        locks.onLocks(mapOf(PATH to held(OwnerRelation.THIS_CLIENT, grant.generation)))
        assertEquals(WriteAccess.Allowed, locks.access(PATH))

        locks.onLocks(mapOf(PATH to held(OwnerRelation.OTHER_USER, "99")))
        assertEquals(WriteAccess.LockedBy(null, OwnerRelation.OTHER_USER, "99"), locks.access(PATH))
    }

    @Test
    fun `workspace events decode by type and lock state`() {
        val snapshot = json.decodeFromString<WorkspaceEvent>(
            """{"type":"snapshot","tree_revision":"r2","transport":{"keepalive_interval_seconds":5,"stale_after_seconds":15},
               "locks":{"a.xml":{"state":"free"},"b.xml":{"state":"held","owner_id":"u2","owner_name":"Grace",
               "owner_client_binding_id":"c2","owner_relation":"other_user","lock_generation":"4","lease_ttl_seconds":300,
               "renew_after_seconds":100,"was_taken_over":false}},"collaboration":{"active_client_count":2,"edit_mode":"automatic"}}""",
        )
        val expected = WorkspaceEvent.Snapshot(
            "r2",
            Transport(5, 15),
            mapOf("a.xml" to LockProjection.Free, "b.xml" to held(OwnerRelation.OTHER_USER, "4", owner = "Grace")),
        )
        assertEquals(expected, snapshot)
        assertEquals(WorkspaceEvent.Ping, json.decodeFromString<WorkspaceEvent>("""{"type":"ping"}"""))
        assertEquals(
            WorkspaceEvent.WorkspaceChanged(listOf(Change("b.xml", "a.xml"))),
            json.decodeFromString<WorkspaceEvent>(
                """{"type":"workspace_changed","mutation_id":"m","source_project_id":"p1","tree_replacement":false,
                   "changes":[{"action":"moved","kind":"file","source_project_id":"p1","path":"b.xml","previous_path":"a.xml"}]}""",
            ),
        )
    }

    @Test
    fun `document refs reject paths that escape the project`() {
        for (bad in listOf("../x", "a//b", "/abs", "a/./b", "a\\b")) {
            assertTrue(bad, runCatching { DocumentRef("p1", bad) }.isFailure)
        }
    }

    private fun awaitIdle() = awaitUntil { !saver.isUploading() }

    private fun awaitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(10)
    }

    private fun held(relation: OwnerRelation, generation: String, owner: String? = null) =
        LockProjection.Held(ownerName = owner, ownerRelation = relation, lockGeneration = generation, renewAfterSeconds = 100)

    private companion object {
        const val PATH = "model/datamimic.xml"
        const val OTHER = "model/other.xml"
        val BASE = FileBase("etag-1", sha256("<setup/>".toByteArray()))
    }
}
