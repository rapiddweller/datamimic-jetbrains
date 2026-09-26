// DATAMIMIC for JetBrains IDEs
// Copyright (c) 2026 Rapiddweller Asia Co., Ltd.
// SPDX-License-Identifier: MIT

package com.rapiddweller.datamimic.ide

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.components.service
import com.intellij.openapi.roots.impl.DirectoryIndexExcludePolicy
import com.intellij.openapi.vfs.WritingAccessProvider
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.openapi.wm.WelcomeScreen
import com.intellij.openapi.wm.WelcomeTabFactory
import com.intellij.platform.lsp.api.LspServerSupportProvider
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.EditorNotificationProvider
import com.rapiddweller.datamimic.ide.editing.PlatformFileBanner
import com.rapiddweller.datamimic.ide.editing.PlatformWritingAccess
import com.rapiddweller.datamimic.ide.editing.ProjectFolderExcludePolicy
import com.rapiddweller.datamimic.ide.lsp.LspStatusWidgetFactory
import com.rapiddweller.datamimic.ide.lsp.PlatformLspSupport
import javax.swing.JComponent
import javax.swing.JPanel

/** Boots a headless IDE with plugin.xml, so a wrong extension point name or a class that fails to load breaks here. */
class PluginRegistrationTest : BasePlatformTestCase() {
    fun `test every extension is registered and loads`() {
        assertTrue(DirectoryIndexExcludePolicy.EP_NAME.getExtensions(project).any { it is ProjectFolderExcludePolicy })
        assertTrue("the optional LSP module loads where the IDE has the LSP API", LspServerSupportProvider.EP_NAME.extensionList.any { it is PlatformLspSupport })
        assertTrue(StatusBarWidgetFactory.EP_NAME.extensionList.any { it is LspStatusWidgetFactory })
        assertTrue(WelcomeTabFactory.WELCOME_TAB_FACTORY_EP.extensionList.any { it is DatamimicWelcomeTabFactory })
        assertInstanceOf(ActionManager.getInstance().getAction("Datamimic.OpenPlatformProject"), OpenPlatformProjectAction::class.java)
        assertTrue(WritingAccessProvider.EP.getExtensions(project).any { it is PlatformWritingAccess })
        assertTrue(EditorNotificationProvider.EP_NAME.getExtensions(project).any { it is PlatformFileBanner })
    }

    fun `test the projects list builds in a project window and on the Welcome screen`() {
        val scope = project.service<ToolWindowScope>().scope
        val projectPanel = ProjectsPanel(project, scope)
        val welcomePanel = ProjectsPanel(null, scope)
        assertNotNull(projectPanel.toolbar)
        assertNotNull(projectPanel.content)
        assertTrue(projectPanel.projectActionsVisible)
        assertTrue(projectPanel.toolbarActions.getChildren(null).any { it.templatePresentation.text == "Generate Data…" })
        assertTrue(projectPanel.contextActions.getChildren(null).any { it.templatePresentation.text == "Copy MCP Configuration for AI Assistant" })
        assertNotNull(welcomePanel.toolbar)
        assertNotNull(welcomePanel.content)
        assertFalse(welcomePanel.projectActionsVisible)
        assertFalse(welcomePanel.toolbarActions.getChildren(null).any { it.templatePresentation.text == "Generate Data…" })
        assertFalse(welcomePanel.contextActions.getChildren(null).any { it.templatePresentation.text == "Copy MCP Configuration for AI Assistant" })
    }

    fun `test the Welcome tab builds its key and projects list`() {
        val welcome = object : WelcomeScreen {
            override fun getWelcomePanel(): JComponent = JPanel()

            override fun dispose() = Unit
        }
        val tab = DatamimicWelcomeTabFactory().createWelcomeTabs(welcome, testRootDisposable).single()
        assertNotNull(tab.getKeyComponent(JPanel()))
        assertNotNull(tab.associatedComponent)
    }
}
