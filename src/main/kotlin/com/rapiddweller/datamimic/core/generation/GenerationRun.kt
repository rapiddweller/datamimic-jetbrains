// DATAMIMIC for JetBrains IDEs
// Copyright (c) 2026 Rapiddweller Asia Co., Ltd.
// SPDX-License-Identifier: MIT

package com.rapiddweller.datamimic.core.generation

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Read-only observation of one Platform task. Closing this object never cancels that task. */
class GenerationRun(
    private val api: GenerationApi,
    private val projectId: String,
    val taskId: String,
    private val startedAt: Long = System.currentTimeMillis(),
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val refreshLock = Mutex()
    private var closed = false
    private var status: TaskStatus? = null
    private var unavailable = false
    private var log: String = ""
    private var logCompleted = false
    private var previews: List<PreviewContent> = emptyList()
    private var previewsLoaded = false
    private var finishedAt: Long? = null

    suspend fun refresh(): GenerationSnapshot = refreshLock.withLock {
        check(!closed) { "The generation view is closed." }
        var statusError: Throwable? = null
        var logError: Throwable? = null
        var previewError: Throwable? = null

        runCatching { api.observe(projectId, taskId) }
            .onSuccess { observation ->
                when (observation) {
                    is TaskObservation.Known -> {
                        status = observation.status
                        unavailable = false
                        if (observation.status.terminal && finishedAt == null) finishedAt = now()
                    }
                    TaskObservation.Unknown -> {
                        status = TaskStatus.UNKNOWN
                        unavailable = false
                    }
                    TaskObservation.Unavailable -> {
                        status = null
                        unavailable = true
                    }
                }
            }
            .onFailure { statusError = it }
        if (!unavailable && !logCompleted) {
            runCatching { api.logs(projectId, taskId) }
                .onSuccess {
                    log = it.content
                    logCompleted = it.completed
                }
                .onFailure { logError = it }
        }
        if (!unavailable && status?.terminal == true && !previewsLoaded) {
            runCatching { api.previews(projectId, taskId) }
                .onSuccess {
                    previews = it
                    previewsLoaded = true
                }
                .onFailure { previewError = it }
        }
        GenerationSnapshot(
            taskId = taskId,
            status = status,
            unavailable = unavailable,
            elapsedMillis = (finishedAt ?: now()) - startedAt,
            log = log,
            logCompleted = logCompleted,
            previews = previews,
            previewsLoaded = previewsLoaded,
            statusError = statusError,
            logError = logError,
            previewError = previewError,
        )
    }

    /** Stops the Platform task. The next [refresh] observes its terminal state. */
    fun stop() = api.stop(taskId)

    fun close() {
        closed = true
    }
}

data class GenerationSnapshot(
    val taskId: String,
    val status: TaskStatus?,
    val unavailable: Boolean,
    val elapsedMillis: Long,
    val log: String,
    val logCompleted: Boolean,
    val previews: List<PreviewContent>,
    val previewsLoaded: Boolean,
    val statusError: Throwable?,
    val logError: Throwable?,
    val previewError: Throwable?,
) {
    val active get() = !unavailable && status?.terminal != true
    val needsRefresh get() = !unavailable && (active || !logCompleted)
}
