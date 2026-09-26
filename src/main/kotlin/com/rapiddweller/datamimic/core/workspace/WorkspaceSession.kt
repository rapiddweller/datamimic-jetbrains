// DATAMIMIC for JetBrains IDEs
// Copyright (c) 2026 Rapiddweller Asia Co., Ltd.
// SPDX-License-Identifier: MIT

package com.rapiddweller.datamimic.core.workspace

import com.rapiddweller.datamimic.core.SessionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.net.http.HttpClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

private class WorkspaceWork(parent: CoroutineScope) {
    private val job = SupervisorJob(parent.coroutineContext[Job])
    val scope = CoroutineScope(parent.coroutineContext + job)
    private val closing = AtomicBoolean(false)

    fun accept(): Boolean = !closing.get()

    fun stop(): Boolean = closing.compareAndSet(false, true).also { if (it) job.cancel() }

    suspend fun awaitStop() = job.join()

    suspend fun <T> run(block: suspend () -> T): T {
        check(accept()) { "Workspace session is closed." }
        val task = scope.async(Dispatchers.IO) { block() }
        try {
            return task.await()
        } catch (e: kotlinx.coroutines.CancellationException) {
            task.cancel()
            throw e
        }
    }
}

sealed interface WorkspaceUpdate {
    data object TreeChanged : WorkspaceUpdate

    /** Write access or upload state of one file changed. */
    data class FileChanged(val path: String) : WorkspaceUpdate

    data class StreamChanged(val state: StreamState) : WorkspaceUpdate

    /** Files deleted locally, but not in the IDE; they stay on the platform until the user decides. Empty when none are left. */
    data class MissingLocally(val paths: Set<String>) : WorkspaceUpdate

    data class SyncProblem(val message: String) : WorkspaceUpdate
}

/** The one thing an editor of a platform file should tell the user, most urgent first. */
sealed interface FileNotice {
    /** Changed here and on the platform (or deleted there); the user picks the version to keep. */
    data class Conflict(val deletedOnPlatform: Boolean) : FileNotice

    data class UploadFailed(val failure: UploadFailure, val message: String) : FileNotice

    data class LockedBy(val ownerName: String?, val relation: OwnerRelation, val generation: String) : FileNotice

    data class LiveUpdatesDown(val state: StreamState) : FileNotice

    data object PlatformUpdated : FileNotice
}

