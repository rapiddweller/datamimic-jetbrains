// DATAMIMIC for JetBrains IDEs
// Copyright (c) 2026 Rapiddweller Asia Co., Ltd.
// SPDX-License-Identifier: MIT

package com.rapiddweller.datamimic.core

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets.UTF_8
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * In-process stand-in that enforces the platform rules the plugin depends on: the session cookie, the exact Origin on
 * unsafe requests, flat `{detail, code}` errors, ETag preconditions, and edit locks owned by a client binding
 * (acquire is idempotent for the owner, as on the platform).
 */
class FakePlatform : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val origin = PlatformOrigin("http://127.0.0.1:${server.address.port}")

    var password = "secret"
    var sessionId = "session-1"
    val revokedSessions = mutableListOf<String>()
    val seenBindings = mutableListOf<String?>()
    val projectPages = mutableListOf<String>()

    /** path → (content, etag) of project "p1". */
    val files: MutableMap<String, Pair<String, String>> = ConcurrentHashMap(mapOf("model/datamimic.xml" to ("<setup/>" to "etag-1")))
    val uploads = CopyOnWriteArrayList<Triple<String, String?, String?>>()
    val creates = CopyOnWriteArrayList<String>()
    val deletes = CopyOnWriteArrayList<String>()
    val moves = CopyOnWriteArrayList<Pair<String, String>>()
    val directories = CopyOnWriteArrayList<String>()

    /** Files shared from a global project: listed read-only. */
    val readOnly: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** The edit lock of "p1" files: owning client binding and its generation. */
    @Volatile var lockOwner: String? = null
    @Volatile var lockGeneration: String? = null
    private var generations = 0L

    /** The next N tree reads fail with 500. */
    var failingTreeReads = 0

    /** When set, an upload waits here before it is processed. */
    var uploadGate: CountDownLatch? = null

    /** When set, an acquire waits here before it is processed. */
    var acquireGate: CountDownLatch? = null

    /** When true, deletes fail as if someone changed the file in between. */
    var rejectDeletes = false

    /** Project access tokens of "p1": name → (secret, expiration as the platform serializes it). */
    val projectTokens = linkedMapOf<String, Pair<String, String>>()
    private var tokenSerial = 0

    @Volatile var acquireCount = 0
    @Volatile var uploadsReceived = 0

    init {
        server.createContext("/api/v2/session/login") { exchange ->
            if (!originOk(exchange)) return@createContext exchange.error(403, "AUTH_ORIGIN_FORBIDDEN", "Browser request origin is not allowed")
            val form = exchange.form()
            if (form["password"] != password) return@createContext exchange.error(401, "AUTH_INVALID_CREDENTIALS", "Incorrect email or password")
            exchange.responseHeaders.add("Set-Cookie", "datamimic_session=$sessionId; HttpOnly; Max-Age=2592000; Path=/; SameSite=lax")
            exchange.respond(204, "")
        }
        server.createContext("/api/v2/session/logout") { exchange ->
            cookie(exchange)?.let(revokedSessions::add)
            exchange.respond(204, "")
        }
        authenticated("/api/v2/me") { it.respond(200, """{"id":7,"email":"ada@example.com","first_name":"Ada"}""") }
        authenticated("/api/v2/projects/search") { exchange ->
            val page = Regex(""""page":(\d+)""").find(exchange.body())!!.groupValues[1].toInt()
            exchange.respond(200, projectPages[page - 1])
        }
        authenticated("/api/v2/projects/p1/workspace/tree") { exchange ->
            if (failingTreeReads > 0) {
                failingTreeReads--
                return@authenticated exchange.error(500, "INTERNAL_SERVER_ERROR", "Tree temporarily unavailable")
            }
            val entries = files.entries.joinToString(",") { (path, value) ->
                val source = if (path in readOnly) "global" else "current"
                """{"path":"$path","workspace_path":"$path","kind":"file","source":"$source","source_project_id":"p1","source_project_name":"P1","hidden":false,"readonly":${path in readOnly},"size":1,"modified_at":null,"etag":"${value.second}"}"""
            }
            exchange.respond(200, """{"project_id":"p1","revision":"r1","entries":[$entries]}""")
        }
        authenticated("/api/v2/projects/p1/workspace/files/") { exchange ->
            val path = exchange.projectPath("/api/v2/projects/p1/workspace/files/")
            val current = files[path]
            when (exchange.requestMethod) {
                "GET" -> {
                    if (current == null) return@authenticated exchange.error(404, "NOT_FOUND", "missing")
                    exchange.responseHeaders.add("ETag", current.second)
                    exchange.respond(200, Base64.getEncoder().encodeToString(current.first.toByteArray()))
                }
                else -> {
                    uploadsReceived++
                    uploadGate?.await(5, TimeUnit.SECONDS)
                    val content = exchange.body().substringAfter("\r\n\r\n").substringBeforeLast("\r\n--")
                    if (exchange.requestHeaders.getFirst("If-None-Match") == "*") {
                        creates += path
                        if (current != null) return@authenticated exchange.error(409, "CONFLICT", "Create-only project file already exists")
                        files[path] = content to "etag-1"
                        return@authenticated exchange.respond(200, """{"path":"$path","action":"created"}""")
                    }
                    val ifMatch = exchange.requestHeaders.getFirst("If-Match")
                    val generation = exchange.requestHeaders.getFirst("X-DATAMIMIC-Lock-Generation")
                    uploads += Triple(path, ifMatch, generation)
                    if (current == null || ifMatch != current.second) {
                        return@authenticated exchange.error(412, "FILE_ETAG_MISMATCH", "The file changed after it was read.")
                    }
                    if (!ownsLock(exchange, generation)) return@authenticated exchange.error(409, "FILE_LOCK_REQUIRED", "Acquire an edit lock first.")
                    files[path] = content to "etag-${current.second.removePrefix("etag-").toInt() + 1}"
                    exchange.respond(200, """{"path":"$path","action":"updated"}""")
                }
            }
        }
        authenticated("/api/v2/projects/p1/workspace/entries/move") { exchange ->
            val body = exchange.body()
            val field = { name: String -> Regex(""""$name":"([^"]+)"""").find(body)!!.groupValues[1] }
            val (source, destination) = field("source_path") to field("destination_path")
            if (field("kind") == "file") {
                val current = files[source] ?: return@authenticated exchange.error(404, "NOT_FOUND", "missing")
                if (exchange.requestHeaders.getFirst("If-Match") != current.second) {
                    return@authenticated exchange.error(412, "FILE_ETAG_MISMATCH", "The file changed after it was read.")
                }
                if (!ownsLock(exchange, exchange.requestHeaders.getFirst("X-DATAMIMIC-Lock-Generation"))) {
                    return@authenticated exchange.error(409, "FILE_LOCK_REQUIRED", "Acquire an edit lock first.")
                }
                files[destination] = files.remove(source)!!
            } else {
                val under = files.keys.filter { it.startsWith("$source/") }
                if (under.isEmpty()) return@authenticated exchange.error(404, "NOT_FOUND", "missing")
                under.forEach { files[destination + it.removePrefix(source)] = files.remove(it)!! }
            }
            moves += source to destination
            exchange.respond(200, """{"path":"$destination","action":"moved"}""")
        }
        authenticated("/api/v2/projects/p1/workspace/entries/") { exchange ->
            val path = exchange.projectPath("/api/v2/projects/p1/workspace/entries/")
            if (exchange.requestURI.query.orEmpty().contains("kind=directory")) {
                val under = files.keys.filter { it.startsWith("$path/") }
                if (under.isEmpty()) return@authenticated exchange.error(404, "NOT_FOUND", "missing")
                under.forEach(files::remove)
                deletes += path
                return@authenticated exchange.respond(200, """{"path":"$path","action":"deleted"}""")
            }
            val current = files[path] ?: return@authenticated exchange.error(404, "NOT_FOUND", "missing")
            if (rejectDeletes || exchange.requestHeaders.getFirst("If-Match") != current.second) {
                return@authenticated exchange.error(412, "FILE_ETAG_MISMATCH", "The file changed after it was read.")
            }
            if (!ownsLock(exchange, exchange.requestHeaders.getFirst("X-DATAMIMIC-Lock-Generation"))) {
                return@authenticated exchange.error(409, "FILE_LOCK_REQUIRED", "Acquire an edit lock first.")
            }
            files.remove(path)
            deletes += path
            exchange.respond(200, """{"path":"$path","action":"deleted"}""")
        }
        authenticated("/api/v2/projects/p1/workspace/directories/") { exchange ->
            directories += exchange.projectPath("/api/v2/projects/p1/workspace/directories/")
            exchange.respond(200, """{"path":"x","action":"created"}""")
        }
        authenticated("/api/v2/projects/p1/workspace/locks/") { exchange ->
            val binding = exchange.requestHeaders.getFirst("X-DATAMIMIC-Client-Binding")
            val generation = exchange.requestHeaders.getFirst("X-DATAMIMIC-Lock-Generation")
            when (exchange.requestURI.path.substringAfterLast('/')) {
                "acquire" -> {
                    acquireGate?.await(5, TimeUnit.SECONDS)
                    acquireCount++
                    if (lockOwner != null && lockOwner != binding) {
                        return@authenticated exchange.error(409, "FILE_LOCK_OWNED_BY_OTHER", "Another client owns this file lock.")
                    }
                    if (lockOwner == null) {
                        lockOwner = binding
                        lockGeneration = (++generations).toString()
                    }
                    exchange.respond(200, grant(lockGeneration.orEmpty()))
                }
                "heartbeat" -> {
                    if (!ownsLock(exchange, generation)) return@authenticated exchange.error(409, "FILE_LOCK_SUPERSEDED", "The file lock changed.")
                    exchange.respond(200, grant(lockGeneration.orEmpty()))
                }
                else -> {
                    if (ownsLock(exchange, generation)) {
                        lockOwner = null
                        lockGeneration = null
                    }
                    exchange.respond(200, """{"path":"x","action":"released"}""")
                }
            }
        }
        authenticated("/api/v2/projects/p1/project-access-tokens") { exchange ->
            val name = exchange.projectPath("/api/v2/projects/p1/project-access-tokens").removePrefix("/")
            when (exchange.requestMethod) {
                "GET" -> exchange.respond(200, projectTokens.entries.joinToString(",", "[", "]") { (tokenName, value) -> tokenJson(tokenName, value) })
                "DELETE" -> if (projectTokens.remove(name) == null) exchange.error(404, "NOT_FOUND", "missing") else exchange.respond(204, "")
                else -> {
                    val body = exchange.body()
                    val requested = Regex(""""name":"([^"]+)"""").find(body)!!.groupValues[1]
                    if (requested in projectTokens) return@authenticated exchange.error(400, "BAD_REQUEST", "Token name exists")
                    // The platform stores naive UTC timestamps and returns them without an offset.
                    val expires = Regex(""""expiration_date":"([^"]+)"""").find(body)!!.groupValues[1].removeSuffix("Z")
                    projectTokens[requested] = "secret-${++tokenSerial}" to expires
                    exchange.respond(201, tokenJson(requested, projectTokens.getValue(requested)))
                }
            }
        }
        server.start()
    }

    private fun tokenJson(name: String, value: Pair<String, String>) =
        """{"tc_creation":"2026-09-23T00:00:00","name":"$name","scopes":["api"],"expiration_date":"${value.second}","token":"${value.first}"}"""

    private fun ownsLock(exchange: HttpExchange, generation: String?) =
        lockOwner != null && lockOwner == exchange.requestHeaders.getFirst("X-DATAMIMIC-Client-Binding") && generation == lockGeneration

    private fun grant(generation: String) =
        """{"path":"x","action":"acquired","generation":"$generation","lease_ttl_seconds":300,"renew_after_seconds":100}"""

    private fun authenticated(path: String, handler: (HttpExchange) -> Unit) = server.createContext(path) { exchange ->
        seenBindings += exchange.requestHeaders.getFirst("X-DATAMIMIC-Client-Binding")
        when {
            cookie(exchange) != sessionId -> exchange.error(401, "AUTH_REQUIRED", "Not authenticated")
            exchange.requestMethod != "GET" && !originOk(exchange) -> exchange.error(403, "AUTH_ORIGIN_FORBIDDEN", "Browser request origin is not allowed")
            else -> handler(exchange)
        }
    }

    private fun originOk(exchange: HttpExchange) = exchange.requestHeaders.getFirst("Origin") == origin.value

    private fun cookie(exchange: HttpExchange): String? = exchange.requestHeaders.getFirst("Cookie")
        ?.split(";")
        ?.map(String::trim)
        ?.firstOrNull { it.startsWith("datamimic_session=") }
        ?.substringAfter('=')

    override fun close() = server.stop(0)
}

/** Percent-decodes the path like the platform's router: `+` stays a plus sign. */
private fun HttpExchange.projectPath(prefix: String): String = requestURI.path.removePrefix(prefix)

private fun HttpExchange.body(): String = requestBody.readAllBytes().toString(UTF_8)

private fun HttpExchange.form(): Map<String, String> = body().split("&").associate {
    val (key, value) = it.split("=", limit = 2)
    URLDecoder.decode(key, UTF_8) to URLDecoder.decode(value, UTF_8)
}

private fun HttpExchange.error(status: Int, code: String, detail: String) = respond(status, """{"detail":"$detail","code":"$code"}""")

private fun HttpExchange.respond(status: Int, body: String) {
    val bytes = body.toByteArray(UTF_8)
    sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
    if (bytes.isNotEmpty()) responseBody.use { it.write(bytes) } else close()
}
