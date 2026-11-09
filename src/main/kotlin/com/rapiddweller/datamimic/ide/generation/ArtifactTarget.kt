// DATAMIMIC for JetBrains IDEs
// Copyright (c) 2026 Rapiddweller Asia Co., Ltd.
// SPDX-License-Identifier: MIT

package com.rapiddweller.datamimic.ide.generation

import com.rapiddweller.datamimic.core.generation.isValidArtifactPath
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * The file that receives the artifact [artifactPath]: the same relative path below the [folder] the user chose.
 * This function only reads the disk. It creates nothing.
 *
 * @throws IllegalArgumentException when the path is not a safe relative artifact path.
 * @throws IOException when the path leaves [folder] through a symbolic link, or a folder is in the place of the file.
 */
internal fun artifactTarget(folder: Path, artifactPath: String): Path {
    require(isValidArtifactPath(artifactPath)) { "The artifact path is not valid." }
    val root = folder.toAbsolutePath().normalize()
    val target = artifactPath.split('/').fold(root) { directory, segment -> directory.resolve(segment) }
    requireInside(root, checkNotNull(target.parent))
    if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) throw IOException("$target is a folder.")
    return target
}

/** Like [artifactTarget], but null when the file exists and [confirmReplace] answers no. */
internal fun artifactSaveTarget(folder: Path, artifactPath: String, confirmReplace: (Path) -> Boolean): Path? {
    val target = artifactTarget(folder, artifactPath)
    return target.takeIf { !Files.exists(it, LinkOption.NOFOLLOW_LINKS) || confirmReplace(it) }
}

/**
 * Checks that [directory], or its nearest folder that exists, is inside [root] after the file system resolves every
 * symbolic link and junction. Missing subfolders cannot leave [root], because the new folders go below that nearest folder.
 *
 * @throws IOException when it is not inside.
 */
internal fun requireInside(root: Path, directory: Path) {
    var existing = directory
    while (!Files.exists(existing)) existing = existing.parent ?: break
    if (!existing.toRealPath().startsWith(root.toRealPath())) throw IOException("$directory is outside the chosen folder.")
}
