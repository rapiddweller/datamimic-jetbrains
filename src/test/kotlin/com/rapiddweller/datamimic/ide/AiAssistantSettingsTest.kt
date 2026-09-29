// DATAMIMIC for JetBrains IDEs
// Copyright (c) 2026 Rapiddweller Asia Co., Ltd.
// SPDX-License-Identifier: MIT

package com.rapiddweller.datamimic.ide

import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import javax.swing.JCheckBox

class AiAssistantSettingsTest : BasePlatformTestCase() {
    fun `test AI Assistant MCP publication is off by default`() {
        assertFalse(project.service<AiAssistantSettings>().publishMcp)
    }

    fun `test AI Assistant setting resets and applies only a changed value`() {
        val configurable = AiAssistantSettingsConfigurable(project)
        val checkbox = configurable.createComponent() as JCheckBox

        configurable.reset()
        assertFalse(checkbox.isSelected)
        checkbox.isSelected = true
        assertTrue(configurable.isModified)
        configurable.apply()
        assertTrue(project.service<AiAssistantSettings>().publishMcp)
        assertFalse(configurable.isModified)
        configurable.disposeUIResources()
    }
}
