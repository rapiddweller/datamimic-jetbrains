// DATAMIMIC for JetBrains IDEs
// Copyright (c) 2026 Rapiddweller Asia Co., Ltd.
// SPDX-License-Identifier: MIT

package com.rapiddweller.datamimic.ide

import com.rapiddweller.datamimic.core.PlatformOrigin
import com.rapiddweller.datamimic.core.workspace.FolderIdentity
import com.rapiddweller.datamimic.core.workspace.FolderInUseException
import com.rapiddweller.datamimic.core.workspace.ProjectFolder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkspaceRegistryTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `a timeout leaves a closing workspace owned until its shared drain finishes`() = runBlocking {
        val registry = WorkspaceRegistry<Any>()
        val session = registry.getOrCreate("p1", null) { Any() }
        val drain = CompletableDeferred<Unit>()
        val finalized = CompletableDeferred<Unit>()
        val closing = checkNotNull(registry.beginShutdown("p1", { drain }, { finalized }))
        val repeated = checkNotNull(registry.beginShutdown("p1", { error("must not start a second drain") }, { error("must not start a second finalization") }))

        assertSame(session, registry.value("p1"))
        assertSame(closing, repeated)
        assertSame(closing.finalization, repeated.finalization)
        assertFalse(withTimeoutOrNull(50) { drain.await(); true } ?: false)
        assertThrows(IllegalStateException::class.java) { registry.getOrCreate("p1", null) { Any() } }

        drain.complete(Unit)
        assertFalse(finalized.isCompleted)
        finalized.complete(Unit)
        assertThrows(IllegalStateException::class.java) { registry.getOrCreate("p1", null) { Any() } }
        assertTrue(registry.remove("p1", closing))
        assertTrue(registry.getOrCreate("p1", null) { Any() } !== session)
    }

    @Test
    fun `closed admission rejects work until a successful sign in reopens it`() {
        val registry = WorkspaceRegistry<Any>()
        val first = registry.getOrCreate("p1", null) { Any() }

        registry.closeAdmission()
        assertThrows(IllegalStateException::class.java) { registry.getOrCreate("p1", null) { Any() } }
        assertThrows(IllegalStateException::class.java) { registry.getOrCreate("p2", null) { Any() } }
        registry.reopenAdmission()
        assertSame(first, registry.getOrCreate("p1", null) { Any() })

        registry.closeAdmissionPermanently()
        registry.reopenAdmission()
        assertThrows(IllegalStateException::class.java) { registry.getOrCreate("p2", null) { Any() } }
    }

    @Test
    fun `a folder claim remains held until the gated finalization removes its entry`() = runBlocking {
        val registry = WorkspaceRegistry<Any>()
        var released = false
        registry.getOrCreate("p1", null, { AutoCloseable { released = true } }) { Any() }
        val drain = CompletableDeferred<Unit>()
        val finalized = CompletableDeferred<Unit>()
        val entry = checkNotNull(registry.beginShutdown("p1", { drain }, { finalized }))

        drain.complete(Unit)
        assertFalse(released)
        finalized.complete(Unit)
        assertFalse(released)

        assertTrue(registry.remove("p1", entry))
        assertTrue(released)
    }

    @Test
    fun `a failed workspace construction releases its claim`() {
        val registry = WorkspaceRegistry<Any>()
        var released = false

        assertThrows(IllegalStateException::class.java) {
            registry.getOrCreate("p1", null, { AutoCloseable { released = true } }) { error("construction failed") }
        }

        assertTrue(released)
    }

    @Test
    fun `identity initialization runs only after the folder claim`() {
        val folder = ProjectFolder(temp.newFolder("project").toPath())
        val registry = WorkspaceRegistry<Any>()
        val origin = PlatformOrigin("https://platform.example.com")
        var initialized = false
        registry.getOrCreate("p1", origin, folder::claimForSync) {
            assertThrows(FolderInUseException::class.java) { ProjectFolder(folder.root).claimForSync() }
            folder.initializeIdentity(FolderIdentity(origin, "p1", "Project"))
            initialized = true
            Any()
        }

        assertTrue(initialized)
        assertEquals("p1", folder.identity()?.projectId)
        val done = CompletableDeferred(Unit)
        val entry = checkNotNull(registry.beginShutdown("p1", { done }, { done }))
        assertTrue(registry.remove("p1", entry))
    }
}
