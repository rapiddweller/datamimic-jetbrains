// DATAMIMIC for JetBrains IDEs
// Copyright (c) 2026 Rapiddweller Asia Co., Ltd.
// SPDX-License-Identifier: MIT

package com.rapiddweller.datamimic.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Duration
import java.util.UUID

internal val json = Json {
    ignoreUnknownKeys = true
    // WHY: unknown enum members (a newer platform) fall back to the property's default instead of failing.
    coerceInputValues = true
}

/** The platform's public origin. Cookie sessions accept unsafe requests only when they carry exactly this Origin. */
@Serializable
@JvmInline
value class PlatformOrigin(val value: String) {
    companion object {
        fun parse(raw: String): PlatformOrigin {
            val uri = runCatching { URI(raw.trim()) }.getOrElse { throw IllegalArgumentException("This is not a valid URL.") }
            require(uri.scheme?.lowercase() == "http" || uri.scheme?.lowercase() == "https") { "The platform URL must start with http:// or https://." }
            require(uri.host != null) { "The platform URL must contain a host name." }
            require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null) {
                "The platform URL must not contain credentials, a query, or a fragment."
            }
            require(uri.rawPath.isNullOrEmpty() || uri.rawPath == "/") { "Use the platform's public URL without a path." }
            val scheme = uri.scheme.lowercase()
            // WHY: the platform compares the Origin header byte-for-byte, and browsers omit default ports.
            val defaultPort = if (scheme == "https") 443 else 80
            val port = if (uri.port == -1 || uri.port == defaultPort) "" else ":${uri.port}"
            return PlatformOrigin("$scheme://${uri.host.lowercase()}$port")
        }
    }

    fun webSocketUrl(pathAndQuery: String): URI {
        val uri = URI(value)
        val scheme = if (uri.scheme == "https") "wss" else "ws"
        return URI("$scheme://${uri.rawAuthority}$pathAndQuery")
    }
}

enum class HttpMethod(val timeout: Duration) {
    GET(Duration.ofSeconds(15)),
    POST(Duration.ofSeconds(60)),
    PUT(Duration.ofSeconds(60)),
    DELETE(Duration.ofSeconds(60)),
}

/** Every header name the client exchanges with the platform. */
enum class PlatformHeader(val wireName: String) {
    ORIGIN("Origin"),
    AUTHORIZATION("Authorization"),
    COOKIE("Cookie"),
    SET_COOKIE("Set-Cookie"),
    CONTENT_TYPE("Content-Type"),
    ETAG("ETag"),
    IF_MATCH("If-Match"),
    IF_NONE_MATCH("If-None-Match"),
    CLIENT_BINDING("X-DATAMIMIC-Client-Binding"),
    LOCK_GENERATION("X-DATAMIMIC-Lock-Generation"),
}

sealed interface RequestBody {
    data object Empty : RequestBody

    data class Json(val text: String) : RequestBody

    data class Form(val fields: Map<String, String>) : RequestBody

    class Multipart(val fileName: String, val bytes: ByteArray) : RequestBody
}

/** Error codes the client reacts to. Anything else decodes as [UNKNOWN]. */
@Serializable
enum class PlatformErrorCode {
    AUTH_ORIGIN_FORBIDDEN,
    CONFLICT,
    NOT_FOUND,
    FILE_ETAG_MISMATCH,
    FILE_LOCK_REQUIRED,
    FILE_LOCK_OWNER_MISMATCH,
    FILE_LOCK_OWNED_BY_OTHER,
    FILE_LOCK_SUPERSEDED,
    LSP_DISABLED,
    UNKNOWN,
}

@Serializable
private data class PlatformErrorBody(val detail: String? = null, val code: PlatformErrorCode = PlatformErrorCode.UNKNOWN)

class PlatformException(val status: Int, val code: PlatformErrorCode, message: String) : RuntimeException(message)

class SessionExpiredException : RuntimeException("Your DATAMIMIC session has ended. Sign in again.")

class PlatformResponse(val status: Int, val body: String, private val headers: java.net.http.HttpHeaders) {
    fun header(name: PlatformHeader): String? = headers.firstValue(name.wireName).orElse(null)

