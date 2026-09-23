// DATAMIMIC for JetBrains IDEs
// Copyright (c) 2026 Rapiddweller Asia Co., Ltd.
// SPDX-License-Identifier: MIT

@file:OptIn(ExperimentalSerializationApi::class)

package com.rapiddweller.datamimic.core.workspace

import com.rapiddweller.datamimic.core.PlatformHeader
import com.rapiddweller.datamimic.core.SESSION_COOKIE
import com.rapiddweller.datamimic.core.SessionService
import com.rapiddweller.datamimic.core.StoredSession
import com.rapiddweller.datamimic.core.encode
import com.rapiddweller.datamimic.core.json
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.net.http.WebSocketHandshakeException
import java.nio.ByteBuffer
import java.util.concurrent.CompletionStage
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

@Serializable
@JsonClassDiscriminator("type")
sealed interface WorkspaceEvent {
    @Serializable
    @SerialName("ping")
    data object Ping : WorkspaceEvent

    @Serializable
    @SerialName("snapshot")
    data class Snapshot(
        @SerialName("tree_revision") val treeRevision: String,
        val transport: Transport,
        val locks: Map<String, LockProjection>,
    ) : WorkspaceEvent

    @Serializable
    @SerialName("collaboration_changed")
    data class CollaborationChanged(val locks: Map<String, LockProjection>) : WorkspaceEvent

    @Serializable
    @SerialName("workspace_changed")
    data class WorkspaceChanged(val changes: List<Change>) : WorkspaceEvent
}

@Serializable
data class Transport(
    @SerialName("keepalive_interval_seconds") val keepaliveIntervalSeconds: Long,
    @SerialName("stale_after_seconds") val staleAfterSeconds: Long,
)

@Serializable
data class Change(val path: String, @SerialName("previous_path") val previousPath: String? = null)

/** How a lock holder relates to this IDE; computed by the platform from the stream's client binding. */
@Serializable
enum class OwnerRelation {
    @SerialName("this_client") THIS_CLIENT,
    @SerialName("same_user_other_client") SAME_USER_OTHER_CLIENT,
    @SerialName("other_user") OTHER_USER,
    @SerialName("project_token") PROJECT_TOKEN,
}

@Serializable
@JsonClassDiscriminator("state")
sealed interface LockProjection {
    @Serializable
    @SerialName("free")
    data object Free : LockProjection

    @Serializable
    @SerialName("held")
    data class Held(
        @SerialName("owner_name") val ownerName: String? = null,
        @SerialName("owner_relation") val ownerRelation: OwnerRelation,
        @SerialName("lock_generation") val lockGeneration: String,
        @SerialName("renew_after_seconds") val renewAfterSeconds: Int,
    ) : LockProjection
}

enum class StreamState { IDLE, CONNECTING, LIVE, UNAVAILABLE, SUPERSEDED, CLOSED }

/**
 * Receive-only workspace event stream of one platform project. Lock state is unknown until the first snapshot,
 * so callers must treat files as read-only while the stream is not [StreamState.LIVE].
 *
 * All mutable state lives on the single [scheduler] thread; WebSocket callbacks only hand work over to it.
 */