/** Everything this IDE does with one platform project and its local [folder]: tree, live locks, edit leases, uploads and sync. */
class WorkspaceSession(
    val projectId: String,
    val folder: ProjectFolder,
    private val workspace: WorkspaceApi,
    locksApi: LocksApi,
    http: HttpClient,
    sessions: SessionService,
    clientBindingId: String,
    parentScope: CoroutineScope,
    editor: LocalEditor,
    /** Diagnostics for the IDE log. */
    log: (String) -> Unit = {},
) {
    private val work = WorkspaceWork(parentScope)
    private val scope = work.scope
    private val teardownScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var shutdown: Deferred<Unit>? = null
    private val updatesFlow = MutableSharedFlow<WorkspaceUpdate>(extraBufferCapacity = 256)
    val updates: SharedFlow<WorkspaceUpdate> = updatesFlow

    @Volatile
    private var cachedTree: WorkspaceTree? = null

    @Volatile
    var streamState: StreamState = StreamState.IDLE
        private set

    private val shown = ConcurrentHashMap.newKeySet<String>()

    val bases = FileBases(folder)

    val locks: LockService = LockService(
        projectId,
        locksApi,
        scope,
        stillWanted = { path -> path in shown || saver.needsLease(path) },
        onAccessChanged = { updatesFlow.tryEmit(WorkspaceUpdate.FileChanged(it)) },
        accepting = work::accept,
    )

    val saver: FileSaver = FileSaver(
        projectId,
        workspace,
        locks,
        bases,
        refreshedEtag = { path -> loadTree().entry(path)?.etag },
        scope,
        onStateChanged = { updatesFlow.tryEmit(WorkspaceUpdate.FileChanged(it)) },
        onIdle = ::releaseIfIdle,
        accepting = work::accept,
    )

    val sync = ProjectSync(folder, projectId, workspace, bases, saver, locks, ::tree, ::loadTree, scope, work::accept, work::run, editor) { updatesFlow.tryEmit(it) }

    private val stream = WorkspaceEventStream(
        http,
        sessions,
        clientBindingId,
        projectId,
        ::onEvent,
        onState = { state ->
            streamState = state
            if (state != StreamState.LIVE) locks.onStreamDown()
            if (state == StreamState.LIVE) saver.retryTransientFailures()
            updatesFlow.tryEmit(WorkspaceUpdate.StreamChanged(state))
        },
        log = log,
    )

    fun start() {
        if (!work.accept()) return
        stream.start()
        sync.requestSync()
    }

    /** Stops admission synchronously. The first call decides whether leases are released or allowed to expire. */
    @Synchronized
    fun beginShutdown(release: Boolean): Deferred<Unit> = shutdown ?: run {
        work.stop()
        stream.close()
        teardownScope.async {
            work.awaitStop()
            if (release) locks.releaseAll() else locks.dropAll()
        }.also { completion ->
            shutdown = completion
            completion.invokeOnCompletion { teardownScope.cancel() }
        }
    }

    suspend fun awaitShutdown(release: Boolean) = beginShutdown(release).await()

    /** Signing out: gives the edit locks back to the platform, then stops. Blocking; call off the UI thread. */
    fun close() = runBlocking { awaitShutdown(release = true) }

    /** The IDE shuts down or unloads the plugin: stops without calling the platform; its leases expire there. */
    fun dispose(): Deferred<Unit> = beginShutdown(release = false)

    fun tree(): WorkspaceTree = cachedTree ?: loadTree()

    /** Reads the platform's tree and brings the folder in line with it. */
    fun refreshTree(): WorkspaceTree = loadTree().also { sync.requestSync() }

    suspend fun takeOver(path: String, generation: String): LockGrant = work.run {
        locks.takeover(path, generation)
    }

    // WHY: the sync reads the tree itself; if reading it started another pass, passes would never stop.
    private fun loadTree(): WorkspaceTree = workspace.tree(projectId).also {
        cachedTree = it
        updatesFlow.tryEmit(WorkspaceUpdate.TreeChanged)
    }

    /** Shared from a global project or otherwise not editable on the platform. */
    fun isReadOnly(path: String): Boolean = cachedTree?.entry(path)?.isReadOnlyHere ?: false

    /** An editor shows the file: take its lease early so typing is not blocked. */
    fun editorShown(path: String) {
        if (!work.accept()) return
        shown.add(path)
        locks.requestLease(path)
    }

    /** @param unsavedEdits the editor still holds edits the IDE has not saved; the upload after saving releases the lease. */
    fun editorHidden(path: String, unsavedEdits: Boolean) {
        if (!work.accept()) return
        shown.remove(path)
        if (!unsavedEdits) releaseIfIdle(path)
    }

    /** The platform has a newer version than the local file, which the sync leaves alone while the editor has unsaved edits. */
    fun hasPlatformUpdate(path: String): Boolean {
        val latest = cachedTree?.entry(path)?.etag ?: return false
        // WHY: while an upload's own version is still being adopted, the tree already shows it; that is not someone else's change.
        return latest != bases.get(path)?.etag && !saver.needsLease(path)
    }

    fun notice(path: String): FileNotice? {
        val upload = saver.state(path)
        if (sync.isConflict(path) || (upload is UploadState.Failed && upload.failure == UploadFailure.CONCURRENT_CHANGE)) {
            return FileNotice.Conflict(deletedOnPlatform = cachedTree?.entry(path) == null)
        }
        if (upload is UploadState.Failed) return FileNotice.UploadFailed(upload.failure, upload.message)
        val access = locks.access(path)
        if (access is WriteAccess.LockedBy) return FileNotice.LockedBy(access.ownerName, access.relation, access.generation)
        if (hasPlatformUpdate(path)) return FileNotice.PlatformUpdated
        if (access == WriteAccess.WaitingForLiveUpdates) return FileNotice.LiveUpdatesDown(streamState)
        return null
    }

    private fun releaseIfIdle(path: String) {
        if (!work.accept()) return
        if (path in shown) return
        saver.stopWaitingForVersion(path)
        if (saver.needsLease(path)) return
        scope.launch(Dispatchers.IO) { locks.release(path) }
    }

    private fun onEvent(event: WorkspaceEvent) {
        if (!work.accept()) return
        when (event) {
            WorkspaceEvent.Ping -> Unit
            is WorkspaceEvent.Snapshot -> {
                locks.onLocks(event.locks)
                // WHY: editors shown before the stream was live could not request their lease yet.
                shown.forEach(locks::requestLease)
                if (event.treeRevision != cachedTree?.revision) refreshTreeInBackground()
            }
            is WorkspaceEvent.CollaborationChanged -> locks.onLocks(event.locks)
            is WorkspaceEvent.WorkspaceChanged -> refreshTreeInBackground()
        }
    }

    private fun refreshTreeInBackground() {
        if (!work.accept()) return
        scope.launch(Dispatchers.IO) {
            try {
                refreshTree()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                // The next workspace event retries the refresh.
            }
        }
    }
}
