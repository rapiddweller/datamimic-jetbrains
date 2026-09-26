// DATAMIMIC for JetBrains IDEs
// Copyright (c) 2026 Rapiddweller Asia Co., Ltd.
// SPDX-License-Identifier: MIT

package com.rapiddweller.datamimic.core.workspace

import com.rapiddweller.datamimic.core.PlatformOrigin
import com.rapiddweller.datamimic.core.PlatformProject
import com.rapiddweller.datamimic.core.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Which platform project a folder is the local copy of. The folder name is only for people; this is the identity. */
@Serializable
data class FolderIdentity(
    val origin: PlatformOrigin,
    @SerialName("project_id") val projectId: String,
    @SerialName("project_name") val projectName: String,
)

/** The platform version a local file was downloaded or uploaded as. */
@Serializable
data class FileBase(val etag: String, val sha256: String)

/**
 * A local folder that mirrors one platform project. The platform stays the source of truth; the plugin keeps its own
 * state in [META_DIR], which is never synced.
 */
class ProjectFolder(root: Path) {
    val root: Path = root.toAbsolutePath().normalize()
    private val metaDir: Path = this.root.resolve(META_DIR)
    val basesFile: Path = metaDir.resolve("state.json")
    val tempDir: Path = metaDir.resolve("tmp")
    private val identityFile: Path = metaDir.resolve("workspace.json")

    fun isProjectFolder(): Boolean = Files.isRegularFile(identityFile)

    fun identity(): FolderIdentity? =
        runCatching { json.decodeFromString<FolderIdentity>(Files.readString(identityFile)) }.getOrNull()

    fun writeIdentity(identity: FolderIdentity) =
        writeAtomically(identityFile, json.encodeToString(FolderIdentity.serializer(), identity).toByteArray(), metaDir)

    /** The local file of platform [path]; null when it would leave the folder, pass through a link, or is not synced. */
    fun resolve(path: String): Path? {
        if (!isSynced(path) || !path.split('/').all(DocumentRef::isValidSegment)) return null
        val target = root.resolve(path).normalize()
        if (!target.startsWith(root)) return null
        // WHY: a link inside the folder could point a platform path at any file on this machine.
        var current = root
        for (segment in path.split('/')) {
            current = current.resolve(segment)
            if (Files.isSymbolicLink(current)) return null
        }
        return target
    }

    /** The platform path of a local [file], or null when it is outside the folder or not synced. */
    fun pathOf(file: Path): String? {
        val normalized = file.normalize()
        if (!normalized.startsWith(root) || normalized == root) return null
        val path = root.relativize(normalized).joinToString("/")
        return path.takeIf { isSynced(it) && it.split('/').all(DocumentRef::isValidSegment) }
    }

