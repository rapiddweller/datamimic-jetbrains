// DATAMIMIC for JetBrains IDEs
// Copyright (c) 2026 Rapiddweller Asia Co., Ltd.
// SPDX-License-Identifier: MIT

package com.rapiddweller.datamimic.ide.creation

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.rapiddweller.datamimic.core.creation.container.migrationFlows
import com.rapiddweller.datamimic.core.creation.container.modelFlows
import com.rapiddweller.datamimic.ide.creation.truck.CreationDialog

class CreationDialogTest : BasePlatformTestCase() {
    private fun dialog(flows: List<com.rapiddweller.datamimic.core.creation.CreationFlow>, values: Map<String, String> = emptyMap()) =
        CreationDialog(project, flows, "data", initialValues = values, source = "/in/source").also { Disposer.register(testRootDisposable) { it.close(0) } }

    fun `test the dialog builds a field for each kind and returns the values of the chosen flow`() {
        val gwa = dialog(migrationFlows("/in/cases.xlsm"), mapOf("schemaVersion" to "1.2"))
        val ama = dialog(migrationFlows("/in/app.ama"))
        val csv = dialog(modelFlows("/in/a.csv", "data", null, "a|b\n1|2\n"))

        assertEquals("1.2", gwa.values["schemaVersion"])
        assertEquals(setOf("schemaVersion", "sheetName", "idColumn", "templateColumn", "operationColumnPrefix", "templatePrefix", "templateSuffix", "templateDirectory"), gwa.values.keys)
        assertEquals(mapOf("iniContentB64" to "", "templateDirectory" to "template"), ama.values)
        assertEquals(mapOf("delimiter" to "|"), csv.values)
        assertEquals("cases", gwa.fileName)
    }
}
