// DATAMIMIC for JetBrains IDEs
// Copyright (c) 2026 Rapiddweller Asia Co., Ltd.
// SPDX-License-Identifier: MIT

package com.rapiddweller.datamimic.ide

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.net.JdkProxyProvider
import com.intellij.util.net.ssl.CertificateManager
import com.rapiddweller.datamimic.core.AccountApi
import com.rapiddweller.datamimic.core.PlatformHttp
import com.rapiddweller.datamimic.core.PlatformOrigin
import com.rapiddweller.datamimic.core.PlatformUser
import com.rapiddweller.datamimic.core.ProjectsApi
import com.rapiddweller.datamimic.core.SessionExpiredException
import com.rapiddweller.datamimic.core.SessionService
import com.rapiddweller.datamimic.core.generation.GenerationApi
import com.rapiddweller.datamimic.core.lsp.LspApi
import com.rapiddweller.datamimic.core.lsp.LspBridge
import com.rapiddweller.datamimic.core.lsp.LspLink
import com.rapiddweller.datamimic.core.mcp.McpAccess
import com.rapiddweller.datamimic.core.mcp.McpServer
import com.rapiddweller.datamimic.core.mcp.ProjectTokensApi
import com.rapiddweller.datamimic.core.workspace.LocalEditor
import com.rapiddweller.datamimic.core.workspace.LocksApi
import com.rapiddweller.datamimic.core.workspace.ProjectFolder
import com.rapiddweller.datamimic.core.workspace.WorkspaceApi
import com.rapiddweller.datamimic.core.workspace.WorkspaceSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.net.http.HttpClient
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.UUID

sealed interface AuthState {
    data object Unknown : AuthState

    data class SignedOut(val notice: String? = null) : AuthState

    data class SignedIn(val user: PlatformUser, val origin: PlatformOrigin) : AuthState
}

/** A file of a synced project folder and the platform session it belongs to. */
class SyncedFile(val session: WorkspaceSession, val path: String)

/**
 * The platform connection of this IDE process, shared by all project windows: one sign-in, one client binding,
 * and one [WorkspaceSession] per platform project whose folder is synced.
 */
@Service(Service.Level.APP)
class DatamimicPlatform(internal val scope: CoroutineScope) : Disposable {
    private val http = platformHttpClient()

    // WHY: scoped per IDE product so two installed IDEs never overwrite each other's session.
    private val credentials = CredentialAttributes(
        generateServiceName("DATAMIMIC", "session.v2.${ApplicationInfo.getInstance().build.productCode}"),
    )

    private val sessions = SessionService(
        http,
        load = { PasswordSafe.instance.getPassword(credentials) },
        save = { PasswordSafe.instance.setPassword(credentials, it) },
        onExpired = ::onSessionExpired,
    )

    // WHY: the platform attributes locks and live updates to a client binding; this IDE process is one client.
    private val transport = PlatformHttp(http, sessions, UUID.randomUUID().toString())

    val account = AccountApi(transport)
    val projects = ProjectsApi(transport)
    val workspace = WorkspaceApi(transport)
    val generation = GenerationApi(transport)
    val lsp = LspApi(transport)
    private val locks = LocksApi(transport)
    private val mcpAccess = McpAccess(ProjectTokensApi(transport), ApplicationInfo.getInstance().build.productCode, transport.clientBindingId)

    private val workspaces = mutableMapOf<String, WorkspaceSession>()

    /** How many IDE windows have each platform project active; the last one to leave closes it on the platform. */
    private val activeWindows = mutableMapOf<String, Int>()

    /** The platform the open workspaces belong to. */
    private var workspacesOrigin: PlatformOrigin? = null

    private val stateFlow = MutableStateFlow<AuthState>(AuthState.Unknown)
    val state: StateFlow<AuthState> = stateFlow

    var lastPlatformUrl: String?
        get() = PropertiesComponent.getInstance().getValue(PLATFORM_URL_KEY)
        set(value) = PropertiesComponent.getInstance().setValue(PLATFORM_URL_KEY, value)