    /** Synced local files by platform path; links are skipped, never followed. */
    fun localFiles(): Map<String, Path> {
        if (!Files.isDirectory(root)) return emptyMap()
        val files = mutableMapOf<String, Path>()
        Files.walk(root).use { stream ->
            stream.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }.forEach { file ->
                pathOf(file)?.takeIf { resolve(it) == file }?.let { files[it] = file }
            }
        }
        return files
    }

    companion object {
        const val META_DIR = ".datamimic"

        /** Folders and files of the IDE, VCS and agents that live next to the project files but are never synced. */
        private val LOCAL_ONLY_NAMES = setOf(META_DIR, ".idea", ".git", ".junie", ".claude", ".vscode", ".DS_Store")

        /** File name endings of IDE module files and of the IDE's safe write (`name.tmp`, then backup `name~`). */
        private val LOCAL_ONLY_SUFFIXES = listOf(".iml", ".tmp", "~")

        fun isSynced(path: String): Boolean =
            path.split('/').none { it in LOCAL_ONLY_NAMES } && LOCAL_ONLY_SUFFIXES.none(path::endsWith)

        /**
         * Where a project is kept under [base]: one folder per platform, one per project. The project id keeps two
         * projects with the same name apart; a folder's persisted identity survives a platform rename, and a longer
         * id is used when the short one is already taken.
         */
        fun locationFor(base: Path, origin: PlatformOrigin, project: PlatformProject): Path {
            val platformDir = base.resolve(safeName(origin.value.substringAfter("://")))
            require(!Files.isSymbolicLink(platformDir)) { "DATAMIMIC platform folder must not be a symbolic link: $platformDir" }
            if (Files.isDirectory(platformDir)) {
                val matches = Files.list(platformDir).use { folders ->
                    folders
                        .filter { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }
                        .filter { folder ->
                            val identity = ProjectFolder(folder).identity()
                            identity != null && identity.origin == origin && identity.projectId == project.id
                        }
                        .toList()
                }
                if (matches.isNotEmpty()) {
                    check(matches.size == 1) { "More than one local folder represents ${project.id} on ${origin.value}." }
                    return matches.single()
                }
            }
            val name = safeName(project.name)
            val candidates = listOf("$name-${safeName(project.id).take(8)}", "$name-${safeName(project.id)}").map(platformDir::resolve)
            return candidates.firstOrNull { ProjectFolder(it).isFreeFor(origin, project.id) } ?: candidates.last()
        }

        private fun safeName(value: String): String = value.replace(Regex("[^A-Za-z0-9._ -]"), "_").trim().ifEmpty { "project" }.take(60)
    }

    private fun isFreeFor(origin: PlatformOrigin, projectId: String): Boolean {
        if (!Files.exists(root)) return true
        val identity = identity() ?: return Files.list(root).use { it.findAny().isEmpty }
        return identity.origin == origin && identity.projectId == projectId
    }
}

/**
 * The platform version each local file of a [ProjectFolder] is based on: the one place that tells local edits from
 * platform changes, and the ETag every upload is fenced with. Kept on disk, so edits made while the IDE was closed are
 * found on the next start.
 */
class FileBases(private val folder: ProjectFolder) {
    private val bases: MutableMap<String, FileBase> = load()

    @Synchronized
    fun get(path: String): FileBase? = bases[path]

    @Synchronized
    fun all(): Map<String, FileBase> = bases.toMap()

    @Synchronized
    fun set(path: String, base: FileBase) {
        bases[path] = base
        save()
    }

    @Synchronized
    fun remove(path: String) {
        if (bases.remove(path) != null) save()
    }

    /** Removes the bases of [path] and everything under it. @return what was removed. */
    @Synchronized
    fun removeUnder(path: String): Map<String, FileBase> {
        val removed = bases.filterKeys { isAtOrUnder(it, path) }
        if (removed.isNotEmpty()) {
            removed.keys.forEach(bases::remove)
            save()
        }
        return removed
    }

    /** Re-keys [from] and everything under it to [to]. */
    @Synchronized
    fun move(from: String, to: String) {
        val moved = bases.filterKeys { isAtOrUnder(it, from) }
        moved.keys.forEach(bases::remove)
        moved.forEach { (path, base) -> bases[to + path.removePrefix(from)] = base }
        save()
    }

    @Serializable
    private class Stored(val files: Map<String, FileBase> = emptyMap())

    private fun load(): MutableMap<String, FileBase> =
        // WHY: lost state only costs one content comparison per file on the next sync, never data.
        runCatching { json.decodeFromString<Stored>(Files.readString(folder.basesFile)).files.toMutableMap() }.getOrElse { mutableMapOf() }

    private fun save() =
        writeAtomically(folder.basesFile, json.encodeToString(Stored.serializer(), Stored(bases)).toByteArray(), folder.tempDir)
}

internal fun isAtOrUnder(path: String, ancestor: String) = path == ancestor || path.startsWith("$ancestor/")

fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

/** Replaces [target] in one step, so the IDE, agents and a crash never see a half-written file. */
internal fun writeAtomically(target: Path, bytes: ByteArray, tempDir: Path) {
    Files.createDirectories(tempDir)
    Files.createDirectories(target.parent)
    val temp = Files.createTempFile(tempDir, "write-", ".tmp")
    try {
        Files.write(temp, bytes)
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    } finally {
        Files.deleteIfExists(temp)
    }
}
