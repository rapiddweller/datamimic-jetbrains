// DATAMIMIC for JetBrains IDEs
// Copyright (c) 2026 Rapiddweller Asia Co., Ltd.
// SPDX-License-Identifier: MIT

package com.rapiddweller.datamimic.ide.creation

import com.rapiddweller.datamimic.core.creation.container.modelFlows

/** Generates a DATAMIMIC model from a JSON, XML or CSV file. This is a composition root of the creation flows. */
class GenerateModelFromFileAction : FromFileAction("Generate DATAMIMIC Model", "Cannot Generate Model") {
    override fun flows(source: String, dir: String, sourceProjectPath: String?, sample: String) =
        modelFlows(source, dir, sourceProjectPath, sample)
}
