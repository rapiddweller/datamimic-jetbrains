// DATAMIMIC for JetBrains IDEs
// Copyright (c) 2026 Rapiddweller Asia Co., Ltd.
// SPDX-License-Identifier: MIT

package com.rapiddweller.datamimic.core.creation.container

import com.rapiddweller.datamimic.core.creation.CreationFlow
import com.rapiddweller.datamimic.core.creation.street.CreationRequest
import com.rapiddweller.datamimic.core.workspace.FileTemplate

/** One flow for each platform template. The label is the platform label, unchanged. */
fun templateFlows(templates: List<FileTemplate>): List<CreationFlow> = templates.map { template ->
    object : CreationFlow {
        override val id = "template" + template.extension
        override val label = template.label

        override fun toRequest(dir: String, name: String, choices: Map<String, String>): CreationRequest =
            CreationRequest.FromTemplate(dir, name, template.extension)
    }
}
