// DATAMIMIC for JetBrains IDEs
// Copyright (c) 2026 Rapiddweller Asia Co., Ltd.
// SPDX-License-Identifier: MIT

package com.rapiddweller.datamimic.core.lsp

import com.rapiddweller.datamimic.core.PlatformErrorCode
import com.rapiddweller.datamimic.core.PlatformException
import com.rapiddweller.datamimic.core.HttpMethod
import com.rapiddweller.datamimic.core.PlatformHttp
import com.rapiddweller.datamimic.core.RequestBody
import com.rapiddweller.datamimic.core.encode
import com.rapiddweller.datamimic.core.json
import com.rapiddweller.datamimic.core.workspace.DocumentRef
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.jsonObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets.UTF_8

/**
 * The document URIs the hosted language server accepts: `datamimic://project/<project-id>/<path>`, every segment
 * UTF-8 percent-encoded except `A-Z a-z 0-9 - . _ ~`, hex in upper case. Anything else is not canonical and rejected.
 */
object DocumentUris {
    private const val PREFIX = "datamimic://project/"

    fun root(projectId: String): String = PREFIX + encodeSegment(projectId)

    fun of(projectId: String, path: String): String =
        root(projectId) + "/" + DocumentRef(projectId, path).path.split('/').joinToString("/", transform = ::encodeSegment)

    /** The project path of [uri], or null when it is not a canonical document URI of [projectId]. */
    fun pathOf(uri: String, projectId: String): String? {
        val prefix = root(projectId) + "/"
        if (!uri.startsWith(prefix)) return null
        val segments = uri.removePrefix(prefix).split('/')
        val decoded = segments.map { decodeSegment(it) ?: return null }
        if (!decoded.all(DocumentRef::isValidSegment) || decoded.map(::encodeSegment) != segments) return null
        return decoded.joinToString("/")
    }

    private fun encodeSegment(segment: String): String = buildString {
        for (byte in segment.toByteArray(UTF_8)) {
            val char = (byte.toInt() and 0xFF).toChar()
            if (char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' || char in "-._~") append(char) else append("%%%02X".format(byte))
        }
    }

    private fun decodeSegment(segment: String): String? {
        val bytes = ByteArrayOutputStream()
        var i = 0
        while (i < segment.length) {
            if (segment[i] == '%') {
                if (i + 3 > segment.length) return null
                bytes.write(segment.substring(i + 1, i + 3).toIntOrNull(16) ?: return null)
                i += 3
            } else {
                bytes.write(segment[i].code.takeIf { it < 0x80 } ?: return null)
                i++
            }
        }
        return try {
            UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
        } catch (_: CharacterCodingException) {
            null
        }
    }
}

/** What the hosted language server of one project needs at startup. */
class LspInit(val rootUri: String, private val body: JsonObject) {
    /** The `lsp/init` response as the server expects it back in `initialize`, plus the bridge's [secret]. */
    fun initializationOptions(secret: String): String =
        JsonObject(body + (LspBridge.SECRET_OPTION to JsonPrimitive(secret))).toString()
}

@Serializable
private class LspInitIdentity(@SerialName("project_id") val projectId: String, @SerialName("root_uri") val rootUri: String)

class LspApi(private val http: PlatformHttp) {
    /** Turns the project's language server on or off for everyone; the platform merges this into the project config. */
    fun setEnabled(projectId: String, enabled: Boolean) {
        val body = buildJsonObject { putJsonObject("config") { putJsonObject("lsp") { put("enabled", enabled) } } }
        http.send(HttpMethod.PUT, "/api/v2/projects/${encode(projectId)}", RequestBody.Json(body.toString()))
    }

    /** @throws PlatformException with [PlatformErrorCode.LSP_DISABLED] when the project has its language server turned off. */
    fun init(projectId: String): LspInit {
        val raw = http.getJson("/api/v2/projects/${encode(projectId)}/lsp/init")
        val identity = json.decodeFromString<LspInitIdentity>(raw)
        check(identity.projectId == projectId && identity.rootUri == DocumentUris.root(projectId)) {
            "The platform returned the language server of another project."
        }
        return LspInit(identity.rootUri, json.parseToJsonElement(raw).jsonObject)
    }
}
