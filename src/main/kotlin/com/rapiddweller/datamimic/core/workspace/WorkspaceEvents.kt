// DATAMIMIC for JetBrains IDEs
// Copyright (c) 2026 Rapiddweller Asia Co., Ltd.
// SPDX-License-Identifier: MIT

@file:OptIn(ExperimentalSerializationApi::class)

package com.rapiddweller.datamimic.core.workspace

import com.rapiddweller.datamimic.core.SessionService
import com.rapiddweller.datamimic.core.StoredSession
import com.rapiddweller.datamimic.core.encode
import com.rapiddweller.datamimic.core.handshakeStatus
import com.rapiddweller.datamimic.core.json
import com.rapiddweller.datamimic.core.openPlatformWebSocket
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator
import java.net.http.HttpClient
import java.net.http.WebSocket
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

enum class StreamState {
    IDLE,
    CONNECTING,
    LIVE,
    UNAVAILABLE,

    /** The platform refused the connection (access denied); retrying on its own would only be refused again. */
    REFUSED,
    SUPERSEDED,
    CLOSED,
}

/**
 * Receive-only workspace event stream of one platform project. Lock state is unknown until the first snapshot,
 * so callers must treat files as read-only while the stream is not [StreamState.LIVE].
 *
 * Connection state lives on the single [scheduler] thread; socket ownership is synchronized with WebSocket callbacks.
 */
class WorkspaceEventStream(
    private val http: HttpClient,
    private val sessions: SessionService,
    private val clientBindingId: String,
    private val projectId: String,
    private val onEvent: (WorkspaceEvent) -> Unit,
    private val onState: (StreamState) -> Unit,
    /** Diagnostics for the IDE log: why the stream is not live. */
    private val log: (String) -> Unit = {},
    /** Waits before each reconnect; the last one repeats for as long as the platform stays unreachable. */
    private val reconnectDelaysMs: List<Long> = RECONNECT_DELAYS_MS,
) {
    init {
        require(reconnectDelaysMs.isNotEmpty()) { "Reconnect delays must not be empty." }
    }

    private val scheduler = Executors.newSingleThreadScheduledExecutor { Thread(it, "DATAMIMIC workspace events").apply { isDaemon = true } }
    private val socketLock = Any()
    private var socket: WebSocket? = null
    private var watchdog: ScheduledFuture<*>? = null
    private var reconnect: ScheduledFuture<*>? = null
    private var lastMessageAt = System.nanoTime()
    private var attempt = 0
    private var state = StreamState.IDLE
    @Volatile private var closed = false
    @Volatile private var connectGeneration = 0L

    /** Connects, or reconnects after the stream gave up; a superseded or closed stream stays down. */
    fun start() {
        if (closed) return
        onScheduler {
            if (closed) return@onScheduler
            if (state != StreamState.IDLE && state != StreamState.UNAVAILABLE && state != StreamState.REFUSED) return@onScheduler
            reconnect?.cancel(false)
            attempt = 0
            setState(StreamState.CONNECTING)
            connect()
        }
    }

    /** Final: the stream never connects again, and its thread ends. */
    fun close() {
        closed = true
        synchronized(socketLock) {
            socket?.abort()
            socket = null
        }
        onScheduler {
            setState(StreamState.CLOSED)
            stopSocket()
            // WHY: shutdown() would still run an already scheduled reconnect.
            scheduler.shutdownNow()
        }
    }

    private fun connect() {
        if (closed || state == StreamState.SUPERSEDED) return
        val generation = ++connectGeneration
        val session = sessions.current() ?: return setState(StreamState.UNAVAILABLE)
        // WHY: the slow retries of an unreachable platform stay UNAVAILABLE, so banners do not flicker with each attempt.
        if (state != StreamState.UNAVAILABLE) setState(StreamState.CONNECTING)
        val path = "/api/v2/projects/${encode(projectId)}/workspace/events?client_binding_id=${encode(clientBindingId)}"
        http.openPlatformWebSocket(session, clientBindingId, path, Listener(generation))
            .whenComplete { _, error -> if (error != null) onScheduler { if (!closed && generation == connectGeneration) onConnectFailed(session, error) } }
    }

    private fun onConnectFailed(session: StoredSession, error: Throwable) {
        val status = error.handshakeStatus()
        log("Live updates of $projectId did not connect: ${status?.let { "HTTP $it" } ?: error.cause ?: error}")
        when (status) {
            UNAUTHORIZED -> {
                setState(StreamState.UNAVAILABLE)
                sessions.expire(session)
            }
            // WHY: the platform refuses before accepting, which reaches the client as HTTP 403, not a close code.
            FORBIDDEN -> setState(StreamState.REFUSED)
            else -> scheduleReconnect()
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

    /** Repeats the final delay until the platform answers; only a refusal, a newer stream or [close] end retries. */
    private fun scheduleReconnect() {
        if (closed || state == StreamState.SUPERSEDED) return
        val delay = reconnectDelaysMs.getOrElse(attempt++) { reconnectDelaysMs.last() }
        setState(if (attempt > reconnectDelaysMs.size) StreamState.UNAVAILABLE else StreamState.CONNECTING)
        reconnect = scheduler.schedule(::connect, delay, TimeUnit.MILLISECONDS)
    }

    private fun stopSocket() {
        watchdog?.cancel(false)
        watchdog = null
        synchronized(socketLock) {
            socket?.abort()
            socket = null
        }
    }

    private fun setState(next: StreamState) {
        if (state == next) return
        state = next
        log("Live updates of $projectId: $next")
        onState(next)
    }

    private inner class Listener(private val generation: Long) : WebSocket.Listener {
        private val buffer = StringBuilder()

        override fun onOpen(webSocket: WebSocket) {
            // WHY: runs before any onText is queued, so the first snapshot is never dropped.
            val adopted = synchronized(socketLock) {
                if (closed || generation != connectGeneration) {
                    false
                } else {
                    socket?.abort()
                    socket = webSocket
                    true
                }
            }
            if (!adopted) {
                webSocket.abort()
                return
            }
            webSocket.request(1)
        }

        override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
            buffer.append(data)
            if (last) {
                val text = buffer.toString()
                buffer.setLength(0)
                onScheduler { if (ownsSocket(webSocket)) onMessage(text) }
            }
            webSocket.request(1)
            return null
        }

        override fun onBinary(webSocket: WebSocket, data: ByteBuffer, last: Boolean): CompletionStage<*>? {
            webSocket.request(1)
            return null
        }

        override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
            onScheduler { if (ownsSocket(webSocket)) onClosed(statusCode) }
            return null
        }

        override fun onError(webSocket: WebSocket, error: Throwable) {
            onScheduler { if (ownsSocket(webSocket)) onClosed(-1) }
        }

        private fun ownsSocket(webSocket: WebSocket): Boolean =
            !closed && generation == connectGeneration && synchronized(socketLock) { socket === webSocket }
    }

    private companion object {
        const val UNAUTHORIZED = 401
        const val FORBIDDEN = 403
        /** Sent by the platform when the same client binding opened a newer stream for this project. */
        const val CLIENT_BINDING_SUPERSEDED = 4001
        val RECONNECT_DELAYS_MS = listOf(250L, 500L, 1_000L, 5_000L, 15_000L, 30_000L)
    }
}
