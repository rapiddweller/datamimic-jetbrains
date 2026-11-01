// DATAMIMIC for JetBrains IDEs
// Copyright (c) 2026 Rapiddweller Asia Co., Ltd.
// SPDX-License-Identifier: MIT

package com.rapiddweller.datamimic.core.workspace

import com.rapiddweller.datamimic.core.HttpMethod
import com.rapiddweller.datamimic.core.PlatformHttp
import com.rapiddweller.datamimic.core.RequestBody
import com.rapiddweller.datamimic.core.encode
import com.rapiddweller.datamimic.core.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** What the platform makes from the uploaded file. [wire] is the `file_type` form field. */
enum class UploadFileType(val wire: String) {
    JSON_MODEL("json_generate_model"),
    JSON_TEMPLATE("json_generate_template"),
    XML_MODEL("xml_generate_model"),
    XML_TEMPLATE("xml_generate_template"),
    CSV_MODEL("csv_generate_model"),
}

sealed interface UploadStatus {
    data object Running : UploadStatus

    /** [paths] are project-relative. */
    data class Succeeded(val paths: List<String>) : UploadStatus

    data class Failed(val message: String) : UploadStatus
}

/** The platform's upload task: it creates DATAMIMIC files from a JSON, XML or CSV file, in the background. */
class UploadTaskApi(private val http: PlatformHttp) {
    /** Starts the task and returns its id. The task overwrites existing target files without a message. */
    fun start(
        projectId: String,
        type: UploadFileType,
        dir: String,
        sourceName: String,
        bytes: ByteArray,
        customFields: JsonObject,
    ): String {
        val fields = mapOf("file_type" to type.wire, "file_dir" to dir, "custom_fields" to customFields.toString())
        val response = http.send(HttpMethod.POST, base(projectId), RequestBody.Multipart(sourceName, bytes, fields))
        return checkNotNull(json.decodeFromString<UploadEnvelope>(response.body).taskId) { "The platform did not return a task id." }
    }

    fun status(projectId: String, taskId: String): UploadStatus {
        val envelope = json.decodeFromString<UploadEnvelope>(http.getJson("${base(projectId)}/${encode(taskId)}"))
        return when (envelope.returncode) {
            RUNNING -> UploadStatus.Running
            SUCCEEDED -> UploadStatus.Succeeded(envelope.data?.fileUris.orEmpty())
            FAILED -> UploadStatus.Failed(
                listOfNotNull(envelope.data?.error, envelope.message).firstOrNull(String::isNotBlank) ?: "The platform gave no reason.",
            )
            else -> throw IllegalStateException("The platform returned an unknown upload result ${envelope.returncode}.")
        }
    }

    private fun base(projectId: String) = "/api/v2/projects/${encode(projectId)}/workspace/tasks/upload"

    private companion object {
        const val SUCCEEDED = 0
        const val FAILED = 1
        const val RUNNING = 3
    }
}

@Serializable
private data class UploadEnvelope(
    val returncode: Int,
    val message: String? = null,
    @SerialName("task_id") val taskId: String? = null,
    val data: UploadData? = null,
)

@Serializable
private data class UploadData(
    @SerialName("file_uris") val fileUris: List<String> = emptyList(),
    val error: String? = null,
)
