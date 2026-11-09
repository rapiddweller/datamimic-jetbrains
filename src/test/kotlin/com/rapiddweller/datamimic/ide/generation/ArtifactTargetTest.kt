// DATAMIMIC for JetBrains IDEs
// Copyright (c) 2026 Rapiddweller Asia Co., Ltd.
// SPDX-License-Identifier: MIT

package com.rapiddweller.datamimic.ide.generation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

class ArtifactTargetTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `a nested artifact resolves into the matching subfolder of the chosen folder`() {
        val root = temp.newFolder("chosen").toPath()

        assertEquals(root.resolve("flat.csv"), artifactTarget(root, "flat.csv"))
        assertEquals(root.resolve("xml").resolve("order.xml"), artifactTarget(root, "xml/order.xml"))
        assertEquals(root.resolve("a").resolve("b").resolve("c.json"), artifactTarget(root, "a/b/c.json"))
    }

    @Test
    fun `names that only look like traversal stay inside the chosen folder`() {
        val root = temp.newFolder("chosen").toPath()

        listOf("...", "a..b", "..a", "a..", "%2e%2e", "..%2Fescape").forEach { name ->
            val target = artifactTarget(root, "xml/$name")
            assertTrue("<$name> left the chosen folder: $target", target.startsWith(root))
        }
    }

    @Test
    fun `traversal attempts never resolve to a path`() {
        val root = temp.newFolder("chosen").toPath()

        listOf(
            "", ".", "..", "../escape.xml", "xml/../../escape.xml", "xml/..", "/etc/passwd", "xml//order.xml", "xml/",
            "xml\\..\\escape.xml", "..\\escape.xml", "xml/or\u0000der.xml", "xml/or\nder.xml", "C:\\escape.xml",
            (1..33).joinToString("/") { "d$it" },
        ).forEach { path ->
            assertThrows("path <$path>", IllegalArgumentException::class.java) { artifactTarget(root, path) }
        }
    }

    @Test
    fun `a symbolic link inside the artifact path is refused and nothing is written through it`() {
        val root = temp.newFolder("chosen").toPath()
        val outside = temp.newFolder("outside").toPath()
        assumeTrue("symbolic links are not available", symlink(root.resolve("xml"), outside))

        assertThrows(IOException::class.java) { artifactTarget(root, "xml/order.xml") }
        assertThrows(IOException::class.java) { artifactTarget(root, "xml/deep/order.xml") }

        Files.list(outside).use { assertTrue(it.findAny().isEmpty) }
    }

    @Test
    fun `a folder in the place of the file is refused`() {
        val root = temp.newFolder("chosen").toPath()
        Files.createDirectories(root.resolve("xml").resolve("order.xml"))

        assertThrows(IOException::class.java) { artifactTarget(root, "xml/order.xml") }
    }

    @Test
    fun `a new file needs no answer and an existing file is replaced only when the user says yes`() {
        val root = temp.newFolder("chosen").toPath()
        val asked = mutableListOf<Path>()

        assertEquals(root.resolve("xml").resolve("order.xml"), artifactSaveTarget(root, "xml/order.xml") { asked.add(it); false })
        assertTrue("a new file must not ask", asked.isEmpty())

        Files.createDirectories(root.resolve("xml"))
        Files.writeString(root.resolve("xml").resolve("order.xml"), "old")
        assertNull(artifactSaveTarget(root, "xml/order.xml") { asked.add(it); false })
        assertEquals(root.resolve("xml").resolve("order.xml"), artifactSaveTarget(root, "xml/order.xml") { asked.add(it); true })
        assertEquals(listOf(root.resolve("xml").resolve("order.xml"), root.resolve("xml").resolve("order.xml")), asked)
        assertEquals("old", Files.readString(root.resolve("xml").resolve("order.xml")))
    }

    @Test
    fun `a dangling link in the place of the file counts as existing and asks`() {
        val root = temp.newFolder("chosen").toPath()
        assumeTrue("symbolic links are not available", symlink(root.resolve("order.xml"), root.resolve("nowhere")))
        val asked = mutableListOf<Path>()

        assertNull(artifactSaveTarget(root, "order.xml") { asked.add(it); false })

        assertEquals(listOf(root.resolve("order.xml")), asked)
    }

    private fun symlink(link: Path, target: Path): Boolean = try {
        Files.createSymbolicLink(link, target)
        true
    } catch (_: UnsupportedOperationException) {
        false
    } catch (_: IOException) {
        false
    }
}
