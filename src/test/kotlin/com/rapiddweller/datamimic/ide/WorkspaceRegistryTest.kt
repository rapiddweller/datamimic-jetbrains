// DATAMIMIC for JetBrains IDEs
// Copyright (c) 2026 Rapiddweller Asia Co., Ltd.
// SPDX-License-Identifier: MIT

package com.rapiddweller.datamimic.ide

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceRegistryTest {
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
}
