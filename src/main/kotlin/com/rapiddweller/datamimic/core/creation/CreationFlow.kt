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

    /** The fields that the dialog shows beside the name. */
    val fields: List<Field> get() = emptyList()

    /** The name that the dialog shows first. */
    val initialName: String get() = ""

    /** Why [name] cannot be the name of the new file, or null. */
    fun nameProblem(name: String): String? = genericNameProblem(name)

    /**
     * @param dir the project folder of the new file, empty for the project root.
     * @param values the value of each [Field.id]: the selected [Choice.Option.value], the text of a [TextInput], or the
     *   Base64 content of the file of a [FileInput]. A missing value is the default of a [Choice] and empty for the others.
     */
    fun toRequest(dir: String, name: String, values: Map<String, String> = emptyMap()): CreationRequest
}

/** One input of the creation dialog beside the name. The dialog shows each kind with its own widget. */
sealed interface Field {
    val id: String
    val label: String
}

/** A choice with fixed options, for example the generation mode. [default] is the [Option.value] that is selected first. */
data class Choice(override val id: String, override val label: String, val options: List<Option>, val default: String) : Field {
    /** [value] goes to the platform; [label] is the text that the dialog shows. */
    data class Option(val value: String, val label: String)

    /** The selected value in [values], or [default]. A value that is not an option is a programming error. */
    fun valueIn(values: Map<String, String>): String =
        (values[id] ?: default).also { value -> require(options.any { it.value == value }) { "$value is not an option of $id." } }
}

/**
 * A text. An empty optional text is not sent to the platform, so the platform default applies. [hint] shows in an empty
 * field. The dialog folds [advanced] texts into one group.
 */
data class TextInput(
    override val id: String,
    override val label: String,
    val required: Boolean = false,
    val advanced: Boolean = false,
    val default: String = "",
    val hint: String = "",
) : Field

/** A local file. The dialog value is the path. The flow gets the Base64 content of the file. [extensions] have no dot. */
data class FileInput(override val id: String, override val label: String, val extensions: List<String>) : Field
