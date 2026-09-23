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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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

    val parentPath: String? get() = path.substringBeforeLast('/', "").ifEmpty { null }

    fun child(name: String): DocumentRef = DocumentRef(projectId, parentPath?.let { "$it/$name" } ?: name)
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
    @SerialName("source_project_name") val sourceProjectName: String? = null,
    val hidden: Boolean = false,
    val readonly: Boolean = false,
    val size: Long? = null,
    val etag: String? = null,
)

@Serializable
data class WorkspaceTree(
    @SerialName("project_id") val projectId: String,
    val revision: String,
    val entries: List<TreeEntry>,
) {
    fun entry(path: String): TreeEntry? = entries.firstOrNull { it.path == path }
}

@Serializable
data class FileTemplate(val label: String, val extension: String)

@Serializable
private data class TemplateList(val templates: List<FileTemplate>)

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

    fun templates(projectId: String): List<FileTemplate> =
        json.decodeFromString<TemplateList>(http.getJson("${base(projectId)}/templates")).templates

    /** @param directory project-relative directory, or null for the project root. */
    fun createFromTemplate(projectId: String, directory: String?, fileName: String, template: FileTemplate): DocumentRef {
        val ref = DocumentRef(projectId, listOfNotNull(directory, fileName + template.extension).joinToString("/"))
        val body = buildJsonObject {
            put("file_extension", template.extension)
            put("file_name", fileName)
            put("file_dir", directory.orEmpty())
        }
        http.send(
            HttpMethod.POST,
            "${base(projectId)}/files/from-template",
            RequestBody.Json(body.toString()),
            mapOf(PlatformHeader.IF_NONE_MATCH to "*"),
        )
        return ref
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

/** One row of a project's file tree; directories that only exist implicitly (as parents of files) have no [entry]. */
data class TreeItem(val ref: DocumentRef, val kind: EntryKind, val entry: TreeEntry?)

/** Direct visible children of [parent] (null for the project root), directories first, then by name. */
fun WorkspaceTree.children(parent: String?): List<TreeItem> {
    val prefix = parent?.let { "$it/" } ?: ""
    val visible = entries.filter { !it.hidden && it.path.startsWith(prefix) && it.path.length > prefix.length }
    val items = linkedMapOf<String, TreeItem>()
    for (entry in visible) {
        val rest = entry.path.removePrefix(prefix)
        val name = rest.substringBefore('/')
        val isDirectChild = '/' !in rest
        val path = prefix + name
        when {
            isDirectChild -> items[path] = TreeItem(DocumentRef(projectId, path), entry.kind, entry)
            path !in items -> items[path] = TreeItem(DocumentRef(projectId, path), EntryKind.DIRECTORY, null)
        }
    }
    return items.values.sortedWith(compareBy<TreeItem> { it.kind != EntryKind.DIRECTORY }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.ref.name })
}
