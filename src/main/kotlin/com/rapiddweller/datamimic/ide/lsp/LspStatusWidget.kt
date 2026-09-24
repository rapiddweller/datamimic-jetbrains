// DATAMIMIC for JetBrains IDEs
// Copyright (c) 2026 Rapiddweller Asia Co., Ltd.
// SPDX-License-Identifier: MIT

package com.rapiddweller.datamimic.ide.lsp

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.ui.awt.RelativePoint
import com.intellij.util.Consumer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.awt.Component
import java.awt.event.MouseEvent

private const val WIDGET_ID = "DatamimicLanguageServer"

/** Shows the platform language server of a DATAMIMIC project window in the status bar. */
class LspStatusWidgetFactory : StatusBarWidgetFactory {
    override fun getId(): String = WIDGET_ID

    override fun getDisplayName(): String = "DATAMIMIC Language Server"

    override fun isAvailable(project: Project): Boolean = project.isProjectFolder()

    override fun createWidget(project: Project, scope: CoroutineScope): StatusBarWidget = LspStatusWidget(project, scope)
}

/** Status text plus a menu to turn the server on or off for the project, or to ask the platform again. */
private class LspStatusWidget(private val project: Project, private val scope: CoroutineScope) :
    StatusBarWidget, StatusBarWidget.TextPresentation {
    private val server = project.service<HostedLanguageServer>()

    override fun ID(): String = WIDGET_ID

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    override fun install(statusBar: StatusBar) {
        scope.launch(Dispatchers.EDT) { server.state.collect { statusBar.updateWidget(WIDGET_ID) } }
    }

    override fun getText(): String = "DATAMIMIC LSP: ${label(server.state.value.status)}"

    override fun getAlignment(): Float = Component.CENTER_ALIGNMENT

    override fun getTooltipText(): String =
        server.state.value.detail ?: "The platform's language server for this project. Click to turn it on or off."

    override fun getClickConsumer(): Consumer<MouseEvent> = Consumer { event ->
        JBPopupFactory.getInstance()
            .createActionGroupPopup(
                "DATAMIMIC Language Server",
                actions(),
                SimpleDataContext.getProjectContext(project),
                JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
                false,
            )
            .show(RelativePoint(event))
    }

    private fun actions() = DefaultActionGroup(
        action("Turn On for This Project", { it == LspStatus.TURNED_OFF || it == LspStatus.FAILED }) { server.turnOn() },
        action("Turn Off for This Project", { it == LspStatus.READY || it == LspStatus.CONNECTED }) { confirmTurnOff() },
        action("Check Again", { it != LspStatus.SIGNED_OUT }) { server.checkAgain() },
    )

    private fun confirmTurnOff() {
        val confirmed = Messages.showOkCancelDialog(
            project,
            "Turn off the language server for everyone who works on this project?",
            "Turn Off Language Server",
            "Turn Off",
            Messages.getCancelButton(),
            Messages.getQuestionIcon(),
        ) == Messages.OK
        if (confirmed) server.turnOff()
    }

    private fun action(text: String, enabledWhen: (LspStatus) -> Boolean, perform: () -> Unit): AnAction =
        object : DumbAwareAction(text) {
            override fun getActionUpdateThread() = ActionUpdateThread.BGT

            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = enabledWhen(server.state.value.status)
            }

            override fun actionPerformed(e: AnActionEvent) = perform()
        }

    private fun label(status: LspStatus): String = when (status) {
        LspStatus.SIGNED_OUT -> "signed out"
        LspStatus.STARTING -> "starting…"
        LspStatus.TURNED_OFF -> "off"
        LspStatus.READY -> "ready"
        LspStatus.CONNECTED -> "connected"
        LspStatus.FAILED -> "error"
    }

    override fun dispose() = Unit
}
