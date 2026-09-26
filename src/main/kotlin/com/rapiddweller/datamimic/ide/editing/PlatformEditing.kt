// DATAMIMIC for JetBrains IDEs
// Copyright (c) 2026 Rapiddweller Asia Co., Ltd.
// SPDX-License-Identifier: MIT

package com.rapiddweller.datamimic.ide.editing

import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.serviceIfCreated
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.impl.DirectoryIndexExcludePolicy
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.WritingAccessProvider
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCopyEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import com.intellij.platform.ide.progress.runWithModalProgressBlocking
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.EditorNotificationProvider
import com.intellij.ui.EditorNotifications
import com.rapiddweller.datamimic.core.workspace.FileNotice
import com.rapiddweller.datamimic.core.workspace.OwnerRelation
import com.rapiddweller.datamimic.core.workspace.ProjectFolder
import com.rapiddweller.datamimic.core.workspace.StreamState
import com.rapiddweller.datamimic.core.workspace.UploadFailure
import com.rapiddweller.datamimic.core.workspace.WorkspaceSession
import com.rapiddweller.datamimic.core.workspace.WriteAccess
import com.rapiddweller.datamimic.ide.DatamimicPlatform
import com.rapiddweller.datamimic.ide.SyncedFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Path
import java.util.function.Function
import javax.swing.JComponent

/** What the editor banners of one IDE window offer for synced files. */
@Service(Service.Level.PROJECT)
class PlatformEditors(private val project: Project, private val scope: CoroutineScope) {
    /** Replaces the local file, and any unsaved text in its editor, with the platform's version. */
    fun usePlatformVersion(file: VirtualFile, synced: SyncedFile) = resolve("Could Not Load the Platform Version") {
        synced.session.sync.usePlatformVersion(synced.path)
        withContext(Dispatchers.EDT) {
            // WHY: the refresh must see the new bytes before the editor drops its unsaved text and reads them.
            file.refresh(false, false)
            if (file.isValid) FileDocumentManager.getInstance().reloadFiles(file)
        }
    }

    fun keepLocalVersion(synced: SyncedFile) = resolve("Could Not Keep Your Version") { synced.session.sync.keepLocalVersion(synced.path) }

    fun takeOver(file: VirtualFile, synced: SyncedFile, notice: FileNotice.LockedBy) {
        val confirmed = Messages.showOkCancelDialog(
            project,
            "${notice.ownerName ?: "Another client"} holds the edit lock for ${file.name}. " +
                "Taking it over discards their changes that are not saved to the platform yet.",
            "Take Over Edit Lock",
            "Take Over",
            Messages.getCancelButton(),
            Messages.getWarningIcon(),
        ) == Messages.OK
        if (!confirmed) return
        resolve("Take Over Failed") {
            withContext(Dispatchers.IO) { synced.session.locks.takeover(synced.path, notice.generation) }
            // WHY: the other client may have saved in between; the sync brings that version unless there are local edits.
            synced.session.sync.requestSync()
        }
    }

    fun refreshBanners() {
        FileEditorManager.getInstance(project).openFiles.forEach(EditorNotifications.getInstance(project)::updateNotifications)
    }

    private fun resolve(title: String, block: suspend () -> Unit) {
        scope.launch(Dispatchers.EDT) {
            runCatching { block() }.onFailure { Messages.showErrorDialog(project, it.message, title) }
            refreshBanners()
        }
    }
}

/** Takes the edit lease while a synced file is shown, and gives it up when the file is no longer edited. */
class PlatformEditorListener : FileEditorManagerListener {
    override fun selectionChanged(event: FileEditorManagerEvent) {
        event.oldFile?.let(::hidden)
        val shown = event.newFile?.let(DatamimicPlatform.getInstance()::syncedFile) ?: return
        if (!shown.session.isReadOnly(shown.path)) shown.session.editorShown(shown.path)
    }

    override fun fileClosed(source: FileEditorManager, file: VirtualFile) = hidden(file)

    private fun hidden(file: VirtualFile) {
        val synced = DatamimicPlatform.getInstance().syncedFile(file) ?: return
        synced.session.editorHidden(synced.path, unsavedEdits = FileDocumentManager.getInstance().isFileModified(file))
    }
}

/**
 * Hands every change in a synced project folder to its sync. Deletes and moves made in the IDE go to the platform as
 * such; anything that only a refresh noticed (agents, terminal, file manager) is compared by content instead.
 */
