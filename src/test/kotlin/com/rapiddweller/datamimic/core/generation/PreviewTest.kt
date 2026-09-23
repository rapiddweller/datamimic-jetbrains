// DATAMIMIC for JetBrains IDEs
// Copyright (c) 2026 Rapiddweller Asia Co., Ltd.
// SPDX-License-Identifier: MIT

package com.rapiddweller.datamimic.core.generation

import com.rapiddweller.datamimic.core.json
import org.junit.Assert.assertEquals
import org.junit.Test

class PreviewTest {
    private fun decode(raw: String): PreviewContent = toContent(json.decodeFromString<PreviewRecord>(raw))

    @Test
    fun `csv previews become tables in the platform's column order`() {
        val content = decode(
            """{"index":0,"extension":"csv","name":"people","encoding":"utf-8","code":{"tabname":"people",
               "rowData":[{"b":"2","a":"1"}],"columns":[{"name":"A","selector":"a","sortable":true},{"name":"B","selector":"b","sortable":true}]}}""",
        )

        assertEquals(PreviewContent.Table("people", listOf("A", "B"), listOf(listOf("1", "2"))), content)
    }

    @Test
    fun `flat json becomes a table, nested json stays text`() {
        assertEquals(
            PreviewContent.Table("orders", listOf("id", "total"), listOf(listOf("1", "9.5"), listOf("2", ""))),
            decode("""{"index":0,"extension":"json","name":"orders","encoding":"utf-8","code":[{"id":1,"total":9.5},{"id":2}]}"""),
        )
        val nested = decode("""{"index":0,"extension":"json","name":"c","encoding":"utf-8","code":[{"id":1,"address":{"city":"Hue"}}]}""")
        assertEquals(PreviewContent.Text::class, nested::class)
    }

    @Test
    fun `unknown task statuses are treated as still running`() {
        assertEquals(TaskStatus.UNKNOWN, json.decodeFromString<TaskStatusHolder>("""{"status":"PAUSED"}""").status)
    }

    @kotlinx.serialization.Serializable
    private data class TaskStatusHolder(val status: TaskStatus = TaskStatus.UNKNOWN)
}
