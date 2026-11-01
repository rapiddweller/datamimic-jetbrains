// DATAMIMIC for JetBrains IDEs
// Copyright (c) 2026 Rapiddweller Asia Co., Ltd.
// SPDX-License-Identifier: MIT

package com.rapiddweller.datamimic.ide.generation

import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.rapiddweller.datamimic.core.generation.GenerationRun
import com.rapiddweller.datamimic.core.generation.TaskType
import com.rapiddweller.datamimic.core.workspace.FolderIdentity
import com.rapiddweller.datamimic.ide.AuthState
import com.rapiddweller.datamimic.ide.DatamimicPlatform
import com.rapiddweller.datamimic.ide.platformSession
import com.rapiddweller.datamimic.ide.editing.flushPlatformEdits
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The one save, sync, warning and dispatch flow for tool-window and native generations. */
internal fun startGeneration(
    project: Project,
    identity: FolderIdentity,
    taskType: TaskType,
    scope: CoroutineScope,
    shouldStart: () -> Boolean = { true },
    acceptRun: (GenerationRun) -> Boolean,
    rejected: () -> Unit = {},
) {
    scope.launch(Dispatchers.EDT) {
        try {
            if (!shouldStart()) return@launch rejected()
            val notOnPlatform = flushPlatformEdits(project, platformSession(project, identity))
            if (notOnPlatform.isNotEmpty() && Messages.showOkCancelDialog(
                    project,
                    "These files have changes that are not on the platform, so the run would not use them:\n" + notOnPlatform.sorted().joinToString("\n"),
                    "Generate Data",
                    "Generate Anyway",
                    Messages.getCancelButton(),
                    Messages.getWarningIcon(),
                ) != Messages.OK
            ) return@launch rejected()
            if (!shouldStart()) return@launch rejected()
            platformSession(project, identity)
            val platform = DatamimicPlatform.getInstance()
            val auth = platform.state.value as? AuthState.SignedIn ?: error("Sign in before starting generation.")
            val generation = platform.generation.boundTo(identity.origin)
            val startedAt = System.currentTimeMillis()
            val dispatch = withBackgroundProgress(project, "Starting data generation for ${identity.projectName}") {
                withContext(Dispatchers.IO) { generation.dispatch(identity.projectId, taskType) }
            }
            val run = GenerationRun(generation, identity.projectId, dispatch.taskId, startedAt)
            run.awaitVisibility()
            if (platform.state.value != auth) {
                run.close()
                return@launch rejected()
            }
            if (!acceptRun(run)) run.close()
        } catch (e: CancellationException) {
            rejected()
            throw e
        } catch (e: Exception) {
            Messages.showErrorDialog(project, e.message ?: e.javaClass.simpleName, "Generation Failed")
            rejected()
        }
    }
}
