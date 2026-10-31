// DATAMIMIC for JetBrains IDEs
// Copyright (c) 2026 Rapiddweller Asia Co., Ltd.
// SPDX-License-Identifier: MIT

package com.rapiddweller.datamimic.core.lsp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Runs [connect] again after a longer wait each time the connection is lost, until [reset] or [stop].
 * The last wait repeats, so a laptop that stays offline costs one request per minute.
 */
class Reconnector(
    private val scope: CoroutineScope,
    private val waitsMs: List<Long> = WAITS_MS,
    private val connect: suspend () -> Unit,
) {
    private var attempt = 0
    private var pending: Job? = null

    /** Whether a connection was lost and has not opened again yet. */
    @get:Synchronized
    val retrying: Boolean get() = attempt > 0

    @Synchronized
    fun retry() {
        pending?.cancel()
        val wait = waitsMs[minOf(attempt, waitsMs.lastIndex)]
        attempt++
        pending = scope.launch {
            delay(wait)
            connect()
        }
    }

    /** The connection is open, or a retry would not help: forget the failures but let a running [connect] finish. */
    @Synchronized
    fun reset() {
        attempt = 0
    }

    /** Cancels the wait or the running [connect], too. */
    @Synchronized
    fun stop() {
        pending?.cancel()
        pending = null
        attempt = 0
    }

    private companion object {
        val WAITS_MS = listOf(2_000L, 5_000L, 15_000L, 60_000L)
    }
}
