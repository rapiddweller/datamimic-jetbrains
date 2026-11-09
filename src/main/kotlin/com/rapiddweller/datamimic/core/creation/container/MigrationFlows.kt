// DATAMIMIC for JetBrains IDEs
// Copyright (c) 2026 Rapiddweller Asia Co., Ltd.
// SPDX-License-Identifier: MIT

package com.rapiddweller.datamimic.core.creation.container

import com.rapiddweller.datamimic.core.creation.CreationFlow
import com.rapiddweller.datamimic.core.creation.Field
import com.rapiddweller.datamimic.core.creation.FileInput
import com.rapiddweller.datamimic.core.creation.TextInput
import com.rapiddweller.datamimic.core.creation.street.CreationRequest
import com.rapiddweller.datamimic.core.creation.street.projectPath
import com.rapiddweller.datamimic.core.workspace.UploadFileType
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// The field ids are the keys of the platform's `gwaConfig`. The labels and hints are the texts of the platform web UI.
private const val SCHEMA_VERSION = "schemaVersion"
private val GWA_FIELDS = listOf(
    TextInput(SCHEMA_VERSION, "Schema version", required = true, hint = "Schema version used during migration"),
    TextInput("sheetName", "Sheet name", advanced = true, hint = "Sheet with the test cases"),
    TextInput("idColumn", "ID column", advanced = true, hint = "Column with the test case identifiers"),
    TextInput("templateColumn", "Template column", advanced = true, hint = "Column with the template file names"),
    TextInput("operationColumnPrefix", "Operation column prefix", advanced = true, hint = "Prefix of the operation columns"),
    TextInput("templatePrefix", "Template prefix", advanced = true, hint = "Prefix of the generated template file names"),
    TextInput("templateSuffix", "Template suffix", advanced = true, hint = "Suffix before the extension"),
    TextInput("templateDirectory", "Template directory", advanced = true, hint = "Folder of the template files"),
)

private const val INI_CONTENT = "iniContentB64"
private const val TEMPLATE_DIRECTORY = "templateDirectory"

/** The start value of the AMA template folder. The dialog shows it, because the templates are written there. */
private const val AMA_TEMPLATE_FOLDER = "template"
private val AMA_FIELDS = listOf(
    FileInput(INI_CONTENT, "INI file", extensions = listOf("ini")),
    TextInput(TEMPLATE_DIRECTORY, "Template folder", default = AMA_TEMPLATE_FOLDER),
)

/** The flows that migrate the local file [source], chosen by its extension. Empty for other files. */
fun migrationFlows(source: String): List<CreationFlow> {
    val fileName = source.substringAfterLast('/').substringAfterLast('\\')
    val stem = fileName.substringBeforeLast('.')
    return when (fileName.substringAfterLast('.', "").lowercase()) {
        "xlsm" -> listOf(MigrationFlow("GWA migration", UploadFileType.GWA_MIGRATION, GWA_FIELDS, source, stem, ".opctl.csv", ::gwaFields))
        "ama" -> listOf(MigrationFlow("AMA migration", UploadFileType.AMA_MIGRATION, AMA_FIELDS, source, stem, ".xml", ::amaFields, ::amaFolders))
        else -> emptyList()
    }
}

private fun JsonObjectBuilder.putText(key: String, value: String?) {
    value?.trim()?.takeIf { it.isNotEmpty() }?.let { put(key, it) }
}

private fun gwaFields(values: Map<String, String>) = buildJsonObject {
    put("gwaConfig", buildJsonObject { GWA_FIELDS.forEach { putText(it.id, values[it.id]) } })
}

private fun amaFields(values: Map<String, String>) = buildJsonObject {
    put("amaConfig", buildJsonObject {
        putText(INI_CONTENT, values[INI_CONTENT])
        putText(TEMPLATE_DIRECTORY, values[TEMPLATE_DIRECTORY])
    })
}

private fun amaFolders(dir: String, values: Map<String, String>) =
    listOfNotNull(values[TEMPLATE_DIRECTORY]?.trim()?.takeIf { it.isNotEmpty() }?.let { projectPath(dir, it) })

/**
 * One migration of the platform. The platform writes the model or the `.opctl.csv` file with the name of the user.
 *
 * @param extension the extension of the file that the IDE opens.
 * @param config the flow-specific part of the `custom_fields` form field, built from the dialog values.
 * @param folders project folders in which the platform can replace more files.
 */
private class MigrationFlow(
    override val label: String,
    private val type: UploadFileType,
    override val fields: List<Field>,
    private val source: String,
    stem: String,
    private val extension: String,
    private val config: (Map<String, String>) -> JsonObject,
    private val folders: (String, Map<String, String>) -> List<String> = { _, _ -> emptyList() },
) : CreationFlow {
    override val id = "migration." + type.wire
    override val initialName = stem

    override fun nameProblem(name: String): String? = uploadNameProblem(name)

    override fun toRequest(dir: String, name: String, values: Map<String, String>): CreationRequest {
        val fields = buildJsonObject {
            put("file_name", name)
            config(values).forEach { (key, value) -> put(key, value) }
        }
        return CreationRequest.FromUpload(dir, name, source, type, fields, listOf(projectPath(dir, name + extension)), folders(dir, values))
    }
}
