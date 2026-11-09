// DATAMIMIC for JetBrains IDEs
// Copyright (c) 2026 Rapiddweller Asia Co., Ltd.
// SPDX-License-Identifier: MIT

package com.rapiddweller.datamimic.core.generation

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The platform accepts 1 to 100 rows in one call. */
private const val MAX_OPERATE_ROWS = 100
private const val MAX_OPERATE_ID_LENGTH = 256
private const val MAX_OPERATE_BODY_BYTES = 1_048_576

/**
 * One operation of the Artifact Operate API, as the platform reference `operations/structural.md` lists it.
 * [takesValue] is false for an operation that has only a path, such as `delete(path)`.
 * The names and the syntax come from the operation registry of EE 4.2.0 (`json_operations.py`, `xml_operations.py`).
 */
class OperateOperation(val name: String, val takesValue: Boolean)

/** The two artifact formats that the Operate API accepts. The format decides the kind of path and the list of operations. */
enum class OperateFormat(val extension: String, val pathKind: String, val operations: List<OperateOperation>) {
    XML(
        "xml",
        "XPath",
        listOf(
            OperateOperation("appendChild", true),
            OperateOperation("clearAttributes", false),
            OperateOperation("clearChildren", false),
            OperateOperation("delete", false),
            OperateOperation("mergeAttributes", true),
            OperateOperation("removeAttributes", true),
            OperateOperation("removeChild", true),
            OperateOperation("replaceAttribute", true),
            OperateOperation("replaceChildren", true),
            OperateOperation("replaceNode", true),
            OperateOperation("set", true),
            OperateOperation("upsertAttribute", true),
            OperateOperation("upsertNode", true),
        ),
    ),
    JSON(
        "json",
        "JSONPath",
        listOf(
            OperateOperation("appendItem", true),
            OperateOperation("clearArray", false),
            OperateOperation("clearObject", false),
            OperateOperation("delete", false),
            OperateOperation("mergeObject", true),
            OperateOperation("removeItem", true),
            OperateOperation("removeProperties", true),
            OperateOperation("replaceArray", true),
            OperateOperation("set", true),
            OperateOperation("upsertProperty", true),
        ),
    ),
    ;

    companion object {
        /** The format of the artifact [path], or null when the Operate API cannot work on it. */
        fun of(path: String): OperateFormat? {
            val extension = path.substringAfterLast('/').substringAfterLast('.', "").lowercase()
            return entries.firstOrNull { it.extension == extension }
        }
    }
}

/** What the platform does with an operation that matches nothing. */
enum class NotMatchedAction(val wire: String) {
    WARN("warn"),
    ERROR("error"),
}

data class OperateRow(val id: String, val operations: List<String>)

/**
 * Builds the command `<operation>(<path>, <value>)` for one entry of `operations`. A command with no value ignores [value].
 * The platform reads the command at the first comma, so the path cannot contain one. The value can.
 */
fun operateCommand(operation: OperateOperation, path: String, value: String): String {
    val target = path.trim()
    require(target.isNotEmpty()) { "Enter a path." }
    require(',' !in target) { "A path cannot contain a comma, because the command splits at the first comma." }
    require('\n' !in target && '\r' !in target) { "A path must stay on one line." }
    if (!operation.takesValue) return "${operation.name}($target)"
    val text = value.trim()
    require(text.isNotEmpty()) { "Enter a value. Quote a string, for example 'text'." }
    require('\n' !in text && '\r' !in text) { "A value must stay on one line." }
    return "${operation.name}($target, $text)"
}

private val prettyJson = Json { prettyPrint = true }

private val SAFE_ROW_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")

/** The script writes `request.json` and `response.json`, so a row cannot use these names for its output file. */
private val RESERVED_FILE_NAMES = setOf("request", "response")

/** One call of `POST /api/v2/projects/{project_id}/tasks/{task_id}/artifacts/operate`, without the task and the secrets. */
class OperateRequest(val artifact: String, val action: NotMatchedAction, val rows: List<OperateRow>) {
    private val bodyText: String by lazy { buildBody() }

    /** What the platform rejects. A body without a problem is ready to send, or to save as `request.json`. */
    fun problems(): List<String> = buildList {
        if (rows.isEmpty()) add("Add at least one row.")
        if (rows.size > MAX_OPERATE_ROWS) add("A call has at most $MAX_OPERATE_ROWS rows.")
        rows.filter { it.id.length > MAX_OPERATE_ID_LENGTH }.forEach { add("The row ID \"${it.id.take(20)}…\" has more than $MAX_OPERATE_ID_LENGTH characters.") }
        if (bodyText.toByteArray(Charsets.UTF_8).size > MAX_OPERATE_BODY_BYTES) add("The request is larger than 1 MiB.")
    }