class FolderChangeListener : BulkFileListener {
    override fun after(events: List<VFileEvent>) {
        // WHY: without a connected project folder there is nothing to sync, and the platform service need not start.
        val platform = serviceIfCreated<DatamimicPlatform>() ?: return
        if (platform.openWorkspaces().isEmpty()) return
        for (event in events) {
            if (event.fileSystem !is LocalFileSystem) continue
            when (event) {
                is VFileContentChangeEvent -> platform.synced(event.path)?.let { it.session.sync.localChanged(it.path) }
                is VFileCreateEvent -> platform.synced(event.path)?.let { created(it, event.isDirectory, event.isFromRefresh) }
                is VFileCopyEvent -> platform.synced("${event.newParent.path}/${event.newChildName}")?.let {
                    created(it, event.file.isDirectory, event.isFromRefresh)
                }
                is VFileDeleteEvent -> platform.synced(event.path)?.let { deleted(it, event.isFromRefresh) }
                is VFileMoveEvent -> moved(platform, event.oldPath, event.newPath, event.file.isDirectory, event.isFromRefresh)
                is VFilePropertyChangeEvent -> if (event.isRename) {
                    moved(platform, event.oldPath, event.newPath, event.file.isDirectory, event.isFromRefresh)
                }
            }
        }
    }

    private fun created(target: SyncedFile, isDirectory: Boolean, fromRefresh: Boolean) = when {
        !isDirectory -> target.session.sync.localChanged(target.path)
        // WHY: a folder that appeared on disk may already hold files the IDE has not listed yet; a pass finds them.
        fromRefresh -> target.session.sync.requestSync()
        else -> target.session.sync.directoryCreated(target.path)
    }

    private fun deleted(target: SyncedFile, fromRefresh: Boolean) =
        if (fromRefresh) target.session.sync.requestSync() else target.session.sync.deleted(target.path)

    private fun moved(platform: DatamimicPlatform, oldPath: String, newPath: String, isDirectory: Boolean, fromRefresh: Boolean) {
        val from = platform.synced(oldPath)
        val to = platform.synced(newPath)
        when {
            fromRefresh -> listOfNotNull(from, to).forEach { it.session.sync.requestSync() }
            from != null && to != null && from.session === to.session -> from.session.sync.moved(from.path, to.path)
            else -> {
                from?.let { deleted(it, fromRefresh = false) }
                to?.let { created(it, isDirectory, fromRefresh = false) }
            }
        }
    }

    private fun DatamimicPlatform.synced(path: String): SyncedFile? = syncedFile(Path.of(path))
}

/** Refuses edits of a synced file only while another client is known to hold its edit lock. */
class PlatformWritingAccess : WritingAccessProvider() {
    override fun requestWriting(files: Collection<VirtualFile>): Collection<VirtualFile> = files.filter { !mayWrite(it) }

    override fun getReadOnlyMessage(): String = "Another client is editing this DATAMIMIC file. The note above the editor says who."

    // WHY: the ETag fences every upload, so editing without a known lock state (offline, connecting) cannot overwrite anyone.
    private fun mayWrite(file: VirtualFile): Boolean {
        val synced = serviceIfCreated<DatamimicPlatform>()?.syncedFile(file) ?: return true
        val locks = synced.session.locks
        return when (locks.access(synced.path)) {
            is WriteAccess.LockedBy -> false
            WriteAccess.Acquiring -> {
                locks.requestLease(synced.path)
                true
            }
            WriteAccess.Allowed, WriteAccess.WaitingForLiveUpdates -> true
        }
    }
}

class PlatformFileBanner : EditorNotificationProvider, DumbAware {
    override fun collectNotificationData(project: Project, file: VirtualFile): Function<in FileEditor, out JComponent?>? {
        val synced = serviceIfCreated<DatamimicPlatform>()?.syncedFile(file) ?: return null
        if (synced.session.isReadOnly(synced.path)) {
            return Function { editor ->
                EditorNotificationPanel(editor, EditorNotificationPanel.Status.Info).text("Shared from a global project; read-only here.")
            }
        }
        val notice = synced.session.notice(synced.path) ?: return null
        val editors = project.getService(PlatformEditors::class.java)
        return Function { editor -> panel(editor, notice, file, synced, editors) }
    }

