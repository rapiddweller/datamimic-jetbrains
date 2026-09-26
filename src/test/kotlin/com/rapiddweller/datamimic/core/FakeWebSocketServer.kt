// DATAMIMIC for JetBrains IDEs
// Copyright (c) 2026 Rapiddweller Asia Co., Ltd.
// SPDX-License-Identifier: MIT

package com.rapiddweller.datamimic.core

import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread

/**
 * A minimal RFC 6455 server standing in for the platform's WebSocket routes: it records each handshake and every text
 * frame a client sends, and lets a test send frames back.
 */
class FakeWebSocketServer : AutoCloseable {
    private val server = ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"))
    val origin = PlatformOrigin("http://127.0.0.1:${server.localPort}")
    val handshakes = LinkedBlockingQueue<Map<String, String>>()
    val received = LinkedBlockingQueue<String>()
    val connections = LinkedBlockingQueue<Connection>()

    @Volatile var pings = 0

    /** When set, every handshake is refused with this HTTP status, as the platform does before accepting. */
    @Volatile var refuseWith: Int? = null

    /** When set, the next handshake waits before it upgrades to WebSocket. */
    @Volatile var handshakeGate: CountDownLatch? = null

    /** Accepts connections until closed; [afterHandshake] runs for each, then the client's frames are read. */
    fun serve(afterHandshake: (Connection) -> Unit = {}) = thread(isDaemon = true) {
        while (!server.isClosed) {
            val socket = runCatching { server.accept() }.getOrNull() ?: break
            thread(isDaemon = true) { answer(socket, afterHandshake) }
        }
    }

    override fun close() = server.close()

    class Connection(private val out: OutputStream) {
        @Synchronized
        fun text(text: String) = frame(TEXT_FRAME, text.toByteArray(UTF_8))

        /** A binary frame, as the platform's language server sends its UTF-8 JSON messages. */
        @Synchronized
        fun binary(text: String) = frame(BINARY_FRAME, text.toByteArray(UTF_8))

        private fun frame(opcode: Int, payload: ByteArray) {
            out.write(opcode)
            when {
                payload.size < 126 -> out.write(payload.size)
                else -> {
                    out.write(126)
                    out.write(payload.size shr 8)
                    out.write(payload.size and 0xFF)
                }
            }
            out.write(payload)
            out.flush()
        }

        @Synchronized
        fun close(code: Int) {
            out.write(byteArrayOf(0x88.toByte(), 2, (code shr 8).toByte(), (code and 0xFF).toByte()))
            out.flush()
        }
    }

    private fun answer(socket: Socket, afterHandshake: (Connection) -> Unit) {
        socket.use {
            val input = BufferedInputStream(socket.getInputStream())
            val lines = generateSequence { readLine(input) }.takeWhile { it.isNotEmpty() }.toList()
            val headers = lines.drop(1).associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() } +
                ("request-line" to lines.first())
            handshakes += headers
            synchronized(this) { handshakeGate.also { handshakeGate = null } }?.await(5, java.util.concurrent.TimeUnit.SECONDS)
            refuseWith?.let { status ->
                socket.getOutputStream().write("HTTP/1.1 $status Refused\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                return
            }
            val accept = Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-1").digest((headers.getValue("sec-websocket-key") + WS_GUID).toByteArray()),
            )
            val out = socket.getOutputStream()
            out.write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n".toByteArray())
            out.flush()
            val connection = Connection(out)
            connections += connection
            afterHandshake(connection)
            runCatching { readFrames(input) }
        }
    }

    /** Client frames are masked; text frames go to [received], a close frame ends the connection. */
    private fun readFrames(input: InputStream) {
        while (true) {
            val first = input.read().takeIf { it >= 0 } ?: return
            val second = input.read()
            var length = (second and 0x7F).toLong()
            if (length == 126L) length = ((input.read() shl 8) or input.read()).toLong()
            if (length == 127L) length = (0 until 8).fold(0L) { value, _ -> (value shl 8) or input.read().toLong() }
            val mask = if (second and 0x80 != 0) input.readNBytes(4) else null
            val payload = input.readNBytes(length.toInt())
            mask?.let { for (i in payload.indices) payload[i] = (payload[i].toInt() xor it[i % 4].toInt()).toByte() }
            when (first and 0x0F) {
                TEXT -> received += String(payload, UTF_8)
                PING -> pings++
                CLOSE -> return
            }
        }
    }

    private fun readLine(input: InputStream): String {
        val line = StringBuilder()
        while (true) {
            val next = input.read()
            if (next == -1 || next == '\n'.code) return line.toString().removeSuffix("\r")
            line.append(next.toChar())
        }
    }

    private companion object {
        const val WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        const val TEXT = 0x1
        const val CLOSE = 0x8
        const val PING = 0x9
        const val TEXT_FRAME = 0x81
        const val BINARY_FRAME = 0x82
    }
}
