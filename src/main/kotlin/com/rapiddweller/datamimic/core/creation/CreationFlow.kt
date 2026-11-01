// DATAMIMIC for JetBrains IDEs
// Copyright (c) 2026 Rapiddweller Asia Co., Ltd.
// SPDX-License-Identifier: MIT

package com.rapiddweller.datamimic.core.creation

import com.rapiddweller.datamimic.core.creation.street.CreationRequest

/** One choice in the creation dialog. A flow is plain data: it builds a request and does no I/O. */
interface CreationFlow {
    val id: String

    /** The text that the dialog shows. */
    val label: String

    /** @param dir the project folder of the new file, empty for the project root. */
    fun toRequest(dir: String, name: String): CreationRequest
}
