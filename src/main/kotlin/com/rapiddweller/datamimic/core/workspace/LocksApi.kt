// DATAMIMIC for JetBrains IDEs
// Copyright (c) 2026 Rapiddweller Asia Co., Ltd.
// SPDX-License-Identifier: MIT

package com.rapiddweller.datamimic.core.workspace

import com.rapiddweller.datamimic.core.HttpMethod
import com.rapiddweller.datamimic.core.PlatformHeader
import com.rapiddweller.datamimic.core.PlatformHttp
import com.rapiddweller.datamimic.core.RequestBody
import com.rapiddweller.datamimic.core.encode
import com.rapiddweller.datamimic.core.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A fenced edit lease: [generation] must accompany every change and renewal of the file. */
data class LockGrant(val generation: String, val leaseTtlSeconds: Int, val renewAfterSeconds: Int)

@Serializable
private data class LockMutationResponse(
    val generation: String? = null,
    @SerialName("lease_ttl_seconds") val leaseTtlSeconds: Int? = null,
    @SerialName("renew_after_seconds") val renewAfterSeconds: Int? = null,
) {
    fun grant(): LockGrant {
        checkNotNull(generation) { "The platform granted a lock without a generation." }
        checkNotNull(leaseTtlSeconds) { "The platform granted a lock without a lease." }
        checkNotNull(renewAfterSeconds) { "The platform granted a lock without a renewal interval." }
        check(renewAfterSeconds in 1 until leaseTtlSeconds) { "The platform granted an unusable lock lease." }
        return LockGrant(generation, leaseTtlSeconds, renewAfterSeconds)
    }
}

class LocksApi(private val http: PlatformHttp) {
    fun acquire(ref: DocumentRef): LockGrant = mutate(ref, LockOperation.ACQUIRE, pathBody(ref), generation = null).grant()

    fun heartbeat(ref: DocumentRef, generation: String): LockGrant = mutate(ref, LockOperation.HEARTBEAT, pathBody(ref), generation).grant()

    fun release(ref: DocumentRef, generation: String) {
        mutate(ref, LockOperation.RELEASE, pathBody(ref), generation)
    }

    /** Takes a foreign lock; only after the user confirmed that the other editor's unsaved work may be lost. */
    fun takeover(ref: DocumentRef, expectedGeneration: String): LockGrant {
        val body = buildJsonObject {
            put("path", ref.path)
            put("expected_generation", expectedGeneration)
        }
        return mutate(ref, LockOperation.TAKEOVER, body.toString(), generation = null).grant()
    }

    private fun mutate(ref: DocumentRef, operation: LockOperation, body: String, generation: String?): LockMutationResponse {
        val headers = generation?.let { mapOf(PlatformHeader.LOCK_GENERATION to it) } ?: emptyMap()
        val response = http.send(
            HttpMethod.POST,
            "/api/v2/projects/${encode(ref.projectId)}/workspace/locks/${operation.route}",
            RequestBody.Json(body),
            headers,
        )
        return json.decodeFromString(response.body)
    }

    private fun pathBody(ref: DocumentRef) = buildJsonObject { put("path", ref.path) }.toString()

    private enum class LockOperation(val route: String) {
        ACQUIRE("acquire"),
        HEARTBEAT("heartbeat"),
        RELEASE("release"),
        TAKEOVER("takeover"),
    }
}
