// DATAMIMIC for JetBrains IDEs
// Copyright (c) 2026 Rapiddweller Asia Co., Ltd.
// SPDX-License-Identifier: MIT

package com.rapiddweller.datamimic.core.generation

import com.rapiddweller.datamimic.core.json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OperateRequestTest {
    private val xml = "xml/order.xml"

    private fun op(format: OperateFormat, name: String) = format.operations.first { it.name == name }

    private fun request(
        artifact: String = xml,
        action: NotMatchedAction = NotMatchedAction.WARN,
        vararg rows: OperateRow = arrayOf(OperateRow("case-1", listOf("set(//order/id, '2')"))),
    ) = OperateRequest(artifact, action, rows.toList())

    @Test
    fun `the command of one row has the form that the platform tests on 4 2 0`() {
        val script = request().script("https://dm.example.com", "p1", "latest")

        assertEquals(
            """
            export DATAMIMIC_URL='https://dm.example.com'
            export PROJECT_ID='p1'
            export TASK_ID='latest'
            # Set PROJECT_TOKEN in your shell before you run the command. The plugin never writes the token.

            curl --fail-with-body --silent --request POST \
              "${'$'}DATAMIMIC_URL/api/v2/projects/${'$'}PROJECT_ID/tasks/${'$'}TASK_ID/artifacts/operate" \
              -H "Authorization: Bearer ${'$'}PROJECT_TOKEN" \
              -H 'Content-Type: application/json' \
              --data @request.json \
              | jq --raw-output '.artifacts[0].content' > case-1.xml
            """.trimIndent(),
            script,
        )
    }

    @Test
    fun `the output file of one row has the extension of the artifact`() {
        val script = request("customer.json", rows = arrayOf(OperateRow("case-7", listOf("set(${'$'}.a, 1)")))).script("https://dm.example.com", "p1", "t-1")

        assertTrue(script, script.endsWith("| jq --raw-output '.artifacts[0].content' > case-7.json"))
        assertTrue(script, script.contains("export TASK_ID='t-1'"))
    }

    @Test
    fun `several rows write one file for each row, because the first artifact alone would hide the others`() {
        val script = request(rows = arrayOf(OperateRow("case-1", emptyList()), OperateRow("case-2", emptyList()))).script("https://dm.example.com", "p1", "latest")

        assertTrue(script, script.contains("  --data @request.json \\\n  --show-error --output response.json &&\n"))
        // WHY: the loop must not run on the body of a failed call, and the outcomes without effect must not be lost.
        assertTrue(script, script.contains("  done &&\njq --raw-output '.artifacts[] | select(any(.outcomes[];"))
        assertTrue(script, script.contains("""jq --join-output --argjson index "${'$'}index" '.artifacts[${'$'}index].content' response.json > "${'$'}file""""))
        assertFalse(script, script.contains("artifacts[0]"))
    }

    @Test
    fun `a secret never appears and the token stays a variable`() {
        val script = request().script("https://dm.example.com", "p1", "latest")

        assertEquals(1, Regex("Bearer").findAll(script).count())
        assertTrue(script.contains("Authorization: Bearer ${'$'}PROJECT_TOKEN\""))
        assertFalse(script.contains("PROJECT_TOKEN="))
    }

    @Test
    fun `a value that contains a quote is quoted for the shell`() {
        val script = request().script("https://dm.example.com/it's", "p'1", "latest")

        assertTrue(script, script.contains("export DATAMIMIC_URL='https://dm.example.com/it'\\''s'"))
        assertTrue(script, script.contains("export PROJECT_ID='p'\\''1'"))
    }

    @Test
    fun `the body has the fields of the platform in the order of the reference`() {
        val body = json.parseToJsonElement(
            request(action = NotMatchedAction.ERROR, rows = arrayOf(OperateRow("a", listOf("delete(//x)", "set(//y, 'ü, \"q\"')")), OperateRow("b", emptyList()))).body(),
        ).jsonObject

        assertEquals(listOf("operation_not_matched_action", "rows"), body.keys.toList())
        assertEquals("error", body.getValue("operation_not_matched_action").jsonPrimitive.content)
        val rows = body.getValue("rows").jsonArray.map { it.jsonObject }
        assertEquals(listOf("id", "artifact", "operations"), rows[0].keys.toList())
        assertEquals("a", rows[0].getValue("id").jsonPrimitive.content)
        assertEquals(xml, rows[0].getValue("artifact").jsonPrimitive.content)
        assertEquals(listOf("delete(//x)", "set(//y, 'ü, \"q\"')"), rows[0].getValue("operations").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(emptyList<String>(), rows[1].getValue("operations").jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `the body is indented so a person can read it, and ends with a newline for a file`() {
        val body = request().body()

        assertTrue(body, body.startsWith("{\n    \"operation_not_matched_action\": \"warn\","))
        assertTrue(body, body.endsWith("}\n"))
    }

    @Test
    fun `a valid request has no problem`() {
        assertEquals(emptyList<String>(), request().problems())
        assertEquals(emptyList<String>(), request().scriptProblems())
        assertEquals(emptyList<String>(), request("customer.JSON").problems())
        assertEquals(emptyList<String>(), request(rows = arrayOf(OperateRow("a.b_c-9", emptyList()))).scriptProblems())
    }

    @Test
    fun `the platform limits rows and the length of a row id`() {
        assertTrue(request(rows = emptyArray()).problems().single().contains("at least one row"))
        val tooMany = (1..101).map { OperateRow("case-$it", emptyList()) }
        assertTrue(OperateRequest(xml, NotMatchedAction.WARN, tooMany).problems().any { it.contains("at most 100 rows") })
        assertEquals(emptyList<String>(), OperateRequest(xml, NotMatchedAction.WARN, tooMany.take(100)).problems())

        assertTrue(request(rows = arrayOf(OperateRow("x".repeat(257), emptyList()))).problems().any { it.contains("256") })
        assertEquals(emptyList<String>(), request(rows = arrayOf(OperateRow("x".repeat(256), emptyList()))).problems())
    }

    @Test
    fun `a row id that is not safe as a file name stops only the script, not the body`() {
        listOf("", " ", "a b", "a;b", "${'$'}(x)", "a/b", "../x", "-x", ".hidden", "a\nb", "a'b", "ä", "a\"b", "a|b", "a>b", "Order #1").forEach { id ->
            val request = request(rows = arrayOf(OperateRow(id, emptyList())))
            assertTrue("id <$id> must stop the script", request.scriptProblems().isNotEmpty())
            assertEquals("id <$id> is a valid body", emptyList<String>(), request.problems())
        }
    }

    @Test
    fun `an id that the command already uses as a file name is refused`() {
        listOf("request", "response", "Request", "RESPONSE").forEach { id ->
            assertTrue("id <$id>", request(rows = arrayOf(OperateRow(id, emptyList()))).scriptProblems().isNotEmpty())
        }
        // WHY: `> request.json` would empty the body file that curl reads at the same time.
        assertTrue(request("customer.json", rows = arrayOf(OperateRow("request", emptyList()))).scriptProblems().isNotEmpty())
        assertEquals(emptyList<String>(), request(rows = arrayOf(OperateRow("requests", emptyList()))).scriptProblems())
    }

    @Test
    fun `two ids that differ only in case collide on a file system that ignores case`() {
        assertTrue(request(rows = arrayOf(OperateRow("a", emptyList()), OperateRow("a", emptyList()))).scriptProblems().any { it.contains("unique") })
        assertTrue(request(rows = arrayOf(OperateRow("Case", emptyList()), OperateRow("case", emptyList()))).scriptProblems().any { it.contains("unique") })
    }

    @Test
    fun `the script refuses an artifact that is not XML or JSON`() {
        assertThrows(IllegalStateException::class.java) { request("data.csv").script("https://dm.example.com", "p1", "latest") }
    }

    @Test
    fun `a body above one MiB is refused as the platform does`() {
        val big = OperateRow("a", listOf("set(//a, '${"x".repeat(1_048_576)}')"))

        assertTrue(request(rows = arrayOf(big)).problems().any { it.contains("1 MiB") })
    }

    @Test
    fun `the script of an invalid request is refused`() {
        assertThrows(IllegalStateException::class.java) { request(rows = emptyArray()).script("https://dm.example.com", "p1", "latest") }
    }

    @Test
    fun `the helper builds a command from the operation, a path and a value`() {
        val set = op(OperateFormat.XML, "set")
        val delete = op(OperateFormat.XML, "delete")

        assertEquals("set(//order/id, '2')", operateCommand(set, "//order/id", "'2'"))
        assertEquals("set(//order/note, 'a, b')", operateCommand(set, "  //order/note ", " 'a, b' "))
        assertEquals("delete(//order/note)", operateCommand(delete, "//order/note", ""))
        // WHY: a command with no value ignores a typed value instead of sending a call that the platform rejects.
        assertEquals("delete(//order/note)", operateCommand(delete, "//order/note", "ignored"))
        assertEquals("upsertAttribute(//order, kind=test)", operateCommand(op(OperateFormat.XML, "upsertAttribute"), "//order", "kind=test"))
        assertEquals("upsertProperty(${'$'}.customer.zip, '53111')", operateCommand(op(OperateFormat.JSON, "upsertProperty"), "${'$'}.customer.zip", "'53111'"))
    }

    @Test
    fun `the helper refuses what the platform cannot read`() {
        val set = op(OperateFormat.XML, "set")

        assertThrows("blank path", IllegalArgumentException::class.java) { operateCommand(set, "  ", "1") }
        assertThrows("comma in path", IllegalArgumentException::class.java) { operateCommand(set, "//a[b,c]", "1") }
        assertThrows("blank value", IllegalArgumentException::class.java) { operateCommand(set, "//a", "  ") }
        assertThrows("newline in value", IllegalArgumentException::class.java) { operateCommand(set, "//a", "x\ny") }
        assertThrows("newline in path", IllegalArgumentException::class.java) { operateCommand(set, "//a\n//b", "1") }
    }

    @Test
    fun `the operations are those of the platform reference for each format, without a function call`() {
        assertEquals(
            listOf(
                "set", "delete", "appendChild", "replaceChildren", "clearChildren", "mergeAttributes", "removeChild",
                "removeAttributes", "clearAttributes", "replaceNode", "replaceAttribute", "upsertNode", "upsertAttribute",
            ).sorted(),
            OperateFormat.XML.operations.map { it.name }.sorted(),
        )
        assertEquals(
            listOf("set", "delete", "mergeObject", "replaceArray", "appendItem", "clearArray", "clearObject", "upsertProperty", "removeProperties", "removeItem").sorted(),
            OperateFormat.JSON.operations.map { it.name }.sorted(),
        )
        assertTrue((OperateFormat.XML.operations + OperateFormat.JSON.operations).none { it.name.startsWith("{") || it.name == "function" })
    }

    @Test
    fun `only these operations take no value`() {
        assertEquals(listOf("clearAttributes", "clearChildren", "delete"), OperateFormat.XML.operations.filter { !it.takesValue }.map { it.name }.sorted())
        assertEquals(listOf("clearArray", "clearObject", "delete"), OperateFormat.JSON.operations.filter { !it.takesValue }.map { it.name }.sorted())
    }

    @Test
    fun `the format follows the extension of the artifact`() {
        assertEquals(OperateFormat.XML, OperateFormat.of("xml/order.xml"))
        assertEquals(OperateFormat.XML, OperateFormat.of("ORDER.XML"))
        assertEquals(OperateFormat.JSON, OperateFormat.of("a/b/customer.json"))
        assertNull(OperateFormat.of("customers.csv"))
        assertNull(OperateFormat.of("noextension"))
        assertNull(OperateFormat.of("folder.xml/readme"))
    }

    @Test
    fun `the response is shown with its outcomes and content`() {
        val response = json.decodeFromString<OperateResponse>(
            """{"task_id":"t-1","operate_id":"op-1","artifacts":[
                {"id":"case-1","content":"<order/>\n","media_type":"application/xml","outcomes":[
                  {"operation_index":0,"command":"set(//a, 1)","status":"applied","path":null},
                  {"operation_index":1,"command":"set(//b, 2)","status":"not_matched","path":"//b"}]}]}""",
        )

        assertEquals(
            """
            Task: t-1
            Operate ID: op-1

            case-1 (application/xml)
            1 not_matched: set(//b, 2) (path //b)
            <order/>
            """.trimIndent(),
            response.render().trimEnd(),
        )
    }
}
