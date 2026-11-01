// DATAMIMIC for JetBrains IDEs
// Copyright (c) 2026 Rapiddweller Asia Co., Ltd.
// SPDX-License-Identifier: MIT

package com.rapiddweller.datamimic.core.creation.street

import com.rapiddweller.datamimic.core.isTransient
import com.rapiddweller.datamimic.core.workspace.EntryKind
import com.rapiddweller.datamimic.core.workspace.UploadStatus
import com.rapiddweller.datamimic.core.workspace.UploadTaskApi
import com.rapiddweller.datamimic.core.workspace.WorkspaceApi
import com.rapiddweller.datamimic.core.workspace.WorkspaceSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Files
import java.nio.file.Path

/** One file that the platform created. [local] is null when the file is not in the local folder (yet). */
data class CreatedFile(val path: String, val local: Path?)

typealias CreatedFiles = List<CreatedFile>

/** The result of [createOnPlatform]. */
sealed interface PlatformResult {
    /** [paths] are the project paths of the created files. */
    data class Created(val paths: List<String>) : PlatformResult

    /** The upload task still runs on the platform after the deadline. It is not a failure: the task goes on. */
    data class StillRunning(val taskId: String) : PlatformResult
}

/**
 * The upload task failed on the platform. [message] is the text of the platform.
 *
 * Files can exist after this failure: the platform writes the template file and the model file one after the other,
 * without a rollback.
 */
class UploadFailedException(message: String) : RuntimeException(message)

/** The largest source file that the platform accepts for an upload task. */
const val MAX_UPLOAD_BYTES = 100L * 1024 * 1024

/**
 * Creates the files of [request] on the platform. The platform content is the source of truth: this function never
 * writes a file locally. Run [pullCreated] afterwards to bring the files into the local folder.
 *
 * Contract:
 * - The platform refuses the request: the function throws, for example a `PlatformException` with HTTP 412 or 409.
 * - The upload task fails: the function throws [UploadFailedException].
 * - The upload task runs longer than [deadlineMs]: the function returns [PlatformResult.StillRunning].
 * - A cancellation always propagates, also while the function waits for the upload task.
 */
suspend fun createOnPlatform(
    request: CreationRequest,
    api: WorkspaceApi,
    uploads: UploadTaskApi,
    session: WorkspaceSession,
    pollIntervalMs: Long = 2_000,
    deadlineMs: Long = 5 * 60_000,
): PlatformResult = withContext(Dispatchers.IO) {
    when (request) {
        is CreationRequest.FromTemplate ->
            PlatformResult.Created(listOf(api.createFromTemplate(session.projectId, request.dir, request.name, request.extension)))
        is CreationRequest.FromUpload -> {
            val source = Path.of(request.source)
            val bytes = Files.readAllBytes(source)
            val taskId = uploads.start(session.projectId, request.type, request.dir, source.fileName.toString(), bytes, request.customFields)
            withTimeoutOrNull(deadlineMs) { awaitUpload(uploads, session.projectId, taskId, pollIntervalMs) }
                ?: PlatformResult.StillRunning(taskId)
        }
    }
}

private suspend fun awaitUpload(uploads: UploadTaskApi, projectId: String, taskId: String, pollIntervalMs: Long): PlatformResult {
    var outages = 0
    while (true) {
        delay(pollIntervalMs)
        val status = try {
            uploads.status(projectId, taskId).also { outages = 0 }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // WHY: a short network or platform outage does not stop the task; only a longer one ends the wait.
            if (!e.isTransient()) throw e
            if (++outages >= MAX_POLL_OUTAGES) return PlatformResult.StillRunning(taskId)
            continue
        }
        when (status) {
            UploadStatus.Running -> continue
            is UploadStatus.Succeeded -> return PlatformResult.Created(status.paths)
            is UploadStatus.Failed -> throw UploadFailedException(status.message)
        }
    }
}

private const val MAX_POLL_OUTAGES = 5

/**
 * The target paths of [request] that exist as files on the platform.
 *
 * It reads the tree from the platform, because the tree of [session] is a cache that is stale while the event stream
 * is down.
 */
suspend fun existingTargets(request: CreationRequest, api: WorkspaceApi, session: WorkspaceSession): List<String> {
    val tree = withContext(Dispatchers.IO) { api.tree(session.projectId) }
    return request.targetPaths().filter { tree.entry(it)?.kind == EntryKind.FILE }
}

/** Why the local file [source] cannot be uploaded, or null. */
fun sourceProblem(source: String): String? {
    val path = Path.of(source)
    return when {
        !Files.isRegularFile(path) -> "The file $source does not exist."
        !Files.isReadable(path) -> "The file $source is not readable."
        Files.size(path) > MAX_UPLOAD_BYTES -> "The file $source is larger than 100 MB."
        else -> null
    }
}

/** Pulls files that the platform created into the local folder. It never throws, except for a cancellation. */
suspend fun pullCreated(paths: List<String>, session: WorkspaceSession): CreatedFiles {
    try {
        withContext(Dispatchers.IO) { session.refreshTree() }
        session.sync.syncNow()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        return paths.map { CreatedFile(it, null) }
    }
    return paths.map { CreatedFile(it, localFile(session, it)) }
}

// WHY: a sync pass collects file problems and does not throw, so only the file and its base prove the pull.
private fun localFile(session: WorkspaceSession, path: String): Path? {
    val file = session.folder.resolve(path) ?: return null
    val pulled = Files.isRegularFile(file) && session.bases.get(path) != null && !session.sync.isConflict(path)
    return file.takeIf { pulled }
}
