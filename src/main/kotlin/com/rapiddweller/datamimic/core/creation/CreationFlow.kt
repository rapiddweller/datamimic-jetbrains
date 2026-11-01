// DATAMIMIC for JetBrains IDEs
// Copyright (c) 2026 Rapiddweller Asia Co., Ltd.
// SPDX-License-Identifier: MIT

package com.rapiddweller.datamimic.core.creation

import com.rapiddweller.datamimic.core.creation.street.CreationRequest
import com.rapiddweller.datamimic.core.creation.street.nameProblem as genericNameProblem

/** One choice in the creation dialog. A flow is plain data: it builds a request and does no I/O. */
interface CreationFlow {
    val id: String

    /** The text that the dialog shows. */
    val label: String

    /** The choices that the dialog shows beside the name. */
    val choices: List<Choice> get() = emptyList()

    /** The name that the dialog shows first. */
    val initialName: String get() = ""

    /** Why [name] cannot be the name of the new file, or null. */
    fun nameProblem(name: String): String? = genericNameProblem(name)

    /**
     * @param dir the project folder of the new file, empty for the project root.
     * @param choices the selected [Choice.Option.value] for each [Choice.id]. A missing choice takes its default.
     */
    fun toRequest(dir: String, name: String, choices: Map<String, String> = emptyMap()): CreationRequest
}

/** A choice with fixed options, for example the generation mode. [default] is the [Option.value] that is selected first. */
data class Choice(val id: String, val label: String, val options: List<Option>, val default: String) {
    /** [value] goes to the platform; [label] is the text that the dialog shows. */
    data class Option(val value: String, val label: String)

    /** The selected value in [choices], or [default]. A value that is not an option is a programming error. */
    fun valueIn(choices: Map<String, String>): String =
        (choices[id] ?: default).also { value -> require(options.any { it.value == value }) { "$value is not an option of $id." } }
}
