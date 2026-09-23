// DATAMIMIC for JetBrains IDEs
// Copyright (c) 2026 Rapiddweller Asia Co., Ltd.
// SPDX-License-Identifier: MIT

@file:OptIn(ExperimentalSerializationApi::class)

package com.rapiddweller.datamimic.core.generation

import com.rapiddweller.datamimic.core.HttpMethod
import com.rapiddweller.datamimic.core.PlatformHttp
import com.rapiddweller.datamimic.core.encode
import com.rapiddweller.datamimic.core.json
import kotlinx.coroutines.delay
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

@Serializable
enum class TaskType(val label: String) {
    @SerialName("standard") STANDARD("Standard run"),
    @SerialName("timed_5min") TIMED_5_MIN("Timed: 5 minutes"),
    @SerialName("timed_30min") TIMED_30_MIN("Timed: 30 minutes"),
    @SerialName("timed_1hour") TIMED_1_HOUR("Timed: 1 hour"),
    @SerialName("infinite") INFINITE("Continuous until stopped"),
}

/** The generate route's outcome: 0 done, 1 failed, 3 accepted and still running (HTTP 202). */
enum class GenerateOutcome(val returnCode: Int) {
    SUCCEEDED(0),
    FAILED(1),
    RUNNING(3),
}

@Serializable
private data class GenerateResponse(val returncode: Int, val message: String? = null, @SerialName("task_id") val taskId: String)

data class GenerateResult(val outcome: GenerateOutcome, val taskId: String, val message: String?)

@Serializable
enum class TaskStatus(val terminal: Boolean, val succeeded: Boolean = false) {
    QUEUED(false),
    RUNNING(false),
    RETRY(false),
    FINALIZING(false),
    SUCCESS(true, succeeded = true),
    SUCCESS_WITH_WARNING(true, succeeded = true),
    WORKER_FAILURE(true),
    DISPATCH_FAILURE(true),
    FINALIZATION_FAILURE(true),
    CANCELLED(true),
    DELETED(true),

    /** A status this client does not know yet; treated as still running. */
    UNKNOWN(false),
}

@Serializable
private data class TaskSearchResponse(val data: List<TaskRow>) {
    @Serializable
    data class TaskRow(@SerialName("task_id") val taskId: String, val status: TaskStatus? = null)
}

@Serializable
@JsonClassDiscriminator("extension")
sealed interface PreviewRecord {
    val name: String

    @Serializable
    @SerialName("csv")
    data class Csv(override val name: String, val code: CsvCode) : PreviewRecord

    @Serializable
    @SerialName("json")
    data class Json(override val name: String, val code: List<JsonObject>) : PreviewRecord

    @Serializable
    @SerialName("xml")
    data class Xml(override val name: String, val code: String) : PreviewRecord
}

@Serializable
data class CsvCode(val rowData: List<JsonObject>, val columns: List<CsvColumn>)

@Serializable
data class CsvColumn(val name: String, val selector: String)

@Serializable
private data class PreviewResponse(val preview: List<PreviewRecord>)

/** What the IDE renders for one generated product. */
sealed interface PreviewContent {
    val name: String

    data class Table(override val name: String, val columns: List<String>, val rows: List<List<String>>) : PreviewContent

    data class Text(override val name: String, val text: String) : PreviewContent
}

class GenerationApi(private val http: PlatformHttp) {
    fun generate(projectId: String, taskType: TaskType): GenerateResult {
        val body = buildJsonObject {
            put("timeout", DISPATCH_WAIT_SECONDS)
            put("task_type", json.encodeToJsonElement(TaskType.serializer(), taskType))
        }
        val response = json.decodeFromString<GenerateResponse>(http.postJson("${base(projectId)}/generate", body.toString()))
        val outcome = GenerateOutcome.entries.firstOrNull { it.returnCode == response.returncode }
            ?: throw IllegalStateException("The platform returned an unknown generation result ${response.returncode}.")
        return GenerateResult(outcome, response.taskId, response.message)
    }

    fun status(projectId: String, taskId: String): TaskStatus {
        val body = buildJsonObject {
            putJsonObject("filters") { put("task_id", taskId) }
            putJsonObject("pagination") { put("page", 1); put("per_page", 1) }
        }
        val response = json.decodeFromString<TaskSearchResponse>(http.postJson("${base(projectId)}/tasks/search", body.toString()))
        return response.data.firstOrNull { it.taskId == taskId }?.status ?: TaskStatus.UNKNOWN
    }

    fun logs(projectId: String, taskId: String): String =
        http.send(HttpMethod.GET, "${base(projectId)}/tasks/${encode(taskId)}/logs?encode=false").body

    fun previews(projectId: String, taskId: String): List<PreviewContent> =
        json.decodeFromString<PreviewResponse>(http.getJson("${base(projectId)}/task/${encode(taskId)}/preview")).preview.map(::toContent)

    private fun base(projectId: String) = "/api/v2/projects/${encode(projectId)}"

    private companion object {
        /** Seconds the platform waits for the run before answering "still running". */
        const val DISPATCH_WAIT_SECONDS = 30
    }
}

internal const val MAX_PREVIEW_ROWS = 500

internal fun toContent(record: PreviewRecord): PreviewContent = when (record) {
    is PreviewRecord.Csv -> PreviewContent.Table(
        record.name,
        record.code.columns.map { it.name },
        record.code.rowData.take(MAX_PREVIEW_ROWS).map { row -> record.code.columns.map { cell(row[it.selector]) } },
    )
    is PreviewRecord.Json -> {
        val columns = record.code.flatMap { it.keys }.distinct()
        val flat = record.code.all { row -> row.values.none { it is JsonObject || it is JsonArray } }
        if (flat) {
            PreviewContent.Table(record.name, columns, record.code.take(MAX_PREVIEW_ROWS).map { row -> columns.map { cell(row[it]) } })
        } else {
            PreviewContent.Text(record.name, prettyJson.encodeToString(JsonArray.serializer(), JsonArray(record.code.take(MAX_PREVIEW_ROWS))))
        }
    }
    is PreviewRecord.Xml -> PreviewContent.Text(record.name, record.code)
}

private val prettyJson = Json { prettyPrint = true }

private fun cell(value: JsonElement?): String = when (value) {
    null -> ""
    is JsonPrimitive -> value.content
    else -> value.toString()
}

data class RunOutcome(val taskId: String, val status: TaskStatus, val message: String?)

private const val POLL_INTERVAL_MS = 3_000L
private const val MAX_OBSERVATION_MS = 30 * 60 * 1_000L

/**
 * Dispatches a run and observes it until the platform reports a terminal status. The platform owns the run:
 * cancelling the caller only stops observing, and a run still going after [MAX_OBSERVATION_MS] keeps running there.
 */
suspend fun GenerationApi.run(projectId: String, taskType: TaskType, onStatus: (TaskStatus) -> Unit = {}): RunOutcome {
    val result = generate(projectId, taskType)
    val immediate = when (result.outcome) {
        GenerateOutcome.SUCCEEDED -> TaskStatus.SUCCESS
        GenerateOutcome.FAILED -> TaskStatus.WORKER_FAILURE
        GenerateOutcome.RUNNING -> null
    }
    if (immediate != null) return RunOutcome(result.taskId, immediate, result.message)
    var status = TaskStatus.RUNNING
    var waited = 0L
    while (!status.terminal && waited < MAX_OBSERVATION_MS) {
        delay(POLL_INTERVAL_MS)
        waited += POLL_INTERVAL_MS
        status = status(projectId, result.taskId)
        onStatus(status)
    }
    return RunOutcome(result.taskId, status, result.message)
}
