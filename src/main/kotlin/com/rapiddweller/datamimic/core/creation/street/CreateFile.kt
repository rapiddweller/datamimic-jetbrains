// DATAMIMIC for JetBrains IDEs
// Copyright (c) 2026 Rapiddweller Asia Co., Ltd.
// SPDX-License-Identifier: MIT

package com.rapiddweller.datamimic.core.creation.street

import com.rapiddweller.datamimic.core.workspace.WorkspaceApi
import com.rapiddweller.datamimic.core.workspace.WorkspaceSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path

/** One file that the platform created. [local] is null when the file is not in the local folder (yet). */
data class CreatedFile(val path: String, val local: Path?)

typealias CreatedFiles = List<CreatedFile>

/**
 * Creates the files of [request] on the platform, then pulls them into the local folder of [session].
 *
 * The platform content is the source of truth. This function never writes the file locally.
 *
 * Contract:
 * - The platform call fails: the function throws, for example a `PlatformException` with HTTP 412.
 * - The platform call succeeds: the function does not throw. When the refresh, the sync or the check of the local
 *   file fails, the file has `local = null`. The caller tells the user, because a retry meets HTTP 412.
 * - A cancellation always propagates, also after the platform call.
 */
suspend fun createFile(request: CreationRequest, api: WorkspaceApi, session: WorkspaceSession): CreatedFiles {
    val paths = withContext(Dispatchers.IO) {
        when (request) {
            is CreationRequest.FromTemplate -> listOf(api.createFromTemplate(session.projectId, request.dir, request.name, request.extension))
        }
    }
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
