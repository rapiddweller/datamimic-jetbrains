// DATAMIMIC for JetBrains IDEs
// Copyright (c) 2026 Rapiddweller Asia Co., Ltd.
// SPDX-License-Identifier: MIT

package com.rapiddweller.datamimic.core.workspace

import com.rapiddweller.datamimic.core.PlatformErrorCode
import com.rapiddweller.datamimic.core.PlatformException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** What one sync pass does with a path whose local file, platform file and base disagree. */
enum class SyncStep {
    DOWNLOAD,
    UPLOAD,

    /** On both sides without a known common version: equal content is adopted, anything else is a conflict. */
    COMPARE,
    DELETE_LOCAL,

    /** Deleted here, but not in the IDE: it is only deleted on the platform when the user says so. */
    MISSING_LOCALLY,
    CONFLICT,
    FORGET,
}

/**
 * Three-way comparison of every path: its [bases], the SHA-256 of its [local] file and the ETag of its [remote] file.
 * Read-only platform files always follow the platform. Paths that need nothing are left out.
 */
fun reconcile(
    bases: Map<String, FileBase>,
    local: Map<String, String>,
    remote: Map<String, String>,
    readOnly: Set<String>,
): Map<String, SyncStep> =
    (bases.keys + local.keys + remote.keys).mapNotNull { path ->
        step(bases[path], local[path], remote[path], path in readOnly)?.let { path to it }
    }.toMap()

private fun step(base: FileBase?, localSha: String?, remoteEtag: String?, readOnly: Boolean): SyncStep? {
    if (base == null) {
        return when {
            localSha == null -> if (remoteEtag == null) null else SyncStep.DOWNLOAD
            remoteEtag == null -> SyncStep.UPLOAD
            readOnly -> SyncStep.DOWNLOAD
            else -> SyncStep.COMPARE
        }
    }
    val localChanged = localSha != null && localSha != base.sha256
    val remoteChanged = remoteEtag != null && remoteEtag != base.etag
    return when {
        localSha == null && remoteEtag == null -> SyncStep.FORGET
        localSha == null -> if (readOnly) SyncStep.DOWNLOAD else SyncStep.MISSING_LOCALLY
        remoteEtag == null -> if (localChanged) SyncStep.CONFLICT else SyncStep.DELETE_LOCAL
        readOnly -> if (localChanged || remoteChanged) SyncStep.DOWNLOAD else null
        localChanged && remoteChanged -> SyncStep.CONFLICT
        localChanged -> SyncStep.UPLOAD
        remoteChanged -> SyncStep.DOWNLOAD
        else -> null
    }
}

/**
 * Keeps a [ProjectFolder] and its platform project in step; the platform stays the source of truth. Local edits upload
 * through the [FileSaver] (lease and ETag), platform changes download unless the file has local edits, and a file
 * changed on both sides becomes a conflict the user resolves. Only deletes and moves the user makes in the IDE reach
 * the platform; a file that disappears any other way is reported, never deleted there.
 */