    init {
        scope.launch(Dispatchers.IO) {
            val session = sessions.current()
            stateFlow.value = if (session == null) {
                AuthState.SignedOut()
            } else {
                runCatching { AuthState.SignedIn(account.me(), session.origin) }.getOrElse { AuthState.SignedOut(it.signedOutNotice()) }
            }
        }
    }

    /** The last sign-in, to prefill the dialog. Blocking (OS credential store); call off the UI thread. */
    fun savedLogin(): SavedLogin {
        val properties = PropertiesComponent.getInstance()
        val url = lastPlatformUrl
        val remember = properties.getBoolean(REMEMBER_PASSWORD_KEY, false)
        val password = url?.takeIf { remember }?.let { PasswordSafe.instance.getPassword(loginCredentials(it)) }
        return SavedLogin(url, properties.getValue(EMAIL_KEY), password, remember)
    }

    /** Blocking; call off the UI thread. */
    fun signIn(input: LoginInput) {
        val origin = input.origin
        val previousOrigin = synchronized(this) { workspacesOrigin }
        sessions.login(origin, input.email, input.password)
        lastPlatformUrl = origin.value
        PropertiesComponent.getInstance().apply {
            setValue(EMAIL_KEY, input.email)
            setValue(REMEMBER_PASSWORD_KEY, input.rememberPassword)
        }
        PasswordSafe.instance.set(loginCredentials(origin.value), if (input.rememberPassword) Credentials(input.email, input.password) else null)
        if (previousOrigin != null && previousOrigin != origin) closeWorkspaces()
        // WHY: after an expired session, open workspaces keep their unconfirmed edits and only need live updates again.
        openWorkspaces().forEach(WorkspaceSession::start)
        stateFlow.value = AuthState.SignedIn(account.me(), origin)
    }

    /** Blocking; call off the UI thread. @return false when the platform could not revoke the session. */
    fun signOut(): Boolean {
        closeWorkspaces()
        val revoked = sessions.logout()
        stateFlow.value = AuthState.SignedOut(
            if (revoked) null else "Signed out locally. The platform was unreachable, so the session expires there on its own.",
        )
        return revoked
    }

    /**
     * The platform rejected the session. Workspaces stay open, so their unconfirmed edits upload with Retry after
     * signing in again; their leases cannot be renewed without a session and are dropped.
     */
    private fun onSessionExpired() {
        openWorkspaces().forEach { it.locks.dropAll() }
        stateFlow.value = AuthState.SignedOut(SessionExpiredException().message)
    }

    override fun dispose() {
        val open = synchronized(this) { workspaces.values.toList().also { workspaces.clear() } }
        open.forEach(WorkspaceSession::dispose)
        http.shutdownNow()
    }

    /** The session that syncs [projectId] with [folder], started on first use. */
    @Synchronized
    fun workspace(projectId: String, folder: ProjectFolder): WorkspaceSession = workspaces.getOrPut(projectId) {
        workspacesOrigin = sessions.current()?.origin
        WorkspaceSession(projectId, folder, workspace, locks, http, sessions, transport.clientBindingId, scope, IdeEditor, LOG::info).also { it.start() }
    }

    /** An IDE window opened the folder of [projectId]. */
    fun activate(projectId: String, folder: ProjectFolder): WorkspaceSession {
        synchronized(this) { activeWindows.merge(projectId, 1, Int::plus) }
        return workspace(projectId, folder)
    }

    /**
     * An IDE window left [projectId]. When no window uses it anymore, its edit locks go back to the platform and the
     * agents' project token is revoked. Blocking; call off the UI thread.
     */
    fun deactivate(projectId: String) {
        val last = synchronized(this) {
            val remaining = (activeWindows[projectId] ?: 0) - 1
            if (remaining > 0) activeWindows[projectId] = remaining else activeWindows.remove(projectId)
            remaining <= 0
        }
        if (!last) return
        synchronized(this) { workspaces.remove(projectId) }?.close()
        val origin = sessions.current()?.origin ?: return
        runCatching { mcpAccess.revoke(origin, projectId) }
    }

