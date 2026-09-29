// DATAMIMIC for JetBrains IDEs
// Copyright (c) 2026 Rapiddweller Asia Co., Ltd.
// SPDX-License-Identifier: MIT

package com.rapiddweller.datamimic.ide

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.components.serviceIfCreated
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import javax.swing.JCheckBox
import javax.swing.JComponent

@State(name = "DatamimicAiAssistantSettings", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
@Service(Service.Level.PROJECT)
class AiAssistantSettings : PersistentStateComponent<AiAssistantSettings.State> {
    data class State(var publishMcp: Boolean = false)

    private var state = State()

    var publishMcp: Boolean
        get() = state.publishMcp
        set(value) {
            state.publishMcp = value
        }

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }
}

class AiAssistantSettingsConfigurable(private val project: Project) : Configurable {
    private var checkbox: JCheckBox? = null

    override fun getDisplayName() = "DATAMIMIC"

    override fun createComponent(): JComponent = JCheckBox("Publish DATAMIMIC MCP configuration for AI Assistant").also { checkbox = it }

    override fun reset() {
        checkbox?.isSelected = project.service<AiAssistantSettings>().publishMcp
    }

    override fun isModified(): Boolean = checkbox?.isSelected != project.service<AiAssistantSettings>().publishMcp

    override fun apply() {
        val settings = project.service<AiAssistantSettings>()
        val next = checkbox?.isSelected ?: settings.publishMcp
        if (settings.publishMcp != next) {
            settings.publishMcp = next
            project.serviceIfCreated<ActiveProject>()?.refreshAiAssistant()
        }
    }

    override fun disposeUIResources() {
        checkbox = null
    }
}
