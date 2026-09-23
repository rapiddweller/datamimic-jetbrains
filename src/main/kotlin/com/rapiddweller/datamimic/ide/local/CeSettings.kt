// DATAMIMIC for JetBrains IDEs
// Copyright (c) 2026 Rapiddweller Asia Co., Ltd.
// SPDX-License-Identifier: MIT

package com.rapiddweller.datamimic.ide.local

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.rapiddweller.datamimic.ide.NOTIFICATION_GROUP
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.awt.datatransfer.StringSelection

/** Settings | Tools | DATAMIMIC */
class DatamimicConfigurable : BoundConfigurable("DATAMIMIC") {
    private val settings = service<LocalCeSettings>()

    override fun createPanel(): DialogPanel = panel {
        group("DATAMIMIC CE for local descriptors") {
            row("CLI:") {
                cell(
                    TextFieldWithBrowseButton().apply {
                        addBrowseFolderListener(null, FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor().withTitle("DATAMIMIC CE CLI"))
                    },
                ).align(AlignX.FILL)
                    .bindText({ settings.configuredCliPath.orEmpty() }, { settings.configuredCliPath = it })
                    .comment(
                        "Leave empty to use <code>.venv/bin/datamimic</code> of the project or <code>datamimic</code> on PATH. " +
                            "Install with <code>pip install \"datamimic-ce[mcp]\"</code>.",
                    )
            }
        }
    }
}

/** Copies an MCP server entry for the CE adapter, so any agent (Junie, AI Assistant, CLI agents) can author locally. */
class CopyCeMcpConfigurationAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val cli = service<LocalCeSettings>().cli(e.project)
        if (cli == null) {
            Messages.showErrorDialog(e.project, "DATAMIMIC CE was not found. Install it with pip install \"datamimic-ce[mcp]\".", "DATAMIMIC CE")
            return
        }
        // WHY: copying a configuration whose server crashes on start would only fail later, inside the agent.
        val problem = ProgressManager.getInstance().runProcessWithProgressSynchronously<String?, RuntimeException>(
            { cli.mcpProblem() },
            "Checking the DATAMIMIC CE MCP Adapter",
            false,
            e.project,
        )
        val mcp = cli.mcpExecutable
        if (problem != null || mcp == null) {
            Messages.showErrorDialog(e.project, problem, "DATAMIMIC CE")
            return
        }
        CopyPasteManager.getInstance().setContents(StringSelection(prettyJson.encodeToString(JsonObject.serializer(), mcpConfiguration(mcp.toString()))))
        NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification(
                "MCP configuration copied",
                "Paste it into your agent's MCP server settings. It starts the local CE adapter over stdio and needs no credentials.",
                NotificationType.INFORMATION,
            )
            .notify(e.project)
    }

    private companion object {
        val prettyJson = Json { prettyPrint = true }

        fun mcpConfiguration(command: String) = buildJsonObject {
            putJsonObject("mcpServers") {
                putJsonObject("datamimic-ce") {
                    put("command", command)
                    // WHY: the adapter defaults to SSE; agents launch MCP servers over stdio.
                    putJsonArray("args") {
                        add("--transport")
                        add("stdio")
                    }
                }
            }
        }
    }
}
