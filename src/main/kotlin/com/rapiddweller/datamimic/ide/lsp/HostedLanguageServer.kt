// DATAMIMIC for JetBrains IDEs
// Copyright (c) 2026 Rapiddweller Asia Co., Ltd.
// SPDX-License-Identifier: MIT

package com.rapiddweller.datamimic.ide.lsp

import com.google.gson.JsonParser
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.components.serviceIfCreated
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.platform.lsp.api.LspCommunicationChannel
import com.intellij.platform.lsp.api.LspServerDescriptor
import com.intellij.platform.lsp.api.LspServerManager
import com.intellij.platform.lsp.api.LspServerSupportProvider
import com.intellij.platform.lsp.api.ProjectWideLspServerDescriptor
import com.rapiddweller.datamimic.core.PlatformErrorCode
import com.rapiddweller.datamimic.core.PlatformException
import com.rapiddweller.datamimic.core.lsp.DocumentUris
import com.rapiddweller.datamimic.core.lsp.LspBridge
import com.rapiddweller.datamimic.core.lsp.LspInit
import com.rapiddweller.datamimic.core.lsp.LspLink
import com.rapiddweller.datamimic.core.workspace.FolderIdentity
import com.rapiddweller.datamimic.core.workspace.ProjectFolder
import com.rapiddweller.datamimic.ide.AuthState
import com.rapiddweller.datamimic.ide.DatamimicPlatform
import com.rapiddweller.datamimic.ide.NOTIFICATION_GROUP
import com.rapiddweller.datamimic.ide.activeProject
import com.rapiddweller.datamimic.ide.toNioPathOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.WorkspaceFolder
import java.nio.file.Path

/** Where the window's language server stands, for the status bar. */
enum class LspStatus {
    SIGNED_OUT,
    STARTING,

    /** Turned off for the project on the platform. */
    TURNED_OFF,

    /** Ready; the IDE connects once an XML file of the folder is open. */
    READY,
    CONNECTED,
    FAILED,
}

data class LspState(val status: LspStatus, val detail: String? = null)

/**
 * The platform's hosted language server for the project folder of one window: completion and checks for its XML
 * files. Part of the optional plugin module that only loads where the IDE has the LSP API.
 */
@Service(Service.Level.PROJECT)
class HostedLanguageServer(private val project: Project, private val scope: CoroutineScope) : Disposable {
    private val platform = DatamimicPlatform.getInstance()
    private val stateFlow = MutableStateFlow(LspState(LspStatus.SIGNED_OUT))
    val state: StateFlow<LspState> = stateFlow

    @Volatile
    private var descriptor: HostedLspDescriptor? = null

    init {
        LOG.info("Language server support started for ${project.basePath}")
        scope.launch {
            platform.state.collect { auth ->
                LOG.info("Sign-in state: ${auth.javaClass.simpleName}")
                when (auth) {
                    is AuthState.SignedIn -> connect()
                    is AuthState.SignedOut -> {
                        disconnect()
                        stateFlow.value = LspState(LspStatus.SIGNED_OUT)
                    }
                    AuthState.Unknown -> Unit
                }
            }
        }
    }

    /** The server for [file], or null when it is not a synced XML file or no server is available. */
    fun descriptorFor(file: VirtualFile): LspServerDescriptor? {
        val current = descriptor
        val supported = current?.isSupportedFile(file) == true
        val outcome = when {
            current == null -> "no server (see the status bar for why)"
            supported -> "sent to the server as ${current.getFileUri(file)}"
            else -> "not a file for the server"
        }
        LOG.info("File opened: ${file.path}; $outcome")
        return current?.takeIf { supported }
    }

    /** Asks the platform again, e.g. after the server was turned on elsewhere. */
    fun checkAgain() {
        scope.launch { connect() }
    }

    /** Turns the server on for everyone in the project, as the platform UI and VS Code do, and connects once it is on. */
    fun turnOn() {
        val target = target() ?: return
        scope.launch {
            if (!setEnabled(target.identity, true)) return@launch
            stateFlow.value = LspState(LspStatus.STARTING, "Waiting for the platform to report it on.")
            withBackgroundProgress(project, "Starting the DATAMIMIC language server") {
                // WHY: each platform process caches "turned off" for up to 30 seconds.
                repeat(ENABLE_CHECKS) { attempt ->
                    if (attempt > 0) delay(ENABLE_CHECK_INTERVAL_MS)
                    if (start(target) != LspStatus.TURNED_OFF) return@withBackgroundProgress
                }
                stateFlow.value = LspState(LspStatus.TURNED_OFF, "Turned on, but the platform still reports it off. Check again in a minute.")
            }
        }
    }

    /** Turns the server off for everyone in the project. */
    fun turnOff() {
        val target = target() ?: return
        scope.launch {
            if (!setEnabled(target.identity, false)) return@launch
            disconnect()
            stateFlow.value = LspState(LspStatus.TURNED_OFF, "Turned off for ${target.identity.projectName}.")
        }
    }

    private class Target(val identity: FolderIdentity, val folder: ProjectFolder)

    private fun target(): Target? {
        val active = project.activeProject()
        val identity = active.identity ?: return null
        val folder = active.folder ?: return null
        return Target(identity, folder)
    }

    private suspend fun connect() {
        val target = target() ?: return LOG.info("Not a DATAMIMIC project folder; no language server.")
        val signedIn = platform.state.value as? AuthState.SignedIn ?: return
        disconnect()
        if (target.identity.origin != signedIn.origin) {
            LOG.info("Folder belongs to ${target.identity.origin.value}, signed in to ${signedIn.origin.value}; no language server.")
            stateFlow.value = LspState(LspStatus.FAILED, "This folder belongs to ${target.identity.origin.value}.")
            return
        }
        if (start(target) == LspStatus.TURNED_OFF) notifyTurnedOff(target.identity)
    }

