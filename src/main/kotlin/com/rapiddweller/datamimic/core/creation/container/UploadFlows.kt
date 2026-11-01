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

// ponytail: repeats the platform's name cleaning on purpose, because a late platform failure gives a misleading message.
private val UPLOAD_NAME = Regex("[A-Za-z0-9]([A-Za-z0-9._-]*[A-Za-z0-9])?")

/** The flows that make DATAMIMIC files from the local file [source], chosen by its extension. Empty for other files. */
fun uploadFlows(source: String): List<CreationFlow> {
    val fileName = source.substringAfterLast('/').substringAfterLast('\\')
    val name = fileName.substringBeforeLast('.')
    val flow = { label: String, type: UploadFileType, mode: Boolean, template: String? -> UploadFlow(label, type, mode, template, source, name) }
    return when (fileName.substringAfterLast('.', "").lowercase()) {
        "json" -> listOf(
            flow("Model from JSON", UploadFileType.JSON_MODEL, true, null),
            flow("Model and Template from JSON", UploadFileType.JSON_TEMPLATE, false, ".template.json"),
        )
        "xml" -> listOf(
            flow("Model from XML", UploadFileType.XML_MODEL, true, null),
            flow("Model and Template from XML", UploadFileType.XML_TEMPLATE, false, ".template.xml"),
        )
        "csv" -> listOf(flow("Model from CSV", UploadFileType.CSV_MODEL, false, null))
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
    private val withMode: Boolean,
    private val template: String?,
    private val source: String,
    override val initialName: String,
) : CreationFlow {
    override val id = "upload." + type.wire
    override val choices = if (withMode) listOf(GENERATION_MODE) else emptyList()

    override fun nameProblem(name: String): String? = uploadNameProblem(name)

    override fun toRequest(dir: String, name: String, choices: Map<String, String>): CreationRequest {
        val fields = buildJsonObject {
            put("file_name", name)
            if (withMode) put(GENERATION_MODE.id, GENERATION_MODE.valueIn(choices))
        }
        val targets = listOfNotNull(MODEL, template).map { projectPath(dir, name + it) }
        return CreationRequest.FromUpload(dir, name, source, type, fields, targets)
    }
}
