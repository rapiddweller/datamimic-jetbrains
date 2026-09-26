// DATAMIMIC for JetBrains IDEs
// Copyright (c) 2026 Rapiddweller Asia Co., Ltd.
// SPDX-License-Identifier: MIT

package com.rapiddweller.datamimic.core.workspace

import com.rapiddweller.datamimic.core.PlatformErrorCode
import com.rapiddweller.datamimic.core.PlatformException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Whether this IDE may change a file right now, and why not. */
sealed interface WriteAccess {
    data object Allowed : WriteAccess

    /** Lock state is unknown until the live workspace stream delivered its first snapshot. */
    data object WaitingForLiveUpdates : WriteAccess

    data object Acquiring : WriteAccess

    data class LockedBy(val ownerName: String?, val relation: OwnerRelation, val generation: String) : WriteAccess
}

private val LOCK_LOSS_CODES = setOf(
    PlatformErrorCode.FILE_LOCK_REQUIRED,
    PlatformErrorCode.FILE_LOCK_OWNER_MISMATCH,
    PlatformErrorCode.FILE_LOCK_OWNED_BY_OTHER,
    PlatformErrorCode.FILE_LOCK_SUPERSEDED,
)

internal fun PlatformException.isLockLoss() = code in LOCK_LOSS_CODES

/**
 * Edit leases of one platform project: acquire before editing, renew while held, release when idle.
 * The live stream's projection decides foreign ownership; a lease is dropped only on positive evidence of loss.
 */
class LockService(
    private val projectId: String,
    private val api: LocksApi,
    private val scope: CoroutineScope,
    /** Whether an editor still needs the file; a lease that arrives after it stopped needing it is given back. */
    private val stillWanted: (path: String) -> Boolean,
    private val onAccessChanged: (path: String) -> Unit,
    private val accepting: () -> Boolean = { true },
) {
    private class Lease(var grant: LockGrant, val heartbeat: Job)

    private val leases = mutableMapOf<String, Lease>()
    private val acquiring = mutableSetOf<String>()
    private var projection: Map<String, LockProjection>? = null

    @Synchronized
    fun access(path: String): WriteAccess {
        if (path in leases) return WriteAccess.Allowed
        val locks = projection ?: return WriteAccess.WaitingForLiveUpdates
        return when (val lock = locks[path]) {
            // WHY: the platform still holds a lock of this client (e.g. from before signing in again); acquiring
            // returns that same lock, because acquire is idempotent for its owner.
            is LockProjection.Held -> if (lock.ownerRelation == OwnerRelation.THIS_CLIENT) {
                WriteAccess.Acquiring
            } else {
                WriteAccess.LockedBy(lock.ownerName, lock.ownerRelation, lock.lockGeneration)
            }
            LockProjection.Free, null -> WriteAccess.Acquiring
        }
    }

    @Synchronized
    fun generation(path: String): String? = leases[path]?.grant?.generation

    /** Starts acquiring in the background when the file is free; returns immediately. */
    fun requestLease(path: String) {
        if (!accepting()) return
        if (access(path) != WriteAccess.Acquiring) return
        synchronized(this) { if (!acquiring.add(path)) return }
        scope.launch(Dispatchers.IO) {
            try {
                lease(path)
                if (!stillWanted(path)) release(path)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The stream projection explains why; the next edit attempt retries.
            } finally {
                synchronized(this@LockService) { acquiring.remove(path) }
                onAccessChanged(path)
            }
        }
    }

    /** The current lease, acquiring it first when needed. */
    fun lease(path: String): LockGrant {
        check(accepting()) { "Workspace session is closed." }
        synchronized(this) { leases[path]?.let { return it.grant } }
        val grant = api.acquire(DocumentRef(projectId, path))
        adopt(path, grant)
        return grant
    }

    /** Takes a foreign lock the user explicitly chose to override. */
    fun takeover(path: String, expectedGeneration: String): LockGrant {
        check(accepting()) { "Workspace session is closed." }
        val grant = api.takeover(DocumentRef(projectId, path), expectedGeneration)
        adopt(path, grant)
        return grant
    }

    fun release(path: String) {
        val lease = synchronized(this) { leases.remove(path) } ?: return
        lease.heartbeat.cancel()
        onAccessChanged(path)
        try {
            api.release(DocumentRef(projectId, path), lease.grant.generation)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // A failed sign-out still lets the platform expire the lease.
        }
    }

    fun releaseAll() = synchronized(this) { leases.keys.toList() }.forEach(::release)

    fun onLocks(locks: Map<String, LockProjection>) {
        val lost = synchronized(this) {
            projection = locks
            leases.filterKeys { path ->
                val lock = locks[path]
                lock is LockProjection.Held &&
                    (lock.ownerRelation != OwnerRelation.THIS_CLIENT || lock.lockGeneration != leases.getValue(path).grant.generation)
            }.keys
        }
        lost.forEach(::drop)
        (locks.keys + lost).forEach(onAccessChanged)
    }

    /** Without a live stream, foreign locks are unknown, so nothing new may be edited. Held leases stay valid. */
    fun onStreamDown() {
        val paths = synchronized(this) {
            projection = null
            leases.keys.toList()
        }
        paths.forEach(onAccessChanged)
    }

    /** Forgets every lease without calling the platform, e.g. after the session ended and leases can no longer be renewed. */
    fun dropAll() {
        val dropped = synchronized(this) { leases.toMap().also { leases.clear() } }
        dropped.values.forEach { it.heartbeat.cancel() }
        dropped.keys.forEach(onAccessChanged)
    }

    fun drop(path: String) {
        synchronized(this) { leases.remove(path) }?.heartbeat?.cancel()
        onAccessChanged(path)
    }

    private fun adopt(path: String, grant: LockGrant) {
        synchronized(this) {
            leases.remove(path)?.heartbeat?.cancel()
            leases[path] = Lease(grant, heartbeat(path))
        }
        onAccessChanged(path)
    }

    private fun heartbeat(path: String): Job = scope.launch(Dispatchers.IO) {
        while (true) {
            val current = synchronized(this@LockService) { leases[path]?.grant } ?: return@launch
            delay(current.renewAfterSeconds * 1_000L)
            try {
                val renewed = api.heartbeat(DocumentRef(projectId, path), current.generation)
                synchronized(this@LockService) { leases[path]?.grant = renewed }
            } catch (e: PlatformException) {
                if (e.isLockLoss()) {
                    synchronized(this@LockService) { leases.remove(path) }
                    onAccessChanged(path)
                    return@launch
                }
                // Transient failure: keep the cadence; the lease outlives several missed renewals.
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Network hiccup: retry at the same cadence.
            }
        }
    }
}
