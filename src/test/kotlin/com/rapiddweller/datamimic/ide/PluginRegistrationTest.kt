// DATAMIMIC for JetBrains IDEs
// Copyright (c) 2026 Rapiddweller Asia Co., Ltd.
// SPDX-License-Identifier: MIT

package com.rapiddweller.datamimic.ide

import com.intellij.openapi.roots.impl.DirectoryIndexExcludePolicy
import com.intellij.openapi.vfs.WritingAccessProvider
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.platform.lsp.api.LspServerSupportProvider
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.EditorNotificationProvider
import com.rapiddweller.datamimic.ide.editing.PlatformFileBanner
import com.rapiddweller.datamimic.ide.editing.PlatformWritingAccess
import com.rapiddweller.datamimic.ide.editing.ProjectFolderExcludePolicy
import com.rapiddweller.datamimic.ide.lsp.LspStatusWidgetFactory
import com.rapiddweller.datamimic.ide.lsp.PlatformLspSupport

/** Boots a headless IDE with plugin.xml, so a wrong extension point name or a class that fails to load breaks here. */
class PluginRegistrationTest : BasePlatformTestCase() {
    fun `test every extension is registered and loads`() {
        assertTrue(DirectoryIndexExcludePolicy.EP_NAME.getExtensions(project).any { it is ProjectFolderExcludePolicy })
        assertTrue("the optional LSP module loads where the IDE has the LSP API", LspServerSupportProvider.EP_NAME.extensionList.any { it is PlatformLspSupport })
        assertTrue(StatusBarWidgetFactory.EP_NAME.extensionList.any { it is LspStatusWidgetFactory })
        assertTrue(WritingAccessProvider.EP.getExtensions(project).any { it is PlatformWritingAccess })
        assertTrue(EditorNotificationProvider.EP_NAME.getExtensions(project).any { it is PlatformFileBanner })
    }

    fun `test the projects tool window builds its toolbar, menu and tree`() {
        val panel = ProjectsPanel(project)
        assertNotNull(panel.toolbar)
        assertNotNull(panel.content)
    }
}
