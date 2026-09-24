// DATAMIMIC for JetBrains IDEs
// Copyright (c) 2026 Rapiddweller Asia Co., Ltd.
// SPDX-License-Identifier: MIT

package com.rapiddweller.datamimic.core.lsp

import com.rapiddweller.datamimic.core.SessionService
import com.rapiddweller.datamimic.core.encode
import com.rapiddweller.datamimic.core.handshakeStatus
import com.rapiddweller.datamimic.core.json
import com.rapiddweller.datamimic.core.openPlatformWebSocket
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.US_ASCII
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CompletionException
import java.util.concurrent.CompletionStage
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Lets an LSP client that speaks stream framing (`Content-Length` headers) over a loopback socket use the platform's
 * hosted language server, which takes one JSON message per WebSocket frame.
 *
 * The port is open to every local process, while the upstream connection carries the user's session. So a client is
 * only connected when its first message is `initialize` with [secret] in its initialization options; the secret is
 * removed before the message goes on.
 */
class LspBridge(
    private val http: HttpClient,
    private val sessions: SessionService,
    private val clientBindingId: String,
    private val projectId: String,
    /** The platform refused the connection: the language server was turned off, or access was denied. */
    private val onRejected: () -> Unit,
    /** Whether the IDE is connected to the platform's language server right now. */
    private val onLink: (LspLink) -> Unit = {},
    /** Diagnostics for the IDE log; the bridge has no other way to say why nothing happens. */
    private val log: (String) -> Unit = {},
    private val pingIntervalMs: Long = PING_INTERVAL_MS,
) : AutoCloseable {
    val secret: String = UUID.randomUUID().toString()

    private val listener = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
    val port: Int = listener.localPort

    @Volatile
    private var client: Socket? = null

    private val pinger = Executors.newSingleThreadScheduledExecutor { Thread(it, "DATAMIMIC language server keepalive").apply { isDaemon = true } }

    init {
        thread(isDaemon = true, name = "DATAMIMIC language server bridge") {
            while (!listener.isClosed) {
                val socket = runCatching { listener.accept() }.getOrNull() ?: break
                client = socket
                runCatching { relay(socket) }
                socket.close()
            }
        }
    }

    override fun close() {
        listener.close()
        client?.close()
        pinger.shutdownNow()
    }

    /** One client at a time; the IDE connects again when it restarts the server. */
    private fun relay(socket: Socket) {
        val input = BufferedInputStream(socket.getInputStream())
        // WHY: a local process that connects and stays silent must not block the IDE's own connection for long.
        socket.soTimeout = FIRST_MESSAGE_TIMEOUT_MS
        val initialize = readMessage(input)?.let(::withoutSecret)
        if (initialize == null) {
            log("Refused a local connection without the IDE's initialize request.")
            return
        }
        socket.soTimeout = 0
        val session = sessions.current() ?: return log("Not signed in; the language server connection was not opened.")
        val path = "/lsp/${encode(projectId)}?client_binding_id=${encode(clientBindingId)}"
        val upstream = try {
            http.openPlatformWebSocket(session, clientBindingId, path, Upstream(socket)).join()
        } catch (e: CompletionException) {
            val status = e.handshakeStatus()
            log("The platform did not open the language server connection: ${status?.let { "HTTP $it" } ?: e.cause?.toString()}")
            // WHY: the platform refuses before accepting, which reaches the client as an HTTP status, not a close code.
            when (status) {
                UNAUTHORIZED -> sessions.expire(session)
                FORBIDDEN -> onRejected()
            }
            return
        }
        log("Language server connection open.")
        onLink(LspLink.OPEN)
        // WHY: proxies close a WebSocket after about a minute without traffic, and an editor is often idle that long.
        val keepalive = pinger.scheduleAtFixedRate({ upstream.sendPing(ByteBuffer.allocate(0)) }, pingIntervalMs, pingIntervalMs, TimeUnit.MILLISECONDS)
        try {
            upstream.sendText(initialize, true).join()
            while (true) upstream.sendText(readMessage(input) ?: break, true).join()
        } catch (_: IOException) {
            // The IDE or the platform went away; both ends are closed below.
        } catch (_: CompletionException) {
        } finally {
            keepalive.cancel(false)
            upstream.abort()
            onLink(LspLink.CLOSED)
        }
    }

    /** The `initialize` request without the bridge's secret, or null when the client is not the IDE this bridge serves. */
    private fun withoutSecret(message: String): String? {
        val request = runCatching { json.parseToJsonElement(message).jsonObject }.getOrNull() ?: return null
        if (request["method"]?.jsonPrimitive?.content != INITIALIZE) return null
        val params = request["params"] as? JsonObject ?: return null
        val options = params[INITIALIZATION_OPTIONS] as? JsonObject ?: return null
        val presented = (options[SECRET_OPTION] as? JsonPrimitive)?.content ?: return null
        if (!MessageDigest.isEqual(presented.toByteArray(UTF_8), secret.toByteArray(UTF_8))) return null
        val forwarded = JsonObject(params + (INITIALIZATION_OPTIONS to JsonObject(options - SECRET_OPTION)))
        return JsonObject(request + ("params" to forwarded)).toString()
    }

    /**
     * Hands each platform message to the IDE with stream framing; a closed platform connection ends the IDE's. The
     * platform sends its messages as binary frames (UTF-8 JSON), so both frame types count.
     */
    private inner class Upstream(private val socket: Socket) : WebSocket.Listener {
        private val pendingText = StringBuilder()
        private val pendingBytes = ByteArrayOutputStream()
        private val output: OutputStream = socket.getOutputStream()

        override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
            pendingText.append(data)
            if (last) {
                deliver(pendingText.toString().toByteArray(UTF_8))
                pendingText.setLength(0)
            }
            webSocket.request(1)
            return null
        }

        override fun onBinary(webSocket: WebSocket, data: ByteBuffer, last: Boolean): CompletionStage<*>? {
            val chunk = ByteArray(data.remaining()).also(data::get)
            pendingBytes.write(chunk)
            if (last) {
                deliver(pendingBytes.toByteArray())
                pendingBytes.reset()
            }
            webSocket.request(1)
            return null
        }

        private fun deliver(body: ByteArray) {
            runCatching {
                output.write("Content-Length: ${body.size}\r\n\r\n".toByteArray(US_ASCII))
                output.write(body)
                output.flush()
            }.onFailure { socket.close() }
        }

        override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
            log("The platform closed the language server connection: $statusCode $reason")
            if (statusCode == POLICY_VIOLATION) onRejected()
            socket.close()
            return null
        }

        override fun onError(webSocket: WebSocket, error: Throwable) {
            log("The language server connection failed: $error")
            socket.close()
        }
    }

    companion object {
        /** Key of the bridge's secret in the `initialize` request's initialization options. */
        const val SECRET_OPTION = "datamimic_ide_bridge_secret"

        private const val INITIALIZE = "initialize"
        private const val INITIALIZATION_OPTIONS = "initializationOptions"
        private const val POLICY_VIOLATION = 1008
        private const val UNAUTHORIZED = 401
        private const val FORBIDDEN = 403
        private const val FIRST_MESSAGE_TIMEOUT_MS = 10_000
        private const val PING_INTERVAL_MS = 20_000L
    }
}

enum class LspLink { OPEN, CLOSED }

/** Reads one message in LSP stream framing; null at the end of the stream. */
internal fun readMessage(input: InputStream): String? {
    var length: Int? = null
    while (true) {
        val line = readHeaderLine(input) ?: return null
        if (line.isEmpty()) break
        if (line.substringBefore(':').trim().equals("Content-Length", ignoreCase = true)) length = line.substringAfter(':').trim().toIntOrNull()
    }
    val size = length?.takeIf { it in 0..MAX_FRAMED_BYTES } ?: throw IOException("Invalid Content-Length.")
    val body = input.readNBytes(size)
    if (body.size < size) return null
    return String(body, UTF_8)
}

private const val MAX_FRAMED_BYTES = 16 * 1024 * 1024

private fun readHeaderLine(input: InputStream): String? {
    val line = StringBuilder()
    while (true) {
        val next = input.read()
        if (next == -1) return null
        if (next == '\n'.code) return line.toString().removeSuffix("\r")
        line.append(next.toChar())
        if (line.length > MAX_HEADER_LINE) throw IOException("Header line too long.")
    }
}

private const val MAX_HEADER_LINE = 1_024