    /** The MCP server of [projectId] as IDE agents need it. Blocking; call off the UI thread. */
    fun mcpServer(projectId: String): McpServer {
        val origin = sessions.current()?.origin ?: throw SessionExpiredException()
        return mcpAccess.server(origin, projectId)
    }

    fun mcpRenewalDue(server: McpServer): Instant = mcpAccess.renewalDue(server)

    /** A bridge from the IDE's LSP client to the hosted language server of [projectId]; close it when done. */
    fun lspBridge(projectId: String, onRejected: () -> Unit, onLink: (LspLink) -> Unit, log: (String) -> Unit): LspBridge =
        LspBridge(http, sessions, transport.clientBindingId, projectId, onRejected, onLink, log)

    @Synchronized
    fun existingWorkspace(projectId: String): WorkspaceSession? = workspaces[projectId]

    @Synchronized
    fun openWorkspaces(): List<WorkspaceSession> = workspaces.values.toList()

    fun syncedFile(file: VirtualFile): SyncedFile? = file.toNioPathOrNull()?.let(::syncedFile)

    /** The synced project folder [file] belongs to, while its project is open. */
    fun syncedFile(file: Path): SyncedFile? =
        openWorkspaces().firstNotNullOfOrNull { session -> session.folder.pathOf(file)?.let { SyncedFile(session, it) } }

    private fun closeWorkspaces() {
        val closing = synchronized(this) {
            workspacesOrigin = null
            workspaces.values.toList().also { workspaces.clear() }
        }
        closing.forEach(WorkspaceSession::close)
    }

    private fun loginCredentials(platformUrl: String) = CredentialAttributes(generateServiceName("DATAMIMIC", "login $platformUrl"))

    companion object {
        private val LOG = logger<DatamimicPlatform>()
        private const val PLATFORM_URL_KEY = "datamimic.platformUrl"
        private const val EMAIL_KEY = "datamimic.email"
        private const val REMEMBER_PASSWORD_KEY = "datamimic.rememberPassword"

        fun getInstance(): DatamimicPlatform = service()
    }
}

/** The IDE's proxy routing and trusted certificates, without treating platform 401s as JDK auth challenges. */
// ponytail: authenticated proxies (407) stay unsupported; add proxy-only authentication when a customer requires it.
internal fun platformHttpClient(): HttpClient = HttpClient.newBuilder()
    .proxy(JdkProxyProvider.getInstance().proxySelector)
    .sslContext(CertificateManager.getInstance().sslContext)
    .connectTimeout(Duration.ofSeconds(15))
    .build()

/** The file on disk behind [this], or null for files that are not local (or not mappable, as in light tests). */
internal fun VirtualFile.toNioPathOrNull(): Path? = if (isInLocalFileSystem) fileSystem.getNioPath(this) else null

/** Tells the sync what the editors hold, so a download never replaces text the user has not saved. */
private object IdeEditor : LocalEditor {
    override fun hasUnsavedEdits(file: Path): Boolean = ReadAction.compute<Boolean, RuntimeException> {
        LocalFileSystem.getInstance().findFileByNioFile(file)?.let(FileDocumentManager.getInstance()::isFileModified) ?: false
    }

    override fun filesChanged(files: Collection<Path>) {
        val fs = LocalFileSystem.getInstance()
        // WHY: new files are not known to the IDE yet; refreshing their nearest known folder finds them.
        val known = files.mapNotNull { file -> generateSequence(file) { it.parent }.firstNotNullOfOrNull(fs::findFileByNioFile) }.distinct()
        VfsUtil.markDirtyAndRefresh(true, true, true, *known.toTypedArray())
    }
}

internal fun Throwable.signedOutNotice(): String = when (this) {
    is SessionExpiredException -> message.orEmpty()
    else -> "Could not reach the platform: ${message ?: javaClass.simpleName}"
}