    /** Reads the startup data and prepares the server; @return the status it ended in. */
    private suspend fun start(target: Target): LspStatus {
        val projectId = target.identity.projectId
        stateFlow.value = LspState(LspStatus.STARTING)
        val init = try {
            withContext(Dispatchers.IO) { platform.lsp.init(projectId) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LOG.info("Language server start failed for $projectId: $e")
            stateFlow.value = if (e is PlatformException && e.code == PlatformErrorCode.LSP_DISABLED) {
                LspState(LspStatus.TURNED_OFF, "Turned off for ${target.identity.projectName} on the platform.")
            } else {
                LspState(LspStatus.FAILED, e.message ?: e.javaClass.simpleName)
            }
            return stateFlow.value.status
        }
        val bridge = platform.lspBridge(
            projectId,
            onRejected = { scope.launch { rejected() } },
            onLink = ::linkChanged,
            log = LOG::info,
        )
        descriptor = HostedLspDescriptor(project, projectId, target.folder, init, bridge)
        stateFlow.value = LspState(LspStatus.READY)
        LOG.info("Language server of $projectId ready on port ${bridge.port}; checking open files.")
        withContext(Dispatchers.EDT) { LspServerManager.getInstance(project).startServersIfNeeded(PlatformLspSupport::class.java) }
        return LspStatus.READY
    }

    private fun linkChanged(link: LspLink) {
        if (descriptor == null) return
        stateFlow.value = LspState(if (link == LspLink.OPEN) LspStatus.CONNECTED else LspStatus.READY)
    }

    /** The platform refused the connection; restarting on our own would only be refused again. */
    private suspend fun rejected() {
        disconnect()
        stateFlow.value = LspState(LspStatus.FAILED, "The platform refused the connection: turned off for this project, or access denied.")
    }

    private suspend fun setEnabled(identity: FolderIdentity, enabled: Boolean): Boolean = try {
        withContext(Dispatchers.IO) { platform.lsp.setEnabled(identity.projectId, enabled) }
        LOG.info("Language server of ${identity.projectId} turned ${if (enabled) "on" else "off"}.")
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        stateFlow.value = LspState(LspStatus.FAILED, "Could not change the setting on the platform: ${e.message ?: e.javaClass.simpleName}")
        false
    }

    private suspend fun disconnect() {
        val previous = descriptor ?: return
        descriptor = null
        previous.bridge.close()
        withContext(Dispatchers.EDT) { LspServerManager.getInstance(project).stopServers(PlatformLspSupport::class.java) }
    }

    private fun notifyTurnedOff(identity: FolderIdentity) {
        NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification(
                "The language server is turned off for ${identity.projectName} on the platform, so there is no completion or checking from it.",
                NotificationType.INFORMATION,
            )
            .addAction(NotificationAction.createSimpleExpiring("Turn On for This Project", ::turnOn))
            .addAction(NotificationAction.createSimpleExpiring("Check Again", ::checkAgain))
            .notify(project)
    }

    override fun dispose() {
        descriptor?.bridge?.close()
    }

    private companion object {
        val LOG = logger<HostedLanguageServer>()
        const val ENABLE_CHECKS = 9
        const val ENABLE_CHECK_INTERVAL_MS = 5_000L
    }
}

/** Connects the IDE's LSP client to the platform through [bridge], naming files the way the server expects. */
internal class HostedLspDescriptor(
    project: Project,
    private val projectId: String,
    private val folder: ProjectFolder,
    private val init: LspInit,
    val bridge: LspBridge,
) : ProjectWideLspServerDescriptor(project, "DATAMIMIC") {
    override val lspCommunicationChannel: LspCommunicationChannel = LspCommunicationChannel.Socket(bridge.port, false)

    // WHY: called often and on the UI thread, so no disk access; [ProjectFolder.pathOf] only looks at the path.
    override fun isSupportedFile(file: VirtualFile): Boolean = file.extension == XML && platformPath(file) != null

    override fun getFileUri(file: VirtualFile): String = platformPath(file)?.let { DocumentUris.of(projectId, it) } ?: super.getFileUri(file)

    override fun findFileByUri(fileUri: String): VirtualFile? =
        DocumentUris.pathOf(fileUri, projectId)?.let(folder::resolve)?.let(LocalFileSystem.getInstance()::findFileByNioFile)
            ?: super.findFileByUri(fileUri)

    override fun createInitializationOptions(): Any = JsonParser.parseString(init.initializationOptions(bridge.secret))

    override fun createInitializeParams(): InitializeParams = super.createInitializeParams().apply {
        rootUri = init.rootUri
        workspaceFolders = listOf(WorkspaceFolder(init.rootUri, presentableName))
    }

    private fun platformPath(file: VirtualFile): String? = file.toNioPathOrNull()?.let(folder::pathOf)

    private companion object {
        const val XML = "xml"
    }
}

class PlatformLspSupport : LspServerSupportProvider {
    override fun fileOpened(project: Project, file: VirtualFile, serverStarter: LspServerSupportProvider.LspServerStarter) {
        project.serviceIfCreated<HostedLanguageServer>()?.descriptorFor(file)?.let(serverStarter::ensureServerStarted)
    }
}

/** Starts the language server connection when a DATAMIMIC project folder opens. */
class HostedLanguageServerStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        if (project.isProjectFolder()) project.service<HostedLanguageServer>()
    }
}

internal fun Project.isProjectFolder(): Boolean = basePath?.let { ProjectFolder(Path.of(it)).isProjectFolder() } == true
