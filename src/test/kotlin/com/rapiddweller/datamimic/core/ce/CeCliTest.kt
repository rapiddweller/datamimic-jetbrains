// DATAMIMIC for JetBrains IDEs
// Copyright (c) 2026 Rapiddweller Asia Co., Ltd.
// SPDX-License-Identifier: MIT

package com.rapiddweller.datamimic.core.ce

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Path

/** Drives [CeCli] against a stand-in executable that replays real DATAMIMIC CE 4.3.0 output. */
class CeCliTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Before
    fun posixOnly() = assumeFalse(System.getProperty("os.name").startsWith("Windows"))

    @Test
    fun `lint decodes every diagnostic even though the CLI exits with 1`() {
        val report = cli(lintFixture = "lint-broken.json").lint(Path.of("/workspace/broken.xml"))

        assertEquals(false, report.ok)
        assertEquals(listOf("DM303", "DM103", "DM212"), report.diagnostics.map { it.rule })
        assertEquals(listOf(LintSeverity.HINT, LintSeverity.ERROR, LintSeverity.ERROR), report.diagnostics.map { it.severity })
        assertEquals(listOf(1, 2, 2), report.diagnostics.map { it.line })
        assertTrue(report.diagnostics[1].fixHint!!.startsWith("Use an attribute exposed by the element model."))
    }

    @Test
    fun `a crashing CLI reports its last error line`() {
        val error = assertThrows(CeCliException::class.java) { cli(lintFixture = null).lint(Path.of("/workspace/x.xml")) }

        assertEquals("`datamimic lint` failed: ValidationError: observed must not exceed limit", error.message)
    }

    @Test
    fun `version and the authoring schema come from the CLI`() {
        val cli = cli(lintFixture = "lint-not-xml.json")

        assertEquals("4.3.0", cli.version())
        assertTrue(cli.authoringSchema().startsWith("""{"${'$'}schema":"https://json-schema.org/draft/2020-12/schema","title":"AuthoringSpecV1""""))
    }

    @Test
    fun `a configured path wins over a project venv, which wins over PATH`() {
        val configured = executable(temp.newFolder("configured"), "datamimic")
        val project = temp.newFolder("project")
        val venv = executable(File(project, ".venv/bin").apply { mkdirs() }, "datamimic")
        val onPath = executable(temp.newFolder("path"), "datamimic")

        assertEquals(configured.toPath(), CeCli.locate(configured.path, project.toPath(), onPath.parent)?.executable)
        assertEquals(venv.toPath(), CeCli.locate(null, project.toPath(), onPath.parent)?.executable)
        assertEquals(onPath.toPath(), CeCli.locate(" ", null, onPath.parent)?.executable)
        assertNull(CeCli.locate(null, temp.newFolder("empty").toPath(), null))
    }

    @Test
    fun `the MCP adapter is found next to the CLI and a broken install is reported`() {
        val bin = temp.newFolder("bin")
        val cli = CeCli(executable(bin, "datamimic").toPath())
        assertNull(cli.mcpExecutable)
        assertTrue(cli.mcpProblem()!!.startsWith("The DATAMIMIC CE MCP adapter is not installed."))

        val mcp = executable(bin, "datamimic-mcp", "#!/bin/sh\necho \"ImportError: cannot import name 'FastMCP' from 'fastmcp'\" >&2\nexit 1\n")
        assertEquals(mcp.toPath(), cli.mcpExecutable)
        assertEquals("The DATAMIMIC CE MCP adapter does not start: ImportError: cannot import name 'FastMCP' from 'fastmcp'", cli.mcpProblem())

        executable(bin, "datamimic-mcp", "#!/bin/sh\nexit 0\n")
        assertNull(cli.mcpProblem())
    }

    private fun cli(lintFixture: String?): CeCli {
        val lint = lintFixture?.let { "cat '${fixture(it)}'; exit 1" }
            ?: "echo 'Traceback (most recent call last):' >&2; echo 'ValidationError: observed must not exceed limit' >&2; exit 1"
        val script = """
            #!/bin/sh
            case "${'$'}1" in
              version) echo "DATAMIMIC version: 4.3.0" ;;
              lint) $lint ;;
              capabilities) cat '${fixture("capabilities-authoring-spec.json")}' ;;
              *) echo "unexpected ${'$'}1" >&2; exit 2 ;;
            esac
        """.trimIndent()
        return CeCli(executable(temp.newFolder(), "datamimic", script).toPath())
    }

    private fun fixture(name: String): String = File(javaClass.getResource("/ce/$name")!!.toURI()).absolutePath

    private fun executable(dir: File, name: String, script: String = "#!/bin/sh\n"): File =
        File(dir, name).apply {
            writeText(script)
            setExecutable(true)
        }
}
