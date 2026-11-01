// DATAMIMIC for JetBrains IDEs
// Copyright (c) 2026 Rapiddweller Asia Co., Ltd.
// SPDX-License-Identifier: MIT

package com.rapiddweller.datamimic.core.workspace

/**
 * Keeps the result of the request that started last. A slow earlier request must not replace a newer result:
 * take a ticket with [begin] before the request starts, and pass it to [offer] when the answer arrives.
 */
class LatestResult<T : Any> {
    private var started = 0L
    private var stored = 0L
    private var value: T? = null

    val current: T?
        @Synchronized get() = value

    @Synchronized
    fun begin(): Long = ++started

    /** Stores [result] unless a request that started later stored one already. Returns the newest result. */
    @Synchronized
    fun offer(ticket: Long, result: T): T {
        if (ticket > stored) {
            stored = ticket
            value = result
        }
        return checkNotNull(value)
    }
}