    fun headers(name: PlatformHeader): List<String> = headers.allValues(name.wireName)
}

/** Authenticated transport of one IDE process: session cookie, platform Origin and client binding on every request. */
class PlatformHttp(
    private val http: HttpClient,
    private val sessions: SessionService,
    val clientBindingId: String,
) {
    fun send(
        method: HttpMethod,
        path: String,
        body: RequestBody = RequestBody.Empty,
        headers: Map<PlatformHeader, String> = emptyMap(),
    ): PlatformResponse {
        val session = sessions.current() ?: throw SessionExpiredException()
        val sessionHeaders = mapOf(
            PlatformHeader.COOKIE to "$SESSION_COOKIE=${session.sessionId}",
            PlatformHeader.CLIENT_BINDING to clientBindingId,
        )
        val response = http.sendPlatformRequest(session.origin, method, path, body, headers + sessionHeaders)
        if (response.status == 401) {
            sessions.expire(session)
            throw SessionExpiredException()
        }
        return response.orThrow(method, path)
    }

    fun getJson(path: String): String = send(HttpMethod.GET, path).body

    fun postJson(path: String, body: String): String = send(HttpMethod.POST, path, RequestBody.Json(body)).body
}

internal fun HttpClient.sendPlatformRequest(
    origin: PlatformOrigin,
    method: HttpMethod,
    path: String,
    body: RequestBody,
    headers: Map<PlatformHeader, String>,
): PlatformResponse {
    val builder = HttpRequest.newBuilder(URI(origin.value + path))
        .timeout(method.timeout)
        .header(PlatformHeader.ORIGIN.wireName, origin.value)
    headers.forEach { (name, value) -> builder.header(name.wireName, value) }
    val publisher = when (body) {
        RequestBody.Empty -> HttpRequest.BodyPublishers.noBody()
        is RequestBody.Json -> {
            builder.header(PlatformHeader.CONTENT_TYPE.wireName, "application/json")
            HttpRequest.BodyPublishers.ofString(body.text)
        }
        is RequestBody.Form -> {
            builder.header(PlatformHeader.CONTENT_TYPE.wireName, "application/x-www-form-urlencoded")
            HttpRequest.BodyPublishers.ofString(body.fields.entries.joinToString("&") { (key, value) -> "${encode(key)}=${encode(value)}" })
        }
        is RequestBody.Multipart -> {
            val boundary = "datamimic-${UUID.randomUUID()}"
            builder.header(PlatformHeader.CONTENT_TYPE.wireName, "multipart/form-data; boundary=$boundary")
            HttpRequest.BodyPublishers.ofByteArray(multipart(boundary, body))
        }
    }
    val response = send(builder.method(method.name, publisher).build(), HttpResponse.BodyHandlers.ofString())
    return PlatformResponse(response.statusCode(), response.body(), response.headers())
}

internal fun PlatformResponse.orThrow(method: HttpMethod, path: String): PlatformResponse {
    if (status in 200..299) return this
    val error = runCatching { json.decodeFromString<PlatformErrorBody>(body) }.getOrNull()
    val detail = error?.detail ?: body.take(200).ifBlank { "HTTP $status" }
    throw PlatformException(status, error?.code ?: PlatformErrorCode.UNKNOWN, "$detail (${method.name} $path → $status)")
}

internal fun encode(value: String): String = URLEncoder.encode(value, UTF_8)

/** Encodes each path segment; the platform routes take project-relative POSIX paths. */
internal fun encodePath(path: String): String = path.split("/").joinToString("/") { encode(it).replace("+", "%20") }

private fun multipart(boundary: String, body: RequestBody.Multipart): ByteArray {
    val head = "--$boundary\r\n" +
        "Content-Disposition: form-data; name=\"file\"; filename=\"${body.fileName.replace("\"", "")}\"\r\n" +
        "Content-Type: application/octet-stream\r\n\r\n"
    return head.toByteArray(UTF_8) + body.bytes + "\r\n--$boundary--\r\n".toByteArray(UTF_8)
}