class ProjectSync(
    private val folder: ProjectFolder,
    private val projectId: String,
    private val api: WorkspaceApi,
    private val bases: FileBases,
    private val saver: FileSaver,
    private val locks: LockService,
    private val tree: () -> WorkspaceTree,
    /** Reads the tree from the platform again, without starting a pass. */
    private val reloadTree: () -> WorkspaceTree,
    private val scope: CoroutineScope,
    private val editor: LocalEditor,
    private val onUpdate: (WorkspaceUpdate) -> Unit,
) {
    // WHY: one change at a time, so a download and an upload of the same file can never interleave.
    private val mutex = Mutex()
    private val passRequested = AtomicBoolean(false)
    private val pendingLocalChanges = AtomicInteger()

    /** Paths the user is deleting or moving in the IDE; a pass must not mistake them for changes made elsewhere. */
    private val intents = ConcurrentHashMap.newKeySet<String>()
    private val conflicts = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var missing: Set<String> = emptySet()

    @Volatile
    private var unsyncable: Set<String> = emptySet()

    fun isConflict(path: String): Boolean = path in conflicts

    fun conflicts(): Set<String> = conflicts.toSet()

    /** Compares everything soon; requests while a pass is waiting are merged into it. */
    fun requestSync() {
        if (!passRequested.compareAndSet(false, true)) return
        scope.launch(Dispatchers.IO) {
            mutex.withLock {
                passRequested.set(false)
                try {
                    pass()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Offline or signed out: the next change or reconnect starts another pass.
                }
            }
        }
    }

    /** One full pass, e.g. the first download of a project; fails when the platform cannot be reached. */
    suspend fun syncNow() = withContext(Dispatchers.IO) { mutex.withLock { pass() } }

    /** The IDE saved [path], or something else wrote it: uploads it unless it is what the platform already has. */
    fun localChanged(path: String) {
        pendingLocalChanges.incrementAndGet()
        scope.launch(Dispatchers.IO) {
            try {
                mutex.withLock {
                    if (path in conflicts || isIntended(path) || isReadOnly(path)) return@withLock
                    val file = folder.resolve(path) ?: return@withLock
                    val bytes = runCatching { Files.readAllBytes(file) }.getOrNull() ?: return@withLock
                    if (bases.get(path)?.sha256 == sha256(bytes)) return@withLock
                    saver.save(path, bytes)
                }
            } finally {
                pendingLocalChanges.decrementAndGet()
            }
        }
    }

    /** Returns once every local change reported so far is uploaded or has failed. */
    suspend fun awaitUploads() {
        while (pendingLocalChanges.get() > 0 || saver.isUploading()) delay(100)
    }

    /** The user created a folder in the IDE. */
    fun directoryCreated(path: String) = change("Could not create the folder $path on the platform") {
        if (tree().entries.none { isAtOrUnder(it.path, path) }) api.createDirectory(DocumentRef(projectId, path))
    }

    /**
     * The user deleted [path] (a file or folder) in the IDE. Each platform file is deleted against the version this
     * folder has; a folder holding anything else (never downloaded, changed there, hidden) is kept and restored here.
     * More than a few files at once are only reported as missing, so a slip never empties a project on the platform.
     */
    fun deleted(path: String) {
        intents += path
        change("Could not delete $path on the platform", after = { intents -= path }, undo = { restore(bases.all().keys.filter { isAtOrUnder(it, path) }) }) {
            val remote = reloadTree().entries.filter { isAtOrUnder(it.path, path) }
            val files = remote.filter { it.kind == EntryKind.FILE }
            if (files.size > BULK_DELETE_LIMIT) return@change
            val unknown = files.filter { bases.get(it.path)?.etag != it.etag }.map { it.path }
            check(unknown.isEmpty()) { "these files changed there or were never downloaded:\n" + unknown.joinToString("\n") }
            releaseUploads(files.map { it.path })
            for (file in files) {
                deleteFile(file.path, checkNotNull(bases.get(file.path)).etag)
                bases.remove(file.path)
            }
            val isFolder = remote.any { it.path != path || it.kind == EntryKind.DIRECTORY }
            // WHY: the folder itself carries no version; delete it only while nothing arrived in it meanwhile.
            if (isFolder && reloadTree().entries.none { isAtOrUnder(it.path, path) && it.kind == EntryKind.FILE }) {
                ignoreMissing { api.deleteDirectory(DocumentRef(projectId, path)) }
            }
            bases.removeUnder(path)
        }
    }

    /** The user moved or renamed [from] (a file or folder) to [to] in the IDE. */
    fun moved(from: String, to: String) {
        intents += from
        intents += to
        val undo = {
            val source = folder.resolve(to)
            val target = folder.resolve(from)
            if (source != null && target != null && Files.exists(source) && !Files.exists(target)) {
                Files.move(source, target)
                editor.filesChanged(listOf(source, target))
            }
        }
        change("Could not move $from on the platform, so it keeps its old name", after = { intents -= from; intents -= to }, undo = undo) {
            releaseUploads(bases.all().keys.filter { isAtOrUnder(it, from) })
            val base = bases.get(from)
            if (base != null) {
                withLease(from) { generation -> api.moveFile(DocumentRef(projectId, from), DocumentRef(projectId, to), base.etag, generation) }
            } else {
                api.moveDirectory(DocumentRef(projectId, from), DocumentRef(projectId, to))
            }
            bases.move(from, to)
        }
    }

    /** Resolves a conflict for the platform: its version replaces the local file. */
    suspend fun usePlatformVersion(path: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            saver.discard(path)
            conflicts.remove(path)
            val file = folder.resolve(path) ?: return@withLock
            if (tree().entry(path) == null) {
                bases.remove(path)
                Files.deleteIfExists(file)
            } else {
                write(path, file, api.read(DocumentRef(projectId, path)))
            }
            editor.filesChanged(listOf(file))
            onUpdate(WorkspaceUpdate.FileChanged(path))
        }
    }

    /** Resolves a conflict for the local file: it replaces the platform's version with the next upload. */
    suspend fun keepLocalVersion(path: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            saver.discard(path)
            if (tree().entry(path) == null) {
                bases.remove(path)
            } else {
                val content = api.read(DocumentRef(projectId, path))
                bases.set(path, FileBase(content.etag ?: versionOf(path), sha256(content.bytes)))
            }
            conflicts.remove(path)
            onUpdate(WorkspaceUpdate.FileChanged(path))
        }
        requestSync()
    }

    /** Brings back files that were deleted locally but not on the platform. */
    suspend fun restoreMissing(paths: Collection<String>) = withContext(Dispatchers.IO) {
        mutex.withLock { restore(paths) }
        requestSync()
    }

    /** Deletes files on the platform that the user deleted locally outside the IDE, once they confirmed it. */
    suspend fun deleteMissing(paths: Collection<String>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            for (path in paths) {
                val file = folder.resolve(path) ?: continue
                val base = bases.get(path) ?: continue
                if (Files.exists(file)) continue
                deleteFile(path, base.etag)
                bases.remove(path)
            }
            reloadTree()
        }
        requestSync()
    }

    private fun pass() {
        val remoteTree = tree()
        val remote = remoteFiles(remoteTree)
        // ponytail: hashes every file on each pass; cache by size and mtime once projects hold large data files.
        val local = folder.localFiles().mapValues { sha256(Files.readAllBytes(it.value)) }
        val steps = reconcile(bases.all(), local, remote.mapValues { it.value.etag!! }, remote.filterValues(::isReadOnly).keys)
        val touched = mutableListOf<Path>()
        val failures = mutableListOf<String>()
        val nowMissing = mutableSetOf<String>()
        for ((path, step) in steps) {
            if (isBusy(path)) continue
            val file = folder.resolve(path) ?: continue
            try {
                when (step) {
                    SyncStep.DOWNLOAD -> if (download(path, file, local[path])) touched.add(file)
                    SyncStep.UPLOAD -> saver.save(path, Files.readAllBytes(file))
                    SyncStep.COMPARE -> compare(path, local.getValue(path))
                    SyncStep.DELETE_LOCAL -> if (deleteLocal(path, file, local.getValue(path))) touched.add(file)
                    SyncStep.MISSING_LOCALLY -> nowMissing += path
                    SyncStep.CONFLICT -> markConflict(path)
                    SyncStep.FORGET -> bases.remove(path)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failures += "$path: ${e.message ?: e.javaClass.simpleName}"
            }
        }
        if (touched.isNotEmpty()) {
            editor.filesChanged(touched)
            touched.mapNotNull(folder::pathOf).forEach { onUpdate(WorkspaceUpdate.FileChanged(it)) }
        }
        if (nowMissing != missing) {
            missing = nowMissing
            onUpdate(WorkspaceUpdate.MissingLocally(nowMissing))
        }
        if (failures.isNotEmpty()) onUpdate(WorkspaceUpdate.SyncProblem("Could not sync:\n" + failures.joinToString("\n")))
    }

    /** Platform files that can live in the folder, by path. */
    private fun remoteFiles(remoteTree: WorkspaceTree): Map<String, TreeEntry> {
        val files = remoteTree.entries.filter { it.kind == EntryKind.FILE && !it.hidden && it.etag != null && ProjectFolder.isSynced(it.path) }
        // WHY: two names that differ only in case are one file on macOS and Windows; syncing either would mix them up.
        val caseTwins = files.groupBy { it.path.lowercase() }.values.filter { it.size > 1 }.flatten().map { it.path }.toSet()
        val invalid = files.filter { folder.resolve(it.path) == null }.map { it.path }.toSet()
        val skipped = caseTwins + invalid
        if (skipped != unsyncable) {
            unsyncable = skipped
            if (skipped.isNotEmpty()) {
                onUpdate(WorkspaceUpdate.SyncProblem("Not synced, because the names clash on this computer or are not valid file names:\n" + skipped.sorted().joinToString("\n")))
            }
        }
        return files.filter { it.path !in skipped }.associateBy { it.path }
    }

    /** @return false when the local file changed since the pass looked; the next pass decides again. */
    private fun download(path: String, file: Path, expectedSha: String?): Boolean {
        val content = api.read(DocumentRef(projectId, path))
        if (!isUntouched(path, file, expectedSha)) return false
        write(path, file, content)
        return true
    }

    private fun write(path: String, file: Path, content: FileContent) {
        bases.set(path, FileBase(content.etag ?: versionOf(path), sha256(content.bytes)))
        // WHY: the base is recorded first, so the IDE's echo of this write is recognized as no change.
        if (Files.exists(file)) file.toFile().setWritable(true)
        writeAtomically(file, content.bytes, folder.tempDir)
        // WHY: agents edit files directly; a read-only file tells them the platform will not take their change.
        if (isReadOnly(path)) file.toFile().setWritable(false)
    }

    private fun compare(path: String, localSha: String) {
        val content = api.read(DocumentRef(projectId, path))
        if (sha256(content.bytes) == localSha) {
            bases.set(path, FileBase(content.etag ?: versionOf(path), localSha))
        } else {
            markConflict(path)
        }
    }

    private fun deleteLocal(path: String, file: Path, expectedSha: String): Boolean {
        if (!isUntouched(path, file, expectedSha)) return false
        bases.remove(path)
        Files.deleteIfExists(file)
        pruneEmptyParents(file)
        return true
    }

    private fun restore(paths: Collection<String>) {
        val restored = paths.mapNotNull { path ->
            folder.resolve(path)?.takeIf { runCatching { tree().entry(path) != null && download(path, it, expectedSha = null) }.getOrDefault(false) }
        }
        if (restored.isNotEmpty()) editor.filesChanged(restored)
    }

    private fun markConflict(path: String) {
        if (conflicts.add(path)) onUpdate(WorkspaceUpdate.FileChanged(path))
    }

    /** Waits for running uploads of [paths] and forgets their state; the local files are what counts from now on. */
    private suspend fun releaseUploads(paths: Collection<String>) {
        withTimeoutOrNull(UPLOAD_WAIT_MS) { while (paths.any(saver::isUploading)) delay(100) }
        paths.forEach(saver::discard)
        paths.forEach(conflicts::remove)
    }

    private fun deleteFile(path: String, etag: String) = ignoreMissing {
        withLease(path) { generation -> api.deleteFile(DocumentRef(projectId, path), etag, generation) }
    }

    /** Runs a change of an existing file under its edit lease; a lease taken only for this change is given back. */
    private fun withLease(path: String, change: (generation: String) -> Unit) {
        val heldBefore = locks.generation(path) != null
        val grant = locks.lease(path)
        var changed = false
        try {
            change(grant.generation)
            changed = true
        } finally {
            if (changed || !heldBefore) locks.release(path)
        }
    }

    /**
     * Runs a structural change the user made in the IDE, one at a time with syncing. [after] ends the change's hold on
     * its paths; [undo] then puts the local files back when the platform refused the change.
     */
    private fun change(failure: String, after: () -> Unit = {}, undo: () -> Unit = {}, block: suspend () -> Unit) {
        scope.launch(Dispatchers.IO) {
            mutex.withLock {
                val error = try {
                    block()
                    null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    e
                } finally {
                    after()
                }
                // WHY: a pass or an undo against the tree from before the change would act on a stale platform state.
                runCatching { reloadTree() }
                if (error != null) {
                    runCatching(undo)
                    onUpdate(WorkspaceUpdate.SyncProblem("$failure: ${error.message ?: error.javaClass.simpleName}"))
                }
            }
            requestSync()
        }
    }

    private fun isUntouched(path: String, file: Path, expectedSha: String?): Boolean {
        val currentSha = if (Files.exists(file)) sha256(Files.readAllBytes(file)) else null
        return currentSha == expectedSha && !editor.hasUnsavedEdits(file) && !isBusy(path)
    }

    private fun isBusy(path: String): Boolean =
        saver.needsLease(path) || saver.state(path) != UploadState.Synced || path in conflicts || isIntended(path)

    private fun isIntended(path: String): Boolean = intents.any { isAtOrUnder(path, it) }

    private fun isReadOnly(path: String): Boolean = tree().entry(path)?.let(::isReadOnly) ?: false

    private fun isReadOnly(entry: TreeEntry): Boolean = entry.readonly || entry.source == EntrySource.GLOBAL

    private fun versionOf(path: String): String =
        tree().entry(path)?.etag ?: throw IllegalStateException("The platform returned no version for $path.")

    private fun pruneEmptyParents(file: Path) {
        var dir = file.parent
        while (dir != null && dir != folder.root && Files.isDirectory(dir) && Files.list(dir).use { it.findAny().isEmpty }) {
            Files.delete(dir)
            dir = dir.parent
        }
    }

    private companion object {
        const val BULK_DELETE_LIMIT = 5
        const val UPLOAD_WAIT_MS = 15_000L
    }
}

/** What syncing needs to know from and tell the editor, so it never overwrites what the user is typing. */
interface LocalEditor {
    /** [file] is open with changes the IDE has not saved yet. */
    fun hasUnsavedEdits(file: Path): Boolean

    /** [files] were written or deleted outside the editor. */
    fun filesChanged(files: Collection<Path>)
}

private inline fun ignoreMissing(block: () -> Unit) {
    try {
        block()
    } catch (e: PlatformException) {
        if (e.code != PlatformErrorCode.NOT_FOUND) throw e
    }
}
