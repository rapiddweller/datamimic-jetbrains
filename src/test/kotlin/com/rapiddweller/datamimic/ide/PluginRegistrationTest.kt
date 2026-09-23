// DATAMIMIC for JetBrains IDEs
// Copyright (c) 2026 Rapiddweller Asia Co., Ltd.
// SPDX-License-Identifier: MIT

package com.rapiddweller.datamimic.ide

import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.openapi.roots.impl.DirectoryIndexExcludePolicy
import com.intellij.openapi.vfs.WritingAccessProvider
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.EditorNotificationProvider
import com.jetbrains.jsonSchema.extension.JsonSchemaProviderFactory
import com.rapiddweller.datamimic.ide.editing.PlatformFileBanner
import com.rapiddweller.datamimic.ide.editing.PlatformWritingAccess
import com.rapiddweller.datamimic.ide.editing.ProjectFolderExcludePolicy
import com.rapiddweller.datamimic.ide.local.AuthoringSchemaProviderFactory
import com.rapiddweller.datamimic.ide.local.CeRunConfigurationType
import com.rapiddweller.datamimic.ide.local.isLocalDescriptor

/** Boots a headless IDE with plugin.xml, so a wrong extension point name or a class that fails to load breaks here. */
class PluginRegistrationTest : BasePlatformTestCase() {
    fun `test every extension is registered and loads`() {
        assertTrue(DirectoryIndexExcludePolicy.EP_NAME.getExtensions(project).any { it is ProjectFolderExcludePolicy })
        assertNotNull(ConfigurationTypeUtil.findConfigurationType(CeRunConfigurationType::class.java))
        assertTrue(JsonSchemaProviderFactory.EP_NAME.extensionList.any { it is AuthoringSchemaProviderFactory })
        assertTrue(WritingAccessProvider.EP.getExtensions(project).any { it is PlatformWritingAccess })
        assertTrue(EditorNotificationProvider.EP_NAME.getExtensions(project).any { it is PlatformFileBanner })
    }

    fun `test the projects tool window builds its toolbar, menu and tree`() {
        val panel = ProjectsPanel(project)
        assertNotNull(panel.toolbar)
        assertNotNull(panel.content)
    }

    fun `test only xml files rooted in setup are descriptors`() {
        assertFalse(myFixture.configureByText("other.xml", "<project/>").isLocalDescriptor())
        assertTrue(myFixture.configureByText("datamimic.xml", "<setup/>").isLocalDescriptor())
    }
}
