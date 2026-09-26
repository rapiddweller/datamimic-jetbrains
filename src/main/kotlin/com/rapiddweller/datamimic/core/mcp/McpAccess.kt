// DATAMIMIC for JetBrains IDEs
// Copyright (c) 2026 Rapiddweller Asia Co., Ltd.
// SPDX-License-Identifier: MIT

package com.rapiddweller.datamimic.core.mcp

import com.rapiddweller.datamimic.core.HttpMethod
import com.rapiddweller.datamimic.core.PlatformException
import com.rapiddweller.datamimic.core.PlatformHeader
import com.rapiddweller.datamimic.core.PlatformHttp
import com.rapiddweller.datamimic.core.PlatformOrigin
import com.rapiddweller.datamimic.core.encode
import com.rapiddweller.datamimic.core.encodePath
import com.rapiddweller.datamimic.core.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Base64

@Serializable
private data class ProjectTokenResponse(
    val name: String,
    val token: String,
    @SerialName("expiration_date") val expirationDate: String,
)

class ProjectToken(val name: String, val secret: String, val expiresAt: Instant)

/** Project access tokens: besides its own OAuth, the only credential the platform MCP server accepts. */
class ProjectTokensApi(private val http: PlatformHttp) {
    fun list(projectId: String): List<ProjectToken> =
        json.decodeFromString(ListSerializer(ProjectTokenResponse.serializer()), http.getJson(base(projectId))).map(::toToken)

    fun create(projectId: String, name: String, expiresAt: Instant): ProjectToken {
        val body = buildJsonObject {
            put("name", name)
            putJsonArray("scopes") { add("api") }
            put("expiration_date", expiresAt.toString())
        }
        return toToken(json.decodeFromString(ProjectTokenResponse.serializer(), http.postJson(base(projectId), body.toString())))
    }

    /** A token that is already gone counts as deleted: another window of this IDE may have removed it first. */
    fun delete(projectId: String, name: String) {
        try {
            http.send(HttpMethod.DELETE, "${base(projectId)}/${encodePath(name)}")
        } catch (e: PlatformException) {
            if (e.status != 404) throw e
        }
    }

    private fun base(projectId: String) = "/api/v2/projects/${encode(projectId)}/project-access-tokens"

    private fun toToken(response: ProjectTokenResponse) = ProjectToken(response.name, response.token, parseInstant(response.expirationDate))

    // WHY: the platform stores naive UTC timestamps; depending on the serializer they arrive with or without an offset.
    private fun parseInstant(value: String): Instant =
        runCatching { OffsetDateTime.parse(value).toInstant() }.getOrElse { LocalDateTime.parse(value).toInstant(ZoneOffset.UTC) }
}

/** Everything an IDE agent needs to reach the active project's MCP server. */
data class McpServer(val url: String, val headers: Map<String, String>, val expiresAt: Instant)

/**
 * Hands IDE agents the platform MCP server of a project. The session cookie is not accepted there, so agents get a
 * short-lived project token: one per IDE product and project, shared by all windows and agents of that IDE.
 */
class McpAccess(
    private val tokens: ProjectTokensApi,
    private val productCode: String,
    private val clientBindingId: String,
    private val clock: Clock = Clock.systemUTC(),
) {
    /** Blocking; call off the UI thread. */
    fun server(origin: PlatformOrigin, projectId: String): McpServer {
        val token = usableToken(projectId, tokenName(origin, projectId))
        return McpServer(
            url = "${origin.value}/api/v2/mcp/projects/${encode(projectId)}",
            headers = mapOf(
                PlatformHeader.AUTHORIZATION.wireName to "Bearer ${token.secret}",
                // WHY: without a stable binding every MCP request would count as a new client and lose its file locks.
                PlatformHeader.CLIENT_BINDING.wireName to agentBinding(projectId),
            ),
            expiresAt = token.expiresAt,
        )
    }

    /** When [server] should be called again so agents never hold an expired token. */
    fun renewalDue(server: McpServer): Instant = server.expiresAt.minus(MIN_REMAINING)

    /** Blocking; call off the UI thread. Only when no window of this IDE uses the project's MCP server anymore. */
    fun revoke(origin: PlatformOrigin, projectId: String) = tokens.delete(projectId, tokenName(origin, projectId))

    private fun usableToken(projectId: String, name: String): ProjectToken {
        val now = clock.instant()
        val existing = tokens.list(projectId).firstOrNull { it.name == name }
        if (existing != null && existing.expiresAt.isAfter(now.plus(MIN_REMAINING))) return existing
        if (existing != null) tokens.delete(projectId, name)
        return try {
            tokens.create(projectId, name, now.plus(LIFETIME))
        } catch (e: PlatformException) {
            if (e.status != 400) throw e
            // WHY: another window of this IDE created the same token at the same moment; reuse the winner.
            tokens.list(projectId).firstOrNull { it.name == name && it.expiresAt.isAfter(now) } ?: throw e
        }
    }

    private fun tokenName(origin: PlatformOrigin, projectId: String) = "dm-mcp-jb-" + digest("$productCode|${origin.value}|$projectId").take(40)

    private fun agentBinding(projectId: String) = "dm-agent-" + digest("$clientBindingId|$projectId").take(32)

    private fun digest(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(value.toByteArray()))

    private companion object {
        val LIFETIME: Duration = Duration.ofHours(24)

        /** Renewed this long before it expires, so a working agent is not cut off mid-task. */
        val MIN_REMAINING: Duration = Duration.ofHours(4)
    }
}

/** An IDE agent that can be pointed at the active project's MCP server. */
interface McpAgent {
    /** Shown to the user, e.g. in "Connected: Claude Code, Junie". */
    val displayName: String

    /** Blocking; call off the UI thread. Replaces an earlier registration and returns non-fatal setup warnings. */
    fun register(server: McpServer): List<String>

    /** Blocking; call off the UI thread. */
    fun unregister()
}
