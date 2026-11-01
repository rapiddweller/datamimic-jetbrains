// DATAMIMIC for JetBrains IDEs
// Copyright (c) 2026 Rapiddweller Asia Co., Ltd.
// SPDX-License-Identifier: MIT

package com.rapiddweller.datamimic.ide.creation

import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class GenerateModelFromFileActionTest : BasePlatformTestCase() {
    fun `test the actions are hidden outside a DATAMIMIC project folder`() {
        val file = myFixture.tempDirFixture.createFile("data/customers.json", "{}")
        for (action in listOf(GenerateModelFromFileAction(), MigrateFromFileAction())) check(action, file)
    }

    private fun check(action: FromFileAction, file: VirtualFile) {
        val noProject = TestActionEvent.createTestEvent(action, SimpleDataContext.EMPTY_CONTEXT)
        val plainProject = TestActionEvent.createTestEvent(
            action,
            SimpleDataContext.builder()
                .add(CommonDataKeys.PROJECT, project)
                .add(CommonDataKeys.VIRTUAL_FILE, file)
                .build(),
        )

        for (event in listOf(noProject, plainProject)) {
            action.update(event)
            assertFalse(event.presentation.isEnabledAndVisible)
        }
    }
}
