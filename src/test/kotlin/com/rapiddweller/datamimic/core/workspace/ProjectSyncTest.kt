// DATAMIMIC for JetBrains IDEs
// Copyright (c) 2026 Rapiddweller Asia Co., Ltd.
// SPDX-License-Identifier: MIT

package com.rapiddweller.datamimic.core.workspace

import com.rapiddweller.datamimic.core.FakePlatform
import com.rapiddweller.datamimic.core.PlatformHttp
import com.rapiddweller.datamimic.core.PlatformOrigin
import com.rapiddweller.datamimic.core.PlatformProject
import com.rapiddweller.datamimic.core.SessionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.http.HttpClient
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

/** Never has unsaved edits and ignores refreshes: the editor of a sync that runs without an IDE. */
internal object NoEditor : LocalEditor {
    override fun hasUnsavedEdits(file: Path) = false

    override fun filesChanged(files: Collection<Path>) = Unit
}

class ProjectSyncTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val platform = FakePlatform()
    private var stored: String? = null
    private val http = HttpClient.newHttpClient()
    private val sessions = SessionService(http, { stored }, { stored = it })
    private val transport = PlatformHttp(http, sessions, "binding-1")
    private val scope = CoroutineScope(SupervisorJob())
    private val unsaved = mutableSetOf<Path>()
    private val updates = CopyOnWriteArrayList<WorkspaceUpdate>()
    private val editor = object : LocalEditor {
        override fun hasUnsavedEdits(file: Path) = file in unsaved

        override fun filesChanged(files: Collection<Path>) = Unit
    }
    private lateinit var folder: ProjectFolder
    private lateinit var session: WorkspaceSession

    @Before
    fun setUp() {
        sessions.login(platform.origin, "ada@example.com", "secret")
        folder = ProjectFolder(temp.newFolder("p1").toPath())
        session = WorkspaceSession("p1", folder, WorkspaceApi(transport), LocksApi(transport), http, sessions, "binding-1", scope, editor)
        // WHY: undispatched, so the collector subscribes before the test makes anything happen.
        scope.launch(start = CoroutineStart.UNDISPATCHED) { session.updates.collect(updates::add) }
    }

    @After
    fun tearDown() {
        scope.cancel()
        platform.close()
    }

    @Test
    fun `reconcile compares every path three ways`() {
        val base = FileBase("e1", "s1")
        val bases = mapOf("same" to base, "mine" to base, "theirs" to base, "both" to base, "goneHere" to base, "goneThere" to base,
            "editedGoneThere" to base, "goneBoth" to base, "global" to base)
        val local = mapOf("same" to "s1", "mine" to "s2", "theirs" to "s1", "both" to "s2", "goneThere" to "s1", "editedGoneThere" to "s2",
            "global" to "s2", "newHere" to "x", "onBoth" to "x")
        val remote = mapOf("same" to "e1", "mine" to "e1", "theirs" to "e2", "both" to "e2", "goneHere" to "e1", "global" to "e1",
            "newThere" to "e1", "onBoth" to "e1")

        assertEquals(
            mapOf(
                "mine" to SyncStep.UPLOAD,
                "theirs" to SyncStep.DOWNLOAD,
                "both" to SyncStep.CONFLICT,
                "goneHere" to SyncStep.MISSING_LOCALLY,
                "goneThere" to SyncStep.DELETE_LOCAL,
                "editedGoneThere" to SyncStep.CONFLICT,
                "goneBoth" to SyncStep.FORGET,
                "global" to SyncStep.DOWNLOAD,
                "newHere" to SyncStep.UPLOAD,
                "newThere" to SyncStep.DOWNLOAD,
                "onBoth" to SyncStep.COMPARE,
            ),
            reconcile(bases, local, remote, readOnly = setOf("global")),
        )
    }

    @Test
    fun `the first sync downloads the project and never writes outside the folder`() {
        platform.files["data/people.csv"] = "name\nAda" to "etag-1"
        platform.files["../escape.xml"] = "<x/>" to "etag-1"
        platform.files["Case.xml"] = "<a/>" to "etag-1"
        platform.files["case.xml"] = "<b/>" to "etag-1"

        syncNow()

        assertEquals("<setup/>", read(PATH))
        assertEquals("name\nAda", read("data/people.csv"))
        assertEquals(FileBase("etag-1", sha256("<setup/>".toByteArray())), session.bases.get(PATH))
        assertFalse(Files.exists(folder.root.parent.resolve("escape.xml")))
        assertFalse("names that clash by case are skipped", Files.exists(folder.root.resolve("Case.xml")))
        val problem = awaitUpdate<WorkspaceUpdate.SyncProblem>().message
        assertTrue(problem.contains("../escape.xml") && problem.contains("Case.xml") && problem.contains("case.xml"))
        assertEquals(0, platform.uploadsReceived)
    }

    @Test
    fun `a platform path through a link inside the folder is not written`() {
        val outside = temp.newFolder("outside").toPath()
        Files.createSymbolicLink(folder.root.resolve("linked"), outside)
        platform.files["linked/x.xml"] = "<x/>" to "etag-1"

        syncNow()

        assertFalse(Files.exists(outside.resolve("x.xml")))
    }

    @Test
    fun `the echo of a download is not uploaded, but an edit made outside the IDE is`() {
        syncNow()
        session.sync.localChanged(PATH)
        awaitUploads()
        assertEquals(0, platform.uploadsReceived)

        write(PATH, "<setup agent='1'/>")
        session.sync.localChanged(PATH)
        awaitUploads()

        assertEquals("<setup agent='1'/>" to "etag-2", platform.files[PATH])
        assertEquals(FileBase("etag-2", sha256("<setup agent='1'/>".toByteArray())), session.bases.get(PATH))
    }

    @Test
    fun `a new local file is created on the platform`() {
        syncNow()
        write("model/new.xml", "<setup new='1'/>")

        syncNow()
        awaitUploads()

        assertEquals(listOf("model/new.xml"), platform.creates)
        assertEquals("<setup new='1'/>", platform.files.getValue("model/new.xml").first)
        assertEquals("etag-1", session.bases.get("model/new.xml")?.etag)
    }

    @Test
    fun `a file deleted outside the IDE is reported and restored on request, never deleted on the platform`() {
        syncNow()
        Files.delete(file(PATH))

        syncNow()

        assertEquals(WorkspaceUpdate.MissingLocally(setOf(PATH)), awaitUpdate<WorkspaceUpdate.MissingLocally> { it.paths.isNotEmpty() })
        assertTrue(PATH in platform.files)
        assertEquals(emptyList<String>(), platform.deletes)

        runBlocking { session.sync.restoreMissing(setOf(PATH)) }
        assertEquals("<setup/>", read(PATH))
    }

    @Test
    fun `a delete in the IDE deletes on the platform and gives back the lease`() {
        syncNow()
        Files.delete(file(PATH))

        session.sync.deleted(PATH)
        // WHY: the base is dropped after the platform answered, a moment after the fake removed the file.
        awaitUntil { PATH !in platform.files && session.bases.get(PATH) == null }

        assertEquals(listOf(PATH), platform.deletes)
        assertNull(session.bases.get(PATH))
        awaitUntil { platform.lockOwner == null }
        assertNull(platform.lockOwner)
    }

    @Test
    fun `a delete the platform refuses restores the file with the platform's version`() {
        syncNow()
        platform.files[PATH] = "<setup theirs='1'/>" to "etag-5"
        session.refreshTree()
        Files.delete(file(PATH))

        session.sync.deleted(PATH)
        awaitUntil { Files.exists(file(PATH)) }

        assertEquals("<setup theirs='1'/>", read(PATH))
        assertTrue(PATH in platform.files)
        assertTrue(awaitUpdate<WorkspaceUpdate.SyncProblem>().message.startsWith("Could not delete $PATH"))
    }

    @Test
    fun `a folder delete in the IDE deletes each file on the platform, then the folder`() {
        platform.files["data/a.csv"] = "a" to "etag-1"
        platform.files["data/b.csv"] = "b" to "etag-1"
        syncNow()
        folder.root.resolve("data").toFile().deleteRecursively()

        session.sync.deleted("data")
        awaitUntil { "data/a.csv" !in platform.files && "data/b.csv" !in platform.files && session.bases.get("data/a.csv") == null }

        assertEquals(setOf("data/a.csv", "data/b.csv"), platform.deletes.toSet())
        assertTrue(PATH in platform.files)
        assertNull(session.bases.get("data/a.csv"))
    }

    @Test
    fun `a folder that holds a file this folder never downloaded is kept on the platform and restored here`() {
        platform.files["data/a.csv"] = "a" to "etag-1"
        syncNow()
        platform.files["data/new.csv"] = "someone else's" to "etag-1"
        folder.root.resolve("data").toFile().deleteRecursively()

        session.sync.deleted("data")
        awaitUntil { Files.exists(file("data/a.csv")) }

        assertEquals(emptyList<String>(), platform.deletes)
        assertTrue("data/new.csv" in platform.files && "data/a.csv" in platform.files)
        assertTrue(awaitUpdate<WorkspaceUpdate.SyncProblem>().message.contains("data/new.csv"))
    }

    @Test
    fun `the IDE's safe-write files are never synced`() {
        syncNow()
        write("model/datamimic.xml.tmp", "<setup half/>")
        write("model/datamimic.xml~", "<setup/>")

        syncNow()
        awaitUploads()

        assertEquals(emptyList<String>(), platform.creates)
    }

    @Test
    fun `deleting many files at once in the IDE only reports them as missing`() {
        (1..6).forEach { platform.files["data/f$it.csv"] = "x" to "etag-1" }
        syncNow()
        folder.root.resolve("data").toFile().deleteRecursively()

        session.sync.deleted("data")
        syncNow()

        assertEquals(emptyList<String>(), platform.deletes)
        assertEquals(6, awaitUpdate<WorkspaceUpdate.MissingLocally> { it.paths.isNotEmpty() }.paths.size)
    }

    @Test
    fun `a platform change is downloaded, but not over unsaved edits`() {
        syncNow()
        platform.files[PATH] = "<setup theirs='1'/>" to "etag-2"
        unsaved.add(file(PATH))

        session.refreshTree()
        syncNow()
        assertEquals("<setup/>", read(PATH))
        assertTrue(session.hasPlatformUpdate(PATH))

        unsaved.clear()
        syncNow()
        assertEquals("<setup theirs='1'/>", read(PATH))
        assertEquals("etag-2", session.bases.get(PATH)?.etag)
    }

    @Test
    fun `a failed download write keeps the previous base`() {
        syncNow()
        val previousBase = checkNotNull(session.bases.get(PATH))
        platform.files[PATH] = "<setup theirs='1'/>" to "etag-2"
        val parent = file(PATH).parent
        assertTrue(parent.toFile().setWritable(false))
        assumeFalse("filesystem must enforce a non-writable directory", Files.isWritable(parent))

        try {
            session.refreshTree()
            syncNow()
        } finally {
            file(PATH).parent.toFile().setWritable(true)
        }

        assertEquals(previousBase, session.bases.get(PATH))
        assertEquals("<setup/>", read(PATH))
    }

    @Test
    fun `a file changed on both sides is a conflict until the user keeps one version`() {
        syncNow()
        write(PATH, "<setup mine='1'/>")
        platform.files[PATH] = "<setup theirs='1'/>" to "etag-2"
        session.refreshTree()

        syncNow()
        assertEquals(FileNotice.Conflict(deletedOnPlatform = false), session.notice(PATH))
        assertEquals(0, platform.uploadsReceived)

        runBlocking { session.sync.keepLocalVersion(PATH) }
        awaitUntil { platform.files.getValue(PATH).first == "<setup mine='1'/>" }
        awaitUploads()

        assertEquals("<setup mine='1'/>" to "etag-3", platform.files[PATH])
        assertFalse(session.notice(PATH) is FileNotice.Conflict)
    }

    @Test
    fun `using the platform version of a conflict replaces the local file`() {
        syncNow()
        write(PATH, "<setup mine='1'/>")
        platform.files[PATH] = "<setup theirs='1'/>" to "etag-2"
        session.refreshTree()
        syncNow()

        runBlocking { session.sync.usePlatformVersion(PATH) }

        assertEquals("<setup theirs='1'/>", read(PATH))
        assertFalse(session.notice(PATH) is FileNotice.Conflict)
        assertEquals(0, platform.uploadsReceived)
    }

    @Test
    fun `a file created on both sides with different content is a conflict`() {
        syncNow()
        write("model/new.xml", "<setup mine='1'/>")
        platform.files["model/new.xml"] = "<setup theirs='1'/>" to "etag-1"
        session.refreshTree()

        syncNow()

        assertTrue(session.sync.isConflict("model/new.xml"))
        assertEquals(emptyList<String>(), platform.creates)
    }

    @Test
    fun `a move in the IDE moves on the platform, and a refused one is undone locally`() {
        syncNow()
        Files.move(file(PATH), file("model/renamed.xml"))

        session.sync.moved(PATH, "model/renamed.xml")
        awaitUntil { "model/renamed.xml" in platform.files }
        awaitUntil { session.bases.get("model/renamed.xml") != null }
        assertEquals(listOf(PATH to "model/renamed.xml"), platform.moves)
        assertNull(session.bases.get(PATH))

        platform.lockOwner = "another-client"
        Files.move(file("model/renamed.xml"), file("model/again.xml"))
        session.sync.moved("model/renamed.xml", "model/again.xml")
        awaitUntil { Files.exists(file("model/renamed.xml")) }

        assertFalse(Files.exists(file("model/again.xml")))
        assertTrue("model/renamed.xml" in platform.files)
        assertTrue(awaitUpdate<WorkspaceUpdate.SyncProblem>().message.startsWith("Could not move"))
    }

    @Test
    fun `a read-only platform file is kept read-only and restored instead of uploaded`() {
        platform.readOnly += PATH
        syncNow()
        assertFalse(Files.isWritable(file(PATH)))

        file(PATH).toFile().setWritable(true)
        write(PATH, "<setup mine='1'/>")
        session.sync.localChanged(PATH)
        syncNow()
        awaitUploads()

        assertEquals("<setup/>", read(PATH))
        assertEquals(0, platform.uploadsReceived)
    }

    @Test
    fun `each platform gets its own folder and a taken short name falls back to the full id`() {
        val home = temp.newFolder("home").toPath()
        val project = PlatformProject("0123456789abcdef", "Customer Data: v2")
        val origin = PlatformOrigin("https://platform.example.com:8443")

        val first = ProjectFolder.locationFor(home, origin, project)
        assertEquals(home.resolve("platform.example.com_8443/Customer Data_ v2-01234567"), first)

        ProjectFolder(first).writeIdentity(FolderIdentity(origin, "01234567-other", "Other"))
        assertEquals(home.resolve("platform.example.com_8443/Customer Data_ v2-0123456789abcdef"), ProjectFolder.locationFor(home, origin, project))
    }

    @Test
    fun `occupied candidate folders are not reused or relabeled`() {
        val home = temp.newFolder("home").toPath()
        val origin = PlatformOrigin("https://platform.example.com:8443")
        val project = PlatformProject("0123456789abcdef", "Customer Data: v2")
        val platformDir = home.resolve("platform.example.com_8443")
        val short = platformDir.resolve("Customer Data_ v2-01234567")
        val full = platformDir.resolve("Customer Data_ v2-0123456789abcdef")
        ProjectFolder(short).writeIdentity(FolderIdentity(origin, "other-project", "Other"))
        Files.writeString(short.resolve("draft.xml"), "short edit")
        Files.createDirectories(full.resolve(ProjectFolder.META_DIR))
        Files.writeString(full.resolve(ProjectFolder.META_DIR).resolve("workspace.json"), "not valid json")
        Files.writeString(full.resolve("draft.xml"), "full edit")

        assertThrows(IllegalStateException::class.java) { ProjectFolder.locationFor(home, origin, project) }

        assertEquals("short edit", Files.readString(short.resolve("draft.xml")))
        assertEquals("full edit", Files.readString(full.resolve("draft.xml")))
        assertEquals("other-project", ProjectFolder(short).identity()?.projectId)
        assertNull(ProjectFolder(full).identity())
    }

    @Test
    fun `a platform rename keeps the existing project folder`() {
        val home = temp.newFolder("home").toPath()
        val origin = PlatformOrigin("https://platform.example.com:8443")
        val original = PlatformProject("0123456789abcdef", "Before Rename")
        val folder = ProjectFolder.locationFor(home, origin, original)
        ProjectFolder(folder).writeIdentity(FolderIdentity(origin, original.id, original.name))
        Files.writeString(folder.resolve("draft.xml"), "not uploaded")

        assertEquals(folder, ProjectFolder.locationFor(home, origin, original.copy(name = "After Rename")))
        assertEquals("not uploaded", Files.readString(folder.resolve("draft.xml")))
    }

    @Test
    fun `duplicate project identities stop without changing candidate folders`() {
        val home = temp.newFolder("home").toPath()
        val origin = PlatformOrigin("https://platform.example.com:8443")
        val project = PlatformProject("0123456789abcdef", "Renamed")
        val platformDir = home.resolve("platform.example.com_8443")
        ProjectFolder(platformDir.resolve("a-old-name")).writeIdentity(FolderIdentity(origin, project.id, "Older"))
        ProjectFolder(platformDir.resolve("z-old-name")).writeIdentity(FolderIdentity(origin, project.id, "Other"))
        val short = platformDir.resolve("Renamed-01234567")
        val full = platformDir.resolve("Renamed-0123456789abcdef")
        ProjectFolder(short).writeIdentity(FolderIdentity(origin, "other", "Other"))
        ProjectFolder(full).writeIdentity(FolderIdentity(origin, "another", "Another"))
        Files.writeString(short.resolve("draft.xml"), "short edit")
        Files.writeString(full.resolve("draft.xml"), "full edit")

        assertThrows(IllegalStateException::class.java) { ProjectFolder.locationFor(home, origin, project) }
        assertEquals("short edit", Files.readString(short.resolve("draft.xml")))
        assertEquals("full edit", Files.readString(full.resolve("draft.xml")))
    }

    @Test
    fun `a symlinked folder is not reused`() {
        val home = temp.newFolder("home").toPath()
        val origin = PlatformOrigin("https://platform.example.com:8443")
        val project = PlatformProject("0123456789abcdef", "Renamed")
        val target = temp.newFolder("outside").toPath()
        ProjectFolder(target).writeIdentity(FolderIdentity(origin, project.id, "Older"))
        val platformDir = home.resolve("platform.example.com_8443")
        Files.createDirectories(platformDir)
        val link = platformDir.resolve("old-name")
        val linked = runCatching { Files.createSymbolicLink(link, target); true }.getOrDefault(false)
        assumeTrue("file system supports symbolic links", linked)

        assertEquals(platformDir.resolve("Renamed-01234567"), ProjectFolder.locationFor(home, origin, project))
    }

    @Test
    fun `an identity below symlinked metadata is not reused`() {
        val home = temp.newFolder("home").toPath()
        val origin = PlatformOrigin("https://platform.example.com:8443")
        val project = PlatformProject("0123456789abcdef", "Renamed")
        val outside = temp.newFolder("outside").toPath()
        ProjectFolder(outside).writeIdentity(FolderIdentity(origin, project.id, "Older"))
        val platformDir = home.resolve("platform.example.com_8443")
        val folder = platformDir.resolve("old-name")
        Files.createDirectories(folder)
        val linked = runCatching { Files.createSymbolicLink(folder.resolve(ProjectFolder.META_DIR), outside.resolve(ProjectFolder.META_DIR)); true }.getOrDefault(false)
        assumeTrue("file system supports symbolic links", linked)

        assertNull(ProjectFolder(folder).identity())
        assertFalse(ProjectFolder(folder).isProjectFolder())
        assertEquals(platformDir.resolve("Renamed-01234567"), ProjectFolder.locationFor(home, origin, project))
    }

    @Test
    fun `a symlinked identity file is not reused`() {
        val home = temp.newFolder("home").toPath()
        val origin = PlatformOrigin("https://platform.example.com:8443")
        val project = PlatformProject("0123456789abcdef", "Renamed")
        val outside = temp.newFolder("outside").toPath()
        ProjectFolder(outside).writeIdentity(FolderIdentity(origin, project.id, "Older"))
        val platformDir = home.resolve("platform.example.com_8443")
        val folder = platformDir.resolve("old-name")
        Files.createDirectories(folder.resolve(ProjectFolder.META_DIR))
        val linked = runCatching {
            Files.createSymbolicLink(
                folder.resolve(ProjectFolder.META_DIR).resolve("workspace.json"),
                outside.resolve(ProjectFolder.META_DIR).resolve("workspace.json"),
            )
            true
        }.getOrDefault(false)
        assumeTrue("file system supports symbolic links", linked)

        assertNull(ProjectFolder(folder).identity())
        assertFalse(ProjectFolder(folder).isProjectFolder())
        assertEquals(platformDir.resolve("Renamed-01234567"), ProjectFolder.locationFor(home, origin, project))
    }

    @Test
    fun `a symlinked platform folder is rejected`() {
        val home = temp.newFolder("home").toPath()
        val origin = PlatformOrigin("https://platform.example.com:8443")
        val project = PlatformProject("0123456789abcdef", "Renamed")
        val outside = temp.newFolder("outside").toPath()
        ProjectFolder(outside.resolve("old-name")).writeIdentity(FolderIdentity(origin, project.id, "Older"))
        val platformDir = home.resolve("platform.example.com_8443")
        val linked = runCatching { Files.createSymbolicLink(platformDir, outside); true }.getOrDefault(false)
        assumeTrue("file system supports symbolic links", linked)

        assertThrows(IllegalArgumentException::class.java) { ProjectFolder.locationFor(home, origin, project) }
    }

    private fun syncNow() = runBlocking { session.sync.syncNow() }

    private fun awaitUploads() = runBlocking { session.sync.awaitUploads() }

    private fun file(path: String): Path = folder.root.resolve(path)

    private fun read(path: String): String = Files.readString(file(path))

    private fun write(path: String, text: String) {
        Files.createDirectories(file(path).parent)
        Files.writeString(file(path), text)
    }

    private inline fun <reified T : WorkspaceUpdate> awaitUpdate(crossinline matches: (T) -> Boolean = { true }): T {
        awaitUntil { updates.filterIsInstance<T>().any(matches) }
        return updates.filterIsInstance<T>().last(matches)
    }

    private fun awaitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(10)
    }

    private companion object {
        const val PATH = "model/datamimic.xml"
    }
}
