// DATAMIMIC for JetBrains IDEs
// Copyright (c) 2026 Rapiddweller Asia Co., Ltd.
// SPDX-License-Identifier: MIT

package com.rapiddweller.datamimic.ide.creation

import com.rapiddweller.datamimic.core.creation.container.migrationFlows

/** Migrates a GWA workbook or an AMA archive. This is a composition root of the creation flows. */
class MigrateFromFileAction : FromFileAction("DATAMIMIC Migration", "Cannot Migrate File") {
    override fun flows(source: String, dir: String, sourceProjectPath: String?, sample: String) = migrationFlows(source)
}
