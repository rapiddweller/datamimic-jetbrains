// DATAMIMIC for JetBrains IDEs
// Copyright (c) 2026 Rapiddweller Asia Co., Ltd.
// SPDX-License-Identifier: MIT

package com.rapiddweller.datamimic.core.workspace

import com.rapiddweller.datamimic.core.PlatformErrorCode
import com.rapiddweller.datamimic.core.PlatformException
import com.rapiddweller.datamimic.core.SessionExpiredException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import java.io.IOException

enum class UploadFailure {
    /** Someone changed the file on the platform after this IDE read it, or created it there first. */
    CONCURRENT_CHANGE,

    /** Another client holds or took over the edit lock. */
    LOCK_LOST,
    SESSION_ENDED,
    OTHER,
}

sealed interface UploadState {
    data object Synced : UploadState

    data object Uploading : UploadState

    data class Failed(val failure: UploadFailure, val message: String) : UploadState
}

/**
 * Uploads saved content of one platform project: an update against the file's base version under its edit lease, or a
 * create for a file the platform does not have yet. The newest bytes stay here until the platform confirmed them; a
 * failed upload keeps them for retry, never drops them silently.
 */
class FileSaver(
    private val projectId: String,
    private val api: WorkspaceApi,
    private val locks: LockService,
    private val bases: FileBases,
    private val refreshedEtag: (path: String) -> String?,
    private val scope: CoroutineScope,
    private val onStateChanged: (path: String) -> Unit,
    private val onIdle: (path: String) -> Unit,
    private val accepting: () -> Boolean = { true },
) {
    private val unconfirmed = mutableMapOf<String, ByteArray>()

    /** Uploaded, but the version it produced is not read yet: path → SHA-256 of the uploaded bytes. The lease is kept until it is. */
    private val versionUnknown = mutableMapOf<String, String>()
    private val states = mutableMapOf<String, UploadState>()
    private val running = mutableSetOf<String>()
    private val retryableFailures = mutableSetOf<String>()
    private val recoveryRequested = mutableSetOf<String>()

    @Synchronized
    fun state(path: String): UploadState = states[path] ?: UploadState.Synced

    @Synchronized
    fun unconfirmedPaths(): Set<String> = unconfirmed.keys.toSet()

    /** Releasing the lease now would let another client write before this IDE knows the version it is based on. */
    @Synchronized
    fun needsLease(path: String): Boolean = path in unconfirmed || path in versionUnknown

    @Synchronized
    fun isUploading(): Boolean = running.isNotEmpty()

    @Synchronized
    fun isUploading(path: String): Boolean = path in running

    fun save(path: String, bytes: ByteArray) {
        if (!accepting()) return
        synchronized(this) { unconfirmed[path] = bytes }
        upload(path)
    }

    fun retry(path: String) = upload(path)

    /** Uploads again only files whose failed upload may succeed after the stream comes back. */
    fun retryTransientFailures() {
        if (!accepting()) return
        val failed = synchronized(this) {
            running.forEach(recoveryRequested::add)
            retryableFailures.toList()
        }
        failed.forEach(::upload)
    }

    /**
     * The editor no longer needs the file while the version of its last upload is still unknown. Stops waiting for it,
     * so the lease can be given back; the next save then fails visibly and asks for a reload instead of guessing.
     */
    @Synchronized
    fun stopWaitingForVersion(path: String) {
        if (path !in running && path !in unconfirmed) versionUnknown.remove(path)
    }

    /** Forgets the upload state of [path]; its local file stays and is compared with the platform again. */
    fun discard(path: String) {
        synchronized(this) {
            // WHY: an upload in flight could still land after the re-read and silently replace the reloaded version.
            check(path !in running) { "The file is still being saved to the platform. Try again in a moment." }
            unconfirmed.remove(path)
            states.remove(path)
            versionUnknown.remove(path)
        }
        onStateChanged(path)
        onIdle(path)
    }

    private fun upload(path: String) {
        if (!accepting()) return
        synchronized(this) {
            if (!running.add(path)) return
            retryableFailures.remove(path)
            states[path] = UploadState.Uploading
        }
        onStateChanged(path)
        scope.launch(Dispatchers.IO) {
            var failed = false
            var recover = false
            var retryable = false
            try {
                while (true) {
                    if (synchronized(this@FileSaver) { path in versionUnknown }) adoptUploadedVersion(path)
                    val bytes = synchronized(this@FileSaver) { unconfirmed[path] } ?: break
                    put(path, bytes)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed = true
                val failure = e.failure()
                retryable = e.isRetryable()
                synchronized(this@FileSaver) {
                    states[path] = UploadState.Failed(failure, failureMessage(path, e))
                    if (retryable) retryableFailures += path else retryableFailures -= path
                }
                if (failure == UploadFailure.LOCK_LOST) locks.drop(path)
                onStateChanged(path)
            } finally {
                // WHY: decided together with leaving `running`, so a save that arrived after the loop's last check is not stranded.
                val again = synchronized(this@FileSaver) {
                    running.remove(path)
                    if (!isActive) {
                        false
                    } else if (!failed) {
                        states.remove(path)
                        retryableFailures.remove(path)
                        recoveryRequested.remove(path)
                    } else {
                        val requested = recoveryRequested.remove(path)
                        recover = retryable && requested
                    }
                    !failed && path in unconfirmed
                }
                onStateChanged(path)
                if ((recover || again) && isActive) upload(path) else if (!needsLease(path)) onIdle(path)
            }
        }
    }

    private suspend fun put(path: String, bytes: ByteArray) {
        val ref = DocumentRef(projectId, path)
        // WHY: without a base the platform has no version of this file that this IDE knows, so it may only create it.
        val base = bases.get(path)
        if (base == null) api.create(ref, bytes) else api.update(ref, bytes, base.etag, locks.lease(path).generation)
        synchronized(this) {
            // WHY: identity, not equality: a newer save that arrived during the upload must still be uploaded.
            if (unconfirmed[path] === bytes) unconfirmed.remove(path)
            versionUnknown[path] = sha256(bytes)
        }
        // WHY: the old base names a version that no longer exists; a crash now leaves no base, and the next sync compares contents.
        bases.remove(path)
        adoptUploadedVersion(path)
    }

    private suspend fun adoptUploadedVersion(path: String) {
        val next = readVersion(path) ?: throw IllegalStateException("The platform listed no version for $path after saving.")
        synchronized(this) {
            versionUnknown.remove(path)?.let { bases.set(path, FileBase(next, it)) }
        }
    }

    /** The upload response carries no ETag; while the lease is held, the refreshed tree shows this upload's version. */
    private suspend fun readVersion(path: String): String? {
        for (pause in VERSION_READ_RETRY_DELAYS_MS) {
            try {
                return refreshedEtag(path)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                delay(pause)
            }
        }
        return refreshedEtag(path)
    }

    private fun failureMessage(path: String, error: Exception): String {
        val detail = error.message ?: error.javaClass.simpleName
        val saved = synchronized(this) { path in versionUnknown && path !in unconfirmed }
        return if (saved) "Saved, but the platform's new version could not be read ($detail). Retry to continue editing." else detail
    }

    private companion object {
        val VERSION_READ_RETRY_DELAYS_MS = listOf(250L, 1_000L)
    }

    private fun Exception.isRetryable(): Boolean =
        this is SessionExpiredException || this is IOException || (this is PlatformException && status >= 500)

    private fun Exception.failure(): UploadFailure = when {
        this is SessionExpiredException -> UploadFailure.SESSION_ENDED
        this is PlatformException && (code == PlatformErrorCode.FILE_ETAG_MISMATCH || code == PlatformErrorCode.CONFLICT) -> UploadFailure.CONCURRENT_CHANGE
        this is PlatformException && isLockLoss() -> UploadFailure.LOCK_LOST
        else -> UploadFailure.OTHER
    }
}
