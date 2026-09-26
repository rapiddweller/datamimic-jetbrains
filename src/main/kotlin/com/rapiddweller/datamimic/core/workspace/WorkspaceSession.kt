// DATAMIMIC for JetBrains IDEs
// Copyright (c) 2026 Rapiddweller Asia Co., Ltd.
// SPDX-License-Identifier: MIT

package com.rapiddweller.datamimic.core.workspace

import com.rapiddweller.datamimic.core.SessionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import java.net.http.HttpClient
import java.util.concurrent.ConcurrentHashMap

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
    private val scope: CoroutineScope,
    editor: LocalEditor,
    /** Diagnostics for the IDE log. */
    log: (String) -> Unit = {},
) {
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
    )

    val sync = ProjectSync(folder, projectId, workspace, bases, saver, locks, ::tree, ::loadTree, scope, editor) { updatesFlow.tryEmit(it) }

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
        stream.start()
        sync.requestSync()
    }

    /** Signing out: gives the edit locks back to the platform, then stops. Blocking; call off the UI thread. */
    fun close() {
        stream.close()
        locks.releaseAll()
    }

    /** The IDE shuts down or unloads the plugin: stops without calling the platform; its leases expire there. */
    fun dispose() {
        stream.close()
        locks.dropAll()
    }

    fun tree(): WorkspaceTree = cachedTree ?: loadTree()

    /** Reads the platform's tree and brings the folder in line with it. */
    fun refreshTree(): WorkspaceTree = loadTree().also { sync.requestSync() }

    // WHY: the sync reads the tree itself; if reading it started another pass, passes would never stop.
    private fun loadTree(): WorkspaceTree = workspace.tree(projectId).also {
        cachedTree = it
        updatesFlow.tryEmit(WorkspaceUpdate.TreeChanged)
    }

    /** Shared from a global project or otherwise not editable on the platform. */
    fun isReadOnly(path: String): Boolean = cachedTree?.entry(path)?.isReadOnlyHere ?: false

    /** An editor shows the file: take its lease early so typing is not blocked. */
    fun editorShown(path: String) {
        shown.add(path)
        locks.requestLease(path)
    }

    /** @param unsavedEdits the editor still holds edits the IDE has not saved; the upload after saving releases the lease. */
    fun editorHidden(path: String, unsavedEdits: Boolean) {
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
        if (path in shown) return
        saver.stopWaitingForVersion(path)
        if (saver.needsLease(path)) return
        scope.launch(Dispatchers.IO) { locks.release(path) }
    }

    private fun onEvent(event: WorkspaceEvent) {
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
        scope.launch(Dispatchers.IO) { runCatching { refreshTree() } }
    }
}
