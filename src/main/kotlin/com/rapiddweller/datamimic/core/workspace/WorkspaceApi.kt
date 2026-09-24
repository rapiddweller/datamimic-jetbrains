// DATAMIMIC for JetBrains IDEs
// Copyright (c) 2026 Rapiddweller Asia Co., Ltd.
// SPDX-License-Identifier: MIT

package com.rapiddweller.datamimic.core.workspace

import com.rapiddweller.datamimic.core.HttpMethod
import com.rapiddweller.datamimic.core.PlatformHeader
import com.rapiddweller.datamimic.core.PlatformHttp
import com.rapiddweller.datamimic.core.RequestBody
import com.rapiddweller.datamimic.core.encode
import com.rapiddweller.datamimic.core.encodePath
import com.rapiddweller.datamimic.core.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.Base64

/** A path inside one platform project. Rejects anything that could escape or alias the project root. */
data class DocumentRef(val projectId: String, val path: String) {
    init {
        require(projectId.isNotBlank()) { "Project id is required." }
        require(path.split("/").all(::isValidSegment)) { "Invalid project path: $path" }
    }

    companion object {
        /** One file or folder name inside a project path. */
        fun isValidSegment(segment: String): Boolean =
            segment.isNotEmpty() && segment != "." && segment != ".." && '/' !in segment && '\\' !in segment && '\u0000' !in segment
    }

    val name: String get() = path.substringAfterLast('/')
}

@Serializable
enum class EntryKind {
    @SerialName("file") FILE,
    @SerialName("directory") DIRECTORY,
}

@Serializable
enum class EntrySource {
    /** The project's own file. */
    @SerialName("current") CURRENT,

    /** Shared from a global project; read-only here. */
    @SerialName("global") GLOBAL,
}

@Serializable
data class TreeEntry(
    val path: String,
    val kind: EntryKind,
    val source: EntrySource,
    val hidden: Boolean = false,
    val readonly: Boolean = false,
    val etag: String? = null,
) {
    /** Shared from a global project or otherwise not editable on the platform. */
    val isReadOnlyHere: Boolean get() = readonly || source == EntrySource.GLOBAL
}

@Serializable
data class WorkspaceTree(
    @SerialName("project_id") val projectId: String,
    val revision: String,
    val entries: List<TreeEntry>,
) {
    fun entry(path: String): TreeEntry? = entries.firstOrNull { it.path == path }
}

class FileContent(val bytes: ByteArray, val etag: String?)

/** First-party workspace routes; ETag and lock generation fence every change to an existing file. */
class WorkspaceApi(private val http: PlatformHttp) {
    fun tree(projectId: String): WorkspaceTree {
        val tree = json.decodeFromString<WorkspaceTree>(http.getJson("${base(projectId)}/tree"))
        check(tree.projectId == projectId) { "The platform returned the tree of another project." }
        return tree
    }

    fun read(ref: DocumentRef): FileContent {
        val response = http.send(HttpMethod.GET, "${base(ref.projectId)}/files/${encodePath(ref.path)}")
        // WHY: the platform streams file content as base64 text.
        return FileContent(Base64.getMimeDecoder().decode(response.body), response.header(PlatformHeader.ETAG))
    }

    fun create(ref: DocumentRef, bytes: ByteArray) {
        http.send(
            HttpMethod.PUT,
            "${base(ref.projectId)}/files/${encodePath(ref.path)}",
            RequestBody.Multipart(ref.name, bytes),
            mapOf(PlatformHeader.IF_NONE_MATCH to "*"),
        )
    }

    fun update(ref: DocumentRef, bytes: ByteArray, etag: String, lockGeneration: String) {
        http.send(
            HttpMethod.PUT,
            "${base(ref.projectId)}/files/${encodePath(ref.path)}",
            RequestBody.Multipart(ref.name, bytes),
            mapOf(PlatformHeader.IF_MATCH to etag, PlatformHeader.LOCK_GENERATION to lockGeneration),
        )
    }

    fun deleteFile(ref: DocumentRef, etag: String, lockGeneration: String) {
        http.send(
            HttpMethod.DELETE,
            "${base(ref.projectId)}/entries/${encodePath(ref.path)}?kind=file",
            headers = mapOf(PlatformHeader.IF_MATCH to etag, PlatformHeader.LOCK_GENERATION to lockGeneration),
        )
    }

    fun moveFile(from: DocumentRef, to: DocumentRef, etag: String, lockGeneration: String) {
        http.send(
            HttpMethod.POST,
            "${base(from.projectId)}/entries/move",
            RequestBody.Json(moveBody(from, to, EntryKind.FILE)),
            mapOf(PlatformHeader.IF_MATCH to etag, PlatformHeader.LOCK_GENERATION to lockGeneration),
        )
    }

    fun createDirectory(ref: DocumentRef) {
        http.send(HttpMethod.PUT, "${base(ref.projectId)}/directories/${encodePath(ref.path)}", RequestBody.Json("{}"))
    }

    fun deleteDirectory(ref: DocumentRef) {
        http.send(HttpMethod.DELETE, "${base(ref.projectId)}/entries/${encodePath(ref.path)}?kind=directory&recursive=true")
    }

    fun moveDirectory(from: DocumentRef, to: DocumentRef) {
        http.send(HttpMethod.POST, "${base(from.projectId)}/entries/move", RequestBody.Json(moveBody(from, to, EntryKind.DIRECTORY)))
    }

    private fun moveBody(from: DocumentRef, to: DocumentRef, kind: EntryKind): String = json.encodeToString(
        MoveRequest.serializer(),
        MoveRequest(from.path, to.path, kind),
    )

    private fun base(projectId: String) = "/api/v2/projects/${encode(projectId)}/workspace"
}

@Serializable
private data class MoveRequest(
    @SerialName("source_path") val sourcePath: String,
    @SerialName("destination_path") val destinationPath: String,
    val kind: EntryKind,
)
