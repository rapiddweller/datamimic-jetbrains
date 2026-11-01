// DATAMIMIC for JetBrains IDEs
// Copyright (c) 2026 Rapiddweller Asia Co., Ltd.
// SPDX-License-Identifier: MIT

package com.rapiddweller.datamimic.ide.creation

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.platform.ide.progress.ModalTaskOwner
import com.intellij.platform.ide.progress.withModalProgress
import com.rapiddweller.datamimic.core.creation.street.CreatedFile
import com.rapiddweller.datamimic.core.creation.street.pullCreated
import com.rapiddweller.datamimic.core.workspace.WorkspaceSession
import com.rapiddweller.datamimic.ide.Cancellation
import com.rapiddweller.datamimic.ide.NOTIFICATION_GROUP
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.file.Path

private val LOG = Logger.getInstance("#com.rapiddweller.datamimic.ide.creation")

/**
 * UI thread. Pulls the created [paths] into the local folder and opens each file that [opens] selects. A file that is
 * not local, or that the IDE does not find, gets a notice.
 */
internal suspend fun pullAndOpen(
    project: Project,
    owner: ModalTaskOwner,
    paths: List<String>,
    session: WorkspaceSession,
    opens: (String) -> Boolean,
) {
    val created = try {
        withModalProgress(owner, "Syncing ${paths.joinToString()}", Cancellation.cancellable()) { pullCreated(paths, session) }
    } catch (e: CancellationException) {
        // WHY: Cancel here stops only the wait. The file exists on the platform and the sync goes on.
        currentCoroutineContext().ensureActive()
        paths.map { CreatedFile(it, null) }
    }
    for (file in created) {
        val local = file.local
        if (local == null || (opens(file.path) && !openCreatedFile(project, local))) {
            notify(project, "Created ${file.path} on the platform. The local copy is still syncing.")
        }
    }
}

/** Opens the new local [file] in an editor. @return false when the IDE does not find the file. */
private suspend fun openCreatedFile(project: Project, file: Path): Boolean {
    // WHY: the sync wrote the file outside the VFS, and a synchronous refresh must not block the UI thread.
    val virtualFile = withContext(Dispatchers.IO) { LocalFileSystem.getInstance().refreshAndFindFileByNioFile(file) } ?: return false
    withContext(Dispatchers.EDT) { OpenFileDescriptor(project, virtualFile).navigate(true) }
    return true
}

internal fun notify(project: Project, text: String) {
    NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP)
        .createNotification(text, NotificationType.INFORMATION)
        .notify(project)
}

internal fun fail(project: Project, title: String, e: Exception, text: String = e.message ?: e.javaClass.simpleName) {
    LOG.warn("$title: $e")
    Messages.showErrorDialog(project, text, title)
}
