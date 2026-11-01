// DATAMIMIC for JetBrains IDEs
// Copyright (c) 2026 Rapiddweller Asia Co., Ltd.
// SPDX-License-Identifier: MIT

package com.rapiddweller.datamimic.core.creation.container

import com.rapiddweller.datamimic.core.creation.Choice
import com.rapiddweller.datamimic.core.creation.CreationFlow
import com.rapiddweller.datamimic.core.creation.street.CreationRequest
import com.rapiddweller.datamimic.core.creation.street.projectPath
import com.rapiddweller.datamimic.core.workspace.UploadFileType
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val MODEL = ".xml"

// The labels are the labels of the platform web UI, so that both show the same words.
private val GENERATION_MODE = Choice(
    id = "generation_mode",
    label = "Generation mode",
    options = listOf(Choice.Option("mirror", "Preserve exact values"), Choice.Option("synthetic", "Infer generators & protect PII")),
    default = "mirror",
)

private val PROJECT_DEFAULT = ""

/** The platform takes a delimiter in the CSV model task. [detected] is selected first; without it the project default applies. */
private fun delimiterChoice(detected: Char?): Choice {
    val named = listOf(';' to "Semicolon ( ; )", ',' to "Comma ( , )", '|' to "Pipe ( | )", '\t' to "Tab")
    val options = named.map { (delimiter, label) ->
        Choice.Option(delimiter.toString(), if (delimiter == detected) "$label - detected" else label)
    }
    val projectDefault = Choice.Option(PROJECT_DEFAULT, if (detected == null) "Project default - no delimiter detected" else "Project default")
    return Choice("delimiter", "Delimiter", options + projectDefault, detected?.toString() ?: PROJECT_DEFAULT)
}

// ponytail: repeats the platform's name cleaning on purpose, because a late platform failure gives a misleading message.
private val UPLOAD_NAME = Regex("[A-Za-z0-9]([A-Za-z0-9._-]*[A-Za-z0-9])?")

/**
 * The flows that make DATAMIMIC files from the local file [source], chosen by its extension. Empty for other files.
 *
 * @param dir the project folder of the new files, empty for the project root.
 * @param sourceProjectPath the project path of [source], or null when [source] is outside the project. Only a source
 *   in the project can be replaced by the new files.
 * @param csvSample the first lines of a CSV [source], for the delimiter.
 */
fun uploadFlows(source: String, dir: String = "", sourceProjectPath: String? = null, csvSample: String = ""): List<CreationFlow> {
    val fileName = source.substringAfterLast('/').substringAfterLast('\\')
    val name = fileName.substringBeforeLast('.')
    val flow = { label: String, type: UploadFileType, choices: List<Choice>, template: String? ->
        UploadFlow(label, type, choices, template, source, name, dir, sourceProjectPath)
    }
    return when (fileName.substringAfterLast('.', "").lowercase()) {
        "json" -> listOf(
            flow("Model from JSON", UploadFileType.JSON_MODEL, listOf(GENERATION_MODE), null),
            flow("Model and Template from JSON", UploadFileType.JSON_TEMPLATE, emptyList(), ".template.json"),
        )
        "xml" -> listOf(
            flow("Model from XML", UploadFileType.XML_MODEL, listOf(GENERATION_MODE), null),
            flow("Model and Template from XML", UploadFileType.XML_TEMPLATE, emptyList(), ".template.xml"),
        )
        "csv" -> listOf(flow("Model from CSV", UploadFileType.CSV_MODEL, listOf(delimiterChoice(detectDelimiter(csvSample))), null))
        else -> emptyList()
    }
}

/** Why [name] cannot be the base name of the uploaded files, or null. */
private fun uploadNameProblem(name: String): String? = when {
    name.isEmpty() -> "Enter a file name."
    !UPLOAD_NAME.matches(name) || ".." in name || "--" in name ->
        "Use only letters, digits, '.', '-' and '_'. Start and end with a letter or a digit. Do not use '..' or '--'."
    else -> null
}

/** @param template the extension of the template file, or null when the flow makes only the model. */
private class UploadFlow(
    override val label: String,
    private val type: UploadFileType,
    override val choices: List<Choice>,
    private val template: String?,
    private val source: String,
    stem: String,
    private val dir: String,
    private val sourceProjectPath: String?,
) : CreationFlow {
    override val id = "upload." + type.wire
    private val sourceName = source.substringAfterLast('/').substringAfterLast('\\')

    // WHY: the model name matches the XML source name (customers.xml). The platform then replaces the source.
    override val initialName = if (replacesSource(stem)) "$stem-model" else stem

    override fun nameProblem(name: String): String? =
        uploadNameProblem(name) ?: if (replacesSource(name)) "This name replaces the source file $sourceName." else null

    private fun replacesSource(name: String) =
        sourceProjectPath != null && listOfNotNull(MODEL, template).any { projectPath(dir, name + it).equals(sourceProjectPath, ignoreCase = true) }

    override fun toRequest(dir: String, name: String, choices: Map<String, String>): CreationRequest {
        val fields = buildJsonObject {
            put("file_name", name)
            for (choice in this@UploadFlow.choices) choice.valueIn(choices).takeIf { it != PROJECT_DEFAULT }?.let { put(choice.id, it) }
        }
        val targets = listOfNotNull(MODEL, template).map { projectPath(dir, name + it) }
        return CreationRequest.FromUpload(dir, name, source, type, fields, targets)
    }
}
