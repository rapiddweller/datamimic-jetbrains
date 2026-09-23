// DATAMIMIC for JetBrains IDEs
// Copyright (c) 2026 Rapiddweller Asia Co., Ltd.
// SPDX-License-Identifier: MIT

package com.rapiddweller.datamimic.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

@Serializable
data class PlatformProject(
    @SerialName("identifier") val id: String,
    val name: String,
    val type: String,
    @SerialName("tc_update") val lastEditedAt: String,
)

@Serializable
private data class ProjectSearchResponse(val data: List<PlatformProject>, val meta: Meta) {
    @Serializable
    data class Meta(val pagination: Pagination)

    @Serializable
    data class Pagination(@SerialName("next_page_num") val nextPage: Int? = null)
}

class ProjectsApi(private val http: PlatformHttp) {
    /** All projects visible to the signed-in user, most recently edited first. */
    fun list(): List<PlatformProject> {
        val projects = mutableListOf<PlatformProject>()
        var page = 1
        while (true) {
            val request = buildJsonObject {
                putJsonObject("filters") {}
                putJsonObject("pagination") { put("page", page); put("per_page", 1000) }
                putJsonObject("sorting") { put("sort_by", "tc_update"); put("sort_order", "desc") }
            }
            val response = json.decodeFromString<ProjectSearchResponse>(http.postJson("/api/v2/projects/search", request.toString()))
            projects += response.data
            val next = response.meta.pagination.nextPage ?: return projects
            check(next > page) { "Project search returned invalid pagination (page $page → $next)." }
            page = next
        }
    }
}
