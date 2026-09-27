// DATAMIMIC for JetBrains IDEs
// Copyright (c) 2026 Rapiddweller Asia Co., Ltd.
// SPDX-License-Identifier: MIT

package com.rapiddweller.datamimic.ide.generation

import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.rapiddweller.datamimic.core.PlatformOrigin
import com.rapiddweller.datamimic.core.generation.GenerationRun
import com.rapiddweller.datamimic.core.generation.TaskType
import com.rapiddweller.datamimic.core.workspace.FolderIdentity
import com.rapiddweller.datamimic.core.workspace.WorkspaceSession
import com.rapiddweller.datamimic.ide.AuthState
import com.rapiddweller.datamimic.ide.DatamimicPlatform
import com.rapiddweller.datamimic.ide.activeProject
import com.rapiddweller.datamimic.ide.editing.flushPlatformEdits
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun generationTargetError(
    expected: FolderIdentity,
    actual: FolderIdentity?,
    signedInOrigin: PlatformOrigin?,
    session: WorkspaceSession?,
): String? = when {
    actual == null -> "Open the DATAMIMIC project before running this configuration."
    actual.origin != expected.origin || actual.projectId != expected.projectId -> "This run configuration belongs to another DATAMIMIC project."
    signedInOrigin != expected.origin -> "Sign in to ${expected.origin.value} before running this configuration."
    session == null -> "DATAMIMIC is not connected to this project."
    else -> null
}

internal fun generationTargetError(project: Project, identity: FolderIdentity): String? {
    val active = project.activeProject()
    return generationTargetError(
        identity,
        active.identity,
        (DatamimicPlatform.getInstance().state.value as? AuthState.SignedIn)?.origin,
        active.session(),
    )
}

internal fun generationSession(project: Project, identity: FolderIdentity): WorkspaceSession {
    generationTargetError(project, identity)?.let(::error)
    return checkNotNull(project.activeProject().session())
}

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
            val notOnPlatform = flushPlatformEdits(project, generationSession(project, identity))
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
            generationSession(project, identity)
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