class WorkspaceEventStream(
    private val http: HttpClient,
    private val sessions: SessionService,
    private val clientBindingId: String,
    private val projectId: String,
    private val onEvent: (WorkspaceEvent) -> Unit,
    private val onState: (StreamState) -> Unit,
) {
    private val scheduler = Executors.newSingleThreadScheduledExecutor { Thread(it, "DATAMIMIC workspace events").apply { isDaemon = true } }
    private var socket: WebSocket? = null
    private var watchdog: ScheduledFuture<*>? = null
    private var lastMessageAt = System.nanoTime()
    private var attempt = 0
    private var state = StreamState.IDLE

    /** Connects, or reconnects after the stream gave up; a superseded or closed stream stays down. */
    fun start() {
        onScheduler {
            if (state != StreamState.IDLE && state != StreamState.UNAVAILABLE) return@onScheduler
            attempt = 0
            connect()
        }
    }

    /** Final: the stream never connects again, and its thread ends. */
    fun close() {
        onScheduler {
            setState(StreamState.CLOSED)
            stopSocket()
            // WHY: shutdown() would still run an already scheduled reconnect.
            scheduler.shutdownNow()
        }
    }

    private fun connect() {
        if (state == StreamState.CLOSED || state == StreamState.SUPERSEDED) return
        val session = sessions.current() ?: return setState(StreamState.UNAVAILABLE)
        setState(StreamState.CONNECTING)
        val url = session.origin.webSocketUrl(
            "/api/v2/projects/${encode(projectId)}/workspace/events?client_binding_id=${encode(clientBindingId)}",
        )
        http.newWebSocketBuilder()
            .header(PlatformHeader.ORIGIN.wireName, session.origin.value)
            .header(PlatformHeader.COOKIE.wireName, "$SESSION_COOKIE=${session.sessionId}")
            .header(PlatformHeader.CLIENT_BINDING.wireName, clientBindingId)
            .buildAsync(url, Listener())
            .whenComplete { _, error -> if (error != null) onScheduler { onConnectFailed(session, error) } }
    }

    private fun onConnectFailed(session: StoredSession, error: Throwable) {
        val rejected = generateSequence(error) { it.cause }.filterIsInstance<WebSocketHandshakeException>().firstOrNull()
        if (rejected?.response?.statusCode() == 401) {
            setState(StreamState.UNAVAILABLE)
            sessions.expire(session)
        } else {
            scheduleReconnect()
        }
    }

    /** Runs [task] on the stream thread; after [close] there is nothing left to do, so the task is dropped. */
    private fun onScheduler(task: () -> Unit): Boolean =
        try {
            scheduler.execute(task)
            true
        } catch (_: RejectedExecutionException) {
            false
        }

    private fun onMessage(text: String) {
        lastMessageAt = System.nanoTime()
        val event = runCatching { json.decodeFromString<WorkspaceEvent>(text) }.getOrNull() ?: return
        if (event !is WorkspaceEvent.Ping) attempt = 0
        if (event is WorkspaceEvent.Snapshot) {
            setState(StreamState.LIVE)
            startWatchdog(event.transport)
        }
        onEvent(event)
    }

    private fun startWatchdog(transport: Transport) {
        watchdog?.cancel(false)
        watchdog = scheduler.scheduleWithFixedDelay({
            val silentSeconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - lastMessageAt)
            if (silentSeconds > transport.staleAfterSeconds) {
                stopSocket()
                scheduleReconnect()
            }
        }, transport.keepaliveIntervalSeconds, transport.keepaliveIntervalSeconds, TimeUnit.SECONDS)
    }

    private fun onClosed(code: Int) {
        stopSocket()
        when {
            state == StreamState.CLOSED -> Unit
            code == CLIENT_BINDING_SUPERSEDED -> setState(StreamState.SUPERSEDED)
            else -> scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        if (state == StreamState.CLOSED || state == StreamState.SUPERSEDED) return
        val delay = RECONNECT_DELAYS_MS.getOrNull(attempt++) ?: return setState(StreamState.UNAVAILABLE)
        setState(StreamState.CONNECTING)
        scheduler.schedule(::connect, delay, TimeUnit.MILLISECONDS)
    }

    private fun stopSocket() {
        watchdog?.cancel(false)
        watchdog = null
        socket?.abort()
        socket = null
    }

    private fun setState(next: StreamState) {
        if (state == next) return
        state = next
        onState(next)
    }

    private inner class Listener : WebSocket.Listener {
        private val buffer = StringBuilder()

        override fun onOpen(webSocket: WebSocket) {
            // WHY: runs before any onText is queued, so the first snapshot is never dropped.
            val adopted = onScheduler { if (state == StreamState.CLOSED) webSocket.abort() else socket = webSocket }
            if (adopted) webSocket.request(1) else webSocket.abort()
        }

        override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
            buffer.append(data)
            if (last) {
                val text = buffer.toString()
                buffer.setLength(0)
                onScheduler { if (socket === webSocket) onMessage(text) }
            }
            webSocket.request(1)
            return null
        }

        override fun onBinary(webSocket: WebSocket, data: ByteBuffer, last: Boolean): CompletionStage<*>? {
            webSocket.request(1)
            return null
        }

        override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
            onScheduler { if (socket === webSocket) onClosed(statusCode) }
            return null
        }

        override fun onError(webSocket: WebSocket, error: Throwable) {
            onScheduler { if (socket === webSocket) onClosed(-1) }
        }
    }

    private companion object {
        /** Sent by the platform when the same client binding opened a newer stream for this project. */
        const val CLIENT_BINDING_SUPERSEDED = 4001
        val RECONNECT_DELAYS_MS = listOf(250L, 500L, 1_000L, 5_000L, 15_000L)
    }
}
