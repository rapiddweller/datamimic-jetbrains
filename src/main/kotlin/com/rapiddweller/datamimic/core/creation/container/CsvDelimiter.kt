// DATAMIMIC for JetBrains IDEs
// Copyright (c) 2026 Rapiddweller Asia Co., Ltd.
// SPDX-License-Identifier: MIT

package com.rapiddweller.datamimic.core.creation.container

// The order is the tie-break. It follows the platform, which also tries the pipe first.
private val CANDIDATES = listOf('|', ';', ',', '\t')

/**
 * The delimiter that splits every line of [sample] into the same number of columns, at least two, or null. The platform
 * does not detect the delimiter of an uploaded CSV, so a wrong project default gives a model with one column.
 *
 * The last line is ignored when it has no line end, because a sample can end inside a line.
 */
fun detectDelimiter(sample: String): Char? {
    val lines = sample.lines().let { if (sample.endsWith('\n') || it.size == 1) it else it.dropLast(1) }.filter { it.isNotBlank() }
    return CANDIDATES
        .map { delimiter -> delimiter to lines.map { countOutsideQuotes(it, delimiter) } }
        .filter { (_, counts) -> counts.isNotEmpty() && counts.first() > 0 && counts.all { it == counts.first() } }
        .maxByOrNull { (_, counts) -> counts.first() }
        ?.first
}

private fun countOutsideQuotes(line: String, delimiter: Char): Int {
    var quoted = false
    var count = 0
    for (c in line) {
        when {
            c == '"' -> quoted = !quoted
            c == delimiter && !quoted -> count++
        }
    }
    return count
}