    /**
     * What makes the shell script unsafe or wrong, although the platform accepts the body. The ID of a row becomes the
     * name of an output file, so it must be safe in a shell, unique also on a file system that ignores case, and not the
     * name of a file that the script reads.
     */
    fun scriptProblems(): List<String> = buildList {
        rows.filter { !SAFE_ROW_ID.matches(it.id) }.forEach {
            add("The row ID \"${it.id}\" must start with a letter or digit and use only letters, digits, dot, underscore and hyphen.")
        }
        rows.filter { it.id.lowercase() in RESERVED_FILE_NAMES }.forEach { add("The row ID \"${it.id}\" is the name of a file that the command uses. Choose another ID.") }
        if (rows.map { it.id.lowercase() }.distinct().size != rows.size) add("Each row ID must be unique, and a different case does not count.")
    }

    /** The JSON body, indented, with a final newline so it is a good text file. */
    fun body(): String = bodyText

    private fun buildBody(): String = prettyJson.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            put("operation_not_matched_action", action.wire)
            put(
                "rows",
                buildJsonArray {
                    rows.forEach { row ->
                        add(
                            buildJsonObject {
                                put("id", row.id)
                                put("artifact", artifact)
                                put("operations", buildJsonArray { row.operations.forEach { add(it) } })
                            },
                        )
                    }
                },
            )
        },
    ) + "\n"

    /**
     * A shell script for the call. It reads `request.json` and the secret from the variables of the shell: the
     * script holds the platform [url], the [projectId] and the [task] (a task ID or `latest`), but never a token.
     *
     * @throws IllegalStateException when the request has a [problems] or [scriptProblems] entry, or the artifact is not XML or JSON.
     */
    fun script(url: String, projectId: String, task: String): String {
        (problems() + scriptProblems()).firstOrNull()?.let { error(it) }
        val extension = checkNotNull(OperateFormat.of(artifact)) { "The Operate API works on XML or JSON artifacts only." }.extension
        val header = listOf(
            "export DATAMIMIC_URL=${shellQuote(url)}",
            "export PROJECT_ID=${shellQuote(projectId)}",
            "export TASK_ID=${shellQuote(task)}",
            "# Set PROJECT_TOKEN in your shell before you run the command. The plugin never writes the token.",
            "",
        )
        val call = CURL_CALL.dropLast(1) + (CURL_CALL.last() + " \\")
        val read = if (rows.size == 1) {
            // WHY: the first artifact is the only artifact of a call with one row.
            listOf("  | jq --raw-output '.artifacts[0].content' > ${rows.single().id}.$extension")
        } else {
            // WHY: --show-error and && show a failed call, instead of a jq error about an error body.
            listOf("  --show-error --output response.json &&") + WRITE_EACH_ARTIFACT
        }
        return (header + call + read).joinToString("\n")
    }
}

/** The form that the platform tests on 4.2.0. The token, the URL, the project and the task are shell variables. */
private val CURL_CALL = listOf(
    "curl --fail-with-body --silent --request POST \\",
    "  \"\$DATAMIMIC_URL/api/v2/projects/\$PROJECT_ID/tasks/\$TASK_ID/artifacts/operate\" \\",
    "  -H \"Authorization: Bearer \$PROJECT_TOKEN\" \\",
    "  -H 'Content-Type: application/json' \\",
    "  --data @request.json",
)

/** From the platform reference `artifact-operate-api.md`: one file for each row, named by the row ID and the media type. */
private val WRITE_EACH_ARTIFACT = listOf(
    "jq --raw-output '.artifacts | to_entries[] | \"\\(.key)\\t\\(.value.id).\\({\"application/xml\": \"xml\", \"application/json\": \"json\"}[.value.media_type])\"' response.json |",
    "  while IFS=\$'\\t' read -r index file; do",
    "    jq --join-output --argjson index \"\$index\" '.artifacts[\$index].content' response.json > \"\$file\"",
    "  done &&",
    "jq --raw-output '.artifacts[] | select(any(.outcomes[]; .status != \"applied\")) | \"\\(.id): \\([.outcomes[] | select(.status != \"applied\") | \"\\(.operation_index) \\(.status) \\(.path)\"] | join(\", \"))\"' response.json",
)

private fun shellQuote(value: String) = "'" + value.replace("'", "'\\''") + "'"

@Serializable
data class OperateResponse(
    @SerialName("task_id") val taskId: String,
    @SerialName("operate_id") val operateId: String,
    val artifacts: List<OperatedArtifact>,
) {
    /** Task, call ID and, for each row, the outcomes without effect and the resulting text. */
    fun render(): String = buildString {
        appendLine("Task: $taskId")
        appendLine("Operate ID: $operateId")
        artifacts.forEach { artifact ->
            appendLine()
            appendLine("${artifact.id} (${artifact.mediaType})")
            val without = artifact.outcomes.filter { it.status != "applied" }
            if (without.isEmpty()) appendLine("All operations applied.")
            without.forEach { appendLine("${it.operationIndex} ${it.status}: ${it.command}" + (it.path?.let { path -> " (path $path)" } ?: "")) }
            append(artifact.content)
        }
    }
}

@Serializable
data class OperatedArtifact(
    val id: String,
    val content: String,
    @SerialName("media_type") val mediaType: String,
    val outcomes: List<OperateOutcome> = emptyList(),
)

@Serializable
data class OperateOutcome(
    @SerialName("operation_index") val operationIndex: Int,
    val command: String,
    val status: String,
    val path: String? = null,
)
