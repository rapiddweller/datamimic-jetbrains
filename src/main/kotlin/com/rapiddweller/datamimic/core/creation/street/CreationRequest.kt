// DATAMIMIC for JetBrains IDEs
// Copyright (c) 2026 Rapiddweller Asia Co., Ltd.
// SPDX-License-Identifier: MIT

package com.rapiddweller.datamimic.core.creation.street

/** What [createFile] asks the platform to create. Each type has its own platform call. */
sealed interface CreationRequest {
    /** A file from a platform template. [extension] includes the leading dot, for example `.ent.csv`. */
    data class FromTemplate(val dir: String, val name: String, val extension: String) : CreationRequest
}

/** The project paths that [createFile] asks the platform to create, for a preview before the call. */
fun CreationRequest.targetPaths(): List<String> = when (this) {
    is CreationRequest.FromTemplate -> listOf(if (dir.isEmpty()) name + extension else "$dir/$name$extension")
}

/** Why [name] cannot be a file name, or null. The platform owns all other name rules and reports them itself. */
fun nameProblem(name: String): String? = when {
    name.isBlank() -> "Enter a file name."
    '/' in name || '\\' in name -> "Enter a name without a slash. The folder is set separately."
    else -> null
}