    private fun panel(editor: FileEditor, notice: FileNotice, file: VirtualFile, synced: SyncedFile, editors: PlatformEditors): EditorNotificationPanel =
        when (notice) {
            is FileNotice.Conflict -> EditorNotificationPanel(editor, EditorNotificationPanel.Status.Error).apply {
                text(if (notice.deletedOnPlatform) "Deleted on the platform, but changed here." else "Changed here and on the platform.")
                createActionLabel("Keep mine") { editors.keepLocalVersion(synced) }
                createActionLabel(if (notice.deletedOnPlatform) "Delete it here too" else "Use the platform version") {
                    editors.usePlatformVersion(file, synced)
                }
            }
            is FileNotice.UploadFailed -> EditorNotificationPanel(editor, EditorNotificationPanel.Status.Error).apply {
                text(uploadFailureText(notice))
                createActionLabel("Retry") { synced.session.saver.retry(synced.path) }
                createActionLabel("Use the platform version (discard my changes)") { editors.usePlatformVersion(file, synced) }
            }
            is FileNotice.LockedBy -> EditorNotificationPanel(editor, EditorNotificationPanel.Status.Warning).apply {
                text("Being edited by ${ownerText(notice)}. You can read, but not change it.")
                createActionLabel("Take over…") { editors.takeOver(file, synced, notice) }
            }
            FileNotice.PlatformUpdated -> EditorNotificationPanel(editor, EditorNotificationPanel.Status.Info).apply {
                text("A newer version of this file is on the platform.")
                createActionLabel("Load it (discard my unsaved changes)") { editors.usePlatformVersion(file, synced) }
            }
            is FileNotice.LiveUpdatesDown -> EditorNotificationPanel(editor, EditorNotificationPanel.Status.Warning).apply {
                text(liveUpdatesText(notice.state))
                if (notice.state == StreamState.UNAVAILABLE || notice.state == StreamState.REFUSED) {
                    createActionLabel("Reconnect") { synced.session.start() }
                }
            }
        }

    private fun uploadFailureText(notice: FileNotice.UploadFailed): String = when (notice.failure) {
        UploadFailure.LOCK_LOST -> "Not saved to the platform: another client took the edit lock."
        UploadFailure.SESSION_ENDED -> "Not saved to the platform: your session ended. Sign in again, then retry."
        UploadFailure.CONCURRENT_CHANGE, UploadFailure.OTHER -> "Not saved to the platform: ${notice.message}"
    }

    private fun ownerText(notice: FileNotice.LockedBy): String = when (notice.relation) {
        OwnerRelation.THIS_CLIENT -> "this IDE"
        OwnerRelation.SAME_USER_OTHER_CLIENT -> "you in another window or browser"
        OwnerRelation.OTHER_USER -> notice.ownerName ?: "another user"
        OwnerRelation.PROJECT_TOKEN -> "an automation using a project token"
    }

    private fun liveUpdatesText(state: StreamState): String = when (state) {
        StreamState.SUPERSEDED -> "Live lock updates moved to another connection of this IDE. Reopen the project to see who edits what."
        StreamState.UNAVAILABLE -> "Live lock updates are unavailable. Your changes are kept here and uploaded when possible."
        StreamState.REFUSED -> "The platform refused live lock updates for this project, so nobody sees that you edit here. Your changes are kept and uploaded when possible."
        StreamState.IDLE, StreamState.CONNECTING, StreamState.LIVE, StreamState.CLOSED -> "Connecting to live lock updates…"
    }
}

/** Keeps the plugin's own state out of search and indexing. */
class ProjectFolderExcludePolicy(private val project: Project) : DirectoryIndexExcludePolicy {
    override fun getExcludeUrlsForProject(): Array<String> {
        val base = project.basePath ?: return emptyArray()
        if (!ProjectFolder(Path.of(base)).isProjectFolder()) return emptyArray()
        return arrayOf(VfsUtilCore.pathToUrl("$base/${ProjectFolder.META_DIR}"))
    }
}

private const val UPLOAD_WAIT_MS = 15_000L

/**
 * Saves every open document, syncs the folder and waits briefly until the platform has the changes. Call on the UI
 * thread before something runs on the platform's copy of the files.
 *
 * @return the files whose local state is still not on the platform, conflicts included.
 */
internal fun flushPlatformEdits(project: Project, session: WorkspaceSession): Set<String> {
    FileDocumentManager.getInstance().saveAllDocuments()
    runWithModalProgressBlocking(project, "Saving DATAMIMIC files to the platform") {
        withTimeoutOrNull(UPLOAD_WAIT_MS) {
            // WHY: agents write files the IDE may not have noticed yet; a pass reads the disk itself.
            runCatching { session.sync.syncNow() }
            session.sync.awaitUploads()
        }
    }
    return session.saver.unconfirmedPaths() + session.sync.conflicts()
}
