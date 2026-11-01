// DATAMIMIC for JetBrains IDEs
// Copyright (c) 2026 Rapiddweller Asia Co., Ltd.
// SPDX-License-Identifier: MIT

package com.rapiddweller.datamimic.core.creation.street

import com.rapiddweller.datamimic.core.workspace.UploadFileType
import kotlinx.serialization.json.JsonObject

/** What [createOnPlatform] asks the platform to create. Each type has its own platform call. */
sealed interface CreationRequest {
    /** A file from a platform template. [extension] includes the leading dot, for example `.ent.csv`. */
    data class FromTemplate(val dir: String, val name: String, val extension: String) : CreationRequest

    /**
     * Files that the platform's upload task makes from the local file [source].
     *
     * @param customFields the `custom_fields` form field, including `file_name`.
     * @param targets the project paths that the task writes, in the order of the platform.
     */
    data class FromUpload(
        val dir: String,
        val name: String,
        val source: String,
        val type: UploadFileType,
        val customFields: JsonObject,
        val targets: List<String>,
    ) : CreationRequest
}

/** The project paths that [createOnPlatform] asks the platform to create, for a preview before the call. */
fun CreationRequest.targetPaths(): List<String> = when (this) {
    is CreationRequest.FromTemplate -> listOf(projectPath(dir, name + extension))
    is CreationRequest.FromUpload -> targets
}

/** The project path of [fileName] in [dir], without a leading slash in the project root. */
fun projectPath(dir: String, fileName: String): String = if (dir.isEmpty()) fileName else "$dir/$fileName"

/** Why [name] cannot be a file name, or null. The platform owns all other name rules and reports them itself. */
fun nameProblem(name: String): String? = when {
    name.isBlank() -> "Enter a file name."
    '/' in name || '\\' in name -> "Enter a name without a slash. The folder is set separately."
    else -> null
}
