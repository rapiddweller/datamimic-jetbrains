// DATAMIMIC for JetBrains IDEs
// Copyright (c) 2026 Rapiddweller Asia Co., Ltd.
// SPDX-License-Identifier: MIT

package com.rapiddweller.datamimic.core.creation.street

/** What [createFile] asks the platform to create. Each type has its own platform call. */
sealed interface CreationRequest {
    /** A file from a platform template. [extension] includes the leading dot, for example `.ent.csv`. */
    data class FromTemplate(val dir: String, val name: String, val extension: String) : CreationRequest
}
