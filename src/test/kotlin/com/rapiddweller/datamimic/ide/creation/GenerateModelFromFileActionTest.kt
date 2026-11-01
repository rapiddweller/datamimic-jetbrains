// DATAMIMIC for JetBrains IDEs
// Copyright (c) 2026 Rapiddweller Asia Co., Ltd.
// SPDX-License-Identifier: MIT

package com.rapiddweller.datamimic.ide.creation

import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class GenerateModelFromFileActionTest : BasePlatformTestCase() {
    fun `test the action is hidden outside a DATAMIMIC project folder`() {
        val action = GenerateModelFromFileAction()
        val noProject = TestActionEvent.createTestEvent(action, SimpleDataContext.EMPTY_CONTEXT)
        val plainProject = TestActionEvent.createTestEvent(
            action,
            SimpleDataContext.builder()
                .add(CommonDataKeys.PROJECT, project)
                .add(CommonDataKeys.VIRTUAL_FILE, myFixture.tempDirFixture.createFile("data/customers.json", "{}"))
                .build(),
        )

        for (event in listOf(noProject, plainProject)) {
            action.update(event)
            assertFalse(event.presentation.isEnabledAndVisible)
        }
    }
}
