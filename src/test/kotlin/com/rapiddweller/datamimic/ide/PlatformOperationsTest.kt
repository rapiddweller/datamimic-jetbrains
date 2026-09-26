// DATAMIMIC for JetBrains IDEs
// Copyright (c) 2026 Rapiddweller Asia Co., Ltd.
// SPDX-License-Identifier: MIT

package com.rapiddweller.datamimic.ide

import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.rapiddweller.datamimic.core.PlatformProject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class PlatformOperationsTest : BasePlatformTestCase() {
    fun `test opening a project outlives the panel scope`() {
        val panelScope = CoroutineScope(SupervisorJob())
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val completed = CompletableDeferred<Unit>()
        val operations = PlatformOperations(
            project = null,
            scope = panelScope,
            active = null,
            selection = { null },
            open = { _, _ ->
                started.complete(Unit)
                release.await()
                completed.complete(Unit)
            },
        )

        try {
            operations.openProject(PlatformProject("p1", "Project"))
            PlatformTestUtil.waitWithEventsDispatching("project open did not start", { started.isCompleted }, 5)
            panelScope.cancel()
            release.complete(Unit)
            PlatformTestUtil.waitWithEventsDispatching("project open was cancelled with its panel", { completed.isCompleted }, 5)
        } finally {
            release.complete(Unit)
            panelScope.cancel()
        }
    }
}
