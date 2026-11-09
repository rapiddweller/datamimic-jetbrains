// DATAMIMIC for JetBrains IDEs
// Copyright (c) 2026 Rapiddweller Asia Co., Ltd.
// SPDX-License-Identifier: MIT

package com.rapiddweller.datamimic.ide.generation

import com.rapiddweller.datamimic.core.FakePlatform
import com.rapiddweller.datamimic.core.PlatformException
import com.rapiddweller.datamimic.core.PlatformHttp
import com.rapiddweller.datamimic.core.SessionService
import com.rapiddweller.datamimic.core.generation.GenerationApi
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.io.OutputStream
import java.net.http.HttpClient
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ArtifactDownloadTest {
    private val platform = FakePlatform()
    private val client = HttpClient.newHttpClient()
    private val sessions = SessionService(client, { null }, {})
    private val api = GenerationApi(PlatformHttp(client, sessions, "artifact-test"))

    @get:Rule
    val temp = TemporaryFolder()

    @Before
    fun setUp() {
        sessions.login(platform.origin, "ada@example.com", "secret")
    }

    @After
    fun tearDown() {
        client.shutdownNow()
        platform.close()
    }

    @Test
    fun `metadata and downloads use exact routes and preserve binary bytes`() {
        val name = "invoice +%.bin"
        val literalPercentName = "..%2Fescape.bin"
        val binary = byteArrayOf(0, 1, -1, 13, 10, 0)
        val zip = byteArrayOf(80, 75, 3, 4, -1)
        platform.generationArtifactMetadata = metadata(name, literalPercentName)
        platform.artifactPayload = binary
        platform.artifactZipPayload = zip

        assertEquals(listOf(name, literalPercentName), api.artifacts("p1", "generation-1").map { it.path })
        val single = java.io.ByteArrayOutputStream()
        api.downloadArtifact("p1", "generation-1", name, single) { true }
        val literalPercent = java.io.ByteArrayOutputStream()
        api.downloadArtifact("p1", "generation-1", literalPercentName, literalPercent) { true }
        val all = java.io.ByteArrayOutputStream()
        api.downloadArtifacts("p1", "generation-1", all) { true }

        assertArrayEquals(binary, single.toByteArray())
        assertArrayEquals(binary, literalPercent.toByteArray())
        assertArrayEquals(zip, all.toByteArray())
        assertEquals(listOf("/api/v2/projects/p1/tasks/generation-1/artifacts/metadata"), platform.artifactMetadataPaths)
        assertEquals(
            listOf(
                "/api/v2/projects/p1/tasks/generation-1/artifacts/invoice%20%2B%25.bin/download",
                "/api/v2/projects/p1/tasks/generation-1/artifacts/..%252Fescape.bin/download",
                "/api/v2/projects/p1/tasks/generation-1/artifacts/download",
            ),
            platform.artifactDownloadPaths,
        )
        assertEquals(listOf("artifact-test", "artifact-test", "artifact-test", "artifact-test"), platform.seenBindings.takeLast(4))
    }

    @Test
    fun `empty metadata and one failed load remain retryable`() {
        platform.failingGenerationArtifactMetadataReads = 1

        assertThrows(PlatformException::class.java) { api.artifacts("p1", "generation-1") }
        assertTrue(api.artifacts("p1", "generation-1").isEmpty())

        assertEquals(2, platform.generationArtifactMetadataReads)
    }

    @Test
    fun `nullable or non-flat metadata is rejected before a download route is used`() {
        platform.generationArtifactMetadata = """{"artifacts":null}"""
        assertThrows(SerializationException::class.java) { api.artifacts("p1", "generation-1") }

        platform.generationArtifactMetadata = """{"artifacts":[{"url":null}]}"""
        assertThrows(IllegalArgumentException::class.java) { api.artifacts("p1", "generation-1") }

        listOf("../escape.bin", "folder/../file.bin", "folder\\file.bin", ".", "..").forEach { name ->
            platform.generationArtifactMetadata = metadata(name)
            assertThrows(IllegalArgumentException::class.java) { api.artifacts("p1", "generation-1") }
        }

        assertTrue(platform.artifactDownloadPaths.isEmpty())
    }

    @Test
    fun `nested artifact paths are listed with their folder and downloaded segment by segment`() {
        val nested = "xml/deep/my order+1.xml"
        platform.generationArtifactMetadata = pathMetadata("flat.csv", "xml/order.xml", nested)
        platform.artifactPayload = byteArrayOf(1, 2, 3)

        val artifacts = api.artifacts("p1", "generation-1")
        val out = java.io.ByteArrayOutputStream()
        api.downloadArtifact("p1", "generation-1", nested, out) { true }

        assertEquals(listOf("flat.csv", "xml/order.xml", nested), artifacts.map { it.path })
        assertEquals("xml/order.xml (6 B)", artifacts[1].toString())
        assertArrayEquals(byteArrayOf(1, 2, 3), out.toByteArray())
        assertEquals(
            listOf("/api/v2/projects/p1/tasks/generation-1/artifacts/xml/deep/my%20order%2B1.xml/download"),
            platform.artifactDownloadPaths,
        )
    }

    @Test
    fun `the path field wins over the url, which the platform percent-encodes`() {
        platform.generationArtifactMetadata =
            """{"artifacts":[{"path":"my order.xml","url":"/api/v2/projects/p1/tasks/generation-1/artifacts/my%20order.xml/download","size":"6 B"}]}"""

        assertEquals(listOf("my order.xml"), api.artifacts("p1", "generation-1").map { it.path })
    }

    @Test
    fun `an older platform without the path field still lists nested artifacts from the url`() {
        platform.generationArtifactMetadata = metadata("flat.bin", "xml/order.xml")

        assertEquals(listOf("flat.bin", "xml/order.xml"), api.artifacts("p1", "generation-1").map { it.path })
    }

    @Test
    fun `a path with 32 segments is accepted and one with 33 is rejected`() {
        platform.generationArtifactMetadata = pathMetadata(segments(32))
        assertEquals(listOf(segments(32)), api.artifacts("p1", "generation-1").map { it.path })

        platform.generationArtifactMetadata = pathMetadata(segments(33))
        assertThrows(IllegalArgumentException::class.java) { api.artifacts("p1", "generation-1") }
        assertThrows(IllegalArgumentException::class.java) {
            api.downloadArtifact("p1", "generation-1", segments(33), java.io.ByteArrayOutputStream()) { true }
        }
        assertTrue(platform.artifactDownloadPaths.isEmpty())
    }

    @Test
    fun `traversal attempts in metadata are rejected before a download route is used`() {
        UNSAFE_PATHS.forEach { path ->
            platform.generationArtifactMetadata = pathMetadata(path)
            assertThrows("metadata path <$path>", IllegalArgumentException::class.java) { api.artifacts("p1", "generation-1") }
        }
        // WHY: the url form of an older platform must obey the same rules as the path field.
        listOf("../escape.bin", "xml/../../escape.bin", "xml//order.xml", "xml/./order.xml", "xml/order.xml/").forEach { name ->
            platform.generationArtifactMetadata = metadata(name)
            assertThrows("url <$name>", IllegalArgumentException::class.java) { api.artifacts("p1", "generation-1") }
        }

        assertTrue(platform.artifactDownloadPaths.isEmpty())
    }

    @Test
    fun `a blank segment is a valid name, as on the platform, and does not hide the other artifacts`() {
        platform.generationArtifactMetadata = pathMetadata("flat.csv", "xml/ /order.xml")

        assertEquals(listOf("flat.csv", "xml/ /order.xml"), api.artifacts("p1", "generation-1").map { it.path })
    }

    @Test
    fun `traversal attempts are rejected before a single download sends a request`() {
        UNSAFE_PATHS.forEach { path ->
            assertThrows("download path <$path>", IllegalArgumentException::class.java) {
                api.downloadArtifact("p1", "generation-1", path, java.io.ByteArrayOutputStream()) { true }
            }
        }

        assertTrue(platform.artifactDownloadPaths.isEmpty())
    }

    @Test
    fun `atomic download replaces only a complete stream`() {
        val target = target("result.bin", byteArrayOf(7, 7))
        val bytes = byteArrayOf(0, -1, 3, 4)
        platform.artifactPayload = bytes

        downloadAtomically(target, { true }, { false }) { output, keepGoing ->
            api.downloadArtifact("p1", "generation-1", "result.bin", output, keepGoing)
        }

        assertArrayEquals(bytes, Files.readAllBytes(target))
        assertNoDownloadTemps(target)
    }

    @Test
    fun `atomic download creates the missing subfolders of a nested artifact`() {
        val target = temp.root.toPath().resolve("xml").resolve("deep").resolve("order.xml")
        val bytes = byteArrayOf(5, 6, 7)
        platform.artifactPayload = bytes

        downloadAtomically(target, { true }, { false }) { output, keepGoing ->
            api.downloadArtifact("p1", "generation-1", "xml/deep/order.xml", output, keepGoing)
        }

        assertArrayEquals(bytes, Files.readAllBytes(target))
        assertNoDownloadTemps(target)
    }

    @Test
    fun `a failed nested download removes the folders it created and keeps the ones that existed`() {
        val root = temp.root.toPath()
        Files.createDirectories(root.resolve("xml"))
        val target = root.resolve("xml").resolve("deep").resolve("er").resolve("order.xml")
        platform.artifactPayload = byteArrayOf(5, 6, 7)
        platform.interruptArtifactDownload = true

        assertThrows(Exception::class.java) {
            downloadAtomically(target, { true }, { false }) { output, keepGoing ->
                api.downloadArtifact("p1", "generation-1", "xml/deep/er/order.xml", output, keepGoing)
            }
        }

        assertTrue(Files.isDirectory(root.resolve("xml")))
        assertFalse(Files.exists(root.resolve("xml").resolve("deep")))
    }

    @Test
    fun `a folder that holds other files stays after a failed download`() {
        val root = temp.root.toPath()
        val target = root.resolve("xml").resolve("order.xml")
        platform.artifactPayload = byteArrayOf(1)
        platform.interruptArtifactDownload = true

        assertThrows(Exception::class.java) {
            downloadAtomically(target, { true }, { false }) { output, keepGoing ->
                Files.createDirectories(target.parent)
                Files.writeString(target.parent.resolve("other.txt"), "mine")
                api.downloadArtifact("p1", "generation-1", "xml/order.xml", output, keepGoing)
            }
        }

        assertEquals("mine", Files.readString(target.parent.resolve("other.txt")))
    }

    @Test
    fun `a subfolder that becomes a symbolic link after the user chose is refused and nothing is written through it`() {
        val root = temp.newFolder("chosen").toPath()
        val outside = temp.newFolder("outside").toPath()
        val target = artifactTarget(root, "xml/order.xml")
        platform.artifactPayload = byteArrayOf(1, 2)
        // The user answers a prompt. Meanwhile another program replaces the subfolder by a link.
        assumeTrue("symbolic links are not available", runCatching { Files.createSymbolicLink(root.resolve("xml"), outside) }.isSuccess)

        assertThrows(IOException::class.java) {
            downloadAtomically(target, { true }, { false }, root) { output, keepGoing ->
                api.downloadArtifact("p1", "generation-1", "xml/order.xml", output, keepGoing)
            }
        }

        Files.list(outside).use { assertTrue(it.findAny().isEmpty) }
        assertTrue(platform.artifactDownloadPaths.isEmpty())
    }

    @Test
    fun `a file in the place of a subfolder fails the download and stays`() {
        val root = temp.root.toPath()
        Files.writeString(root.resolve("xml"), "not a folder")
        platform.artifactPayload = byteArrayOf(1)

        assertThrows(IOException::class.java) {
            downloadAtomically(root.resolve("xml").resolve("order.xml"), { true }, { false }, root) { output, keepGoing ->
                api.downloadArtifact("p1", "generation-1", "xml/order.xml", output, keepGoing)
            }
        }

        assertEquals("not a folder", Files.readString(root.resolve("xml")))
    }

    @Test
    fun `broken response keeps existing target and removes temp`() {
        val target = target("result.bin", byteArrayOf(7, 7))
        platform.artifactPayload = byteArrayOf(0, -1, 3, 4)
        platform.interruptArtifactDownload = true

        assertThrows(Exception::class.java) {
            downloadAtomically(target, { true }, { false }) { output, keepGoing ->
                api.downloadArtifact("p1", "generation-1", "result.bin", output, keepGoing)
            }
        }

        assertArrayEquals(byteArrayOf(7, 7), Files.readAllBytes(target))
        assertNoDownloadTemps(target)
    }

    @Test
    fun `cancellation and unsaved guard preserve destination and remove temp`() {
        val target = target("result.bin", byteArrayOf(7, 7))
        platform.artifactPayload = byteArrayOf(1, 2)

        assertThrows(CancellationException::class.java) {
            downloadAtomically(target, { false }, { false }) { output, keepGoing ->
                api.downloadArtifact("p1", "generation-1", "result.bin", output, keepGoing)
            }
        }
        assertArrayEquals(byteArrayOf(7, 7), Files.readAllBytes(target))
        assertNoDownloadTemps(target)

        assertThrows(IOException::class.java) {
            downloadAtomically(target, { true }, { true }) { output, keepGoing ->
                api.downloadArtifact("p1", "generation-1", "result.bin", output, keepGoing)
            }
        }
        assertArrayEquals(byteArrayOf(7, 7), Files.readAllBytes(target))
        assertNoDownloadTemps(target)
    }

    @Test
    fun `session replacement before publish keeps existing target and removes temp`() {
        val target = target("result.bin", byteArrayOf(7, 7))
        platform.artifactPayload = byteArrayOf(1, 2)

        assertThrows(CancellationException::class.java) {
            downloadAtomically(target, { true }, { false }) { output, keepGoing ->
                api.downloadArtifact("p1", "generation-1", "result.bin", output, keepGoing).also {
                    platform.sessionId = "replacement"
                    sessions.login(platform.origin, "ada@example.com", "secret")
                }
            }
        }

        assertArrayEquals(byteArrayOf(7, 7), Files.readAllBytes(target))
        assertNoDownloadTemps(target)
    }

    @Test
    fun `cancellation after the unsaved check keeps existing target and removes temp`() {
        val target = target("result.bin", byteArrayOf(7, 7))
        platform.artifactPayload = byteArrayOf(1, 2)
        var keepGoing = true

        assertThrows(CancellationException::class.java) {
            downloadAtomically(target, { keepGoing }, {
                keepGoing = false
                false
            }) { output, current ->
                api.downloadArtifact("p1", "generation-1", "result.bin", output, current)
            }
        }

        assertArrayEquals(byteArrayOf(7, 7), Files.readAllBytes(target))
        assertNoDownloadTemps(target)
    }

    @Test
    fun `first chunk reaches the temporary output before the response completes`() {
        val target = target("result.bin", byteArrayOf(7, 7))
        val first = byteArrayOf(0, -1, 3)
        val rest = byteArrayOf(4, 5)
        val release = CountDownLatch(1)
        val sent = CountDownLatch(1)
        val copied = CountDownLatch(1)
        platform.artifactFirstChunk = first
        platform.artifactPayload = rest
        platform.artifactChunkGate = release
        platform.artifactFirstChunkWritten = sent

        val future = CompletableFuture.runAsync {
            downloadAtomically(target, { true }, { false }) { output, keepGoing ->
                api.downloadArtifact("p1", "generation-1", "result.bin", FirstWriteOutput(output, copied), keepGoing)
            }
        }
        try {
            assertTrue(sent.await(5, TimeUnit.SECONDS))
            assertTrue("the first response bytes were buffered instead of streamed", copied.await(5, TimeUnit.SECONDS))
            assertArrayEquals(byteArrayOf(7, 7), Files.readAllBytes(target))
        } finally {
            release.countDown()
        }
        future.get(5, TimeUnit.SECONDS)

        assertArrayEquals(first + rest, Files.readAllBytes(target))
        assertNoDownloadTemps(target)
    }

    private fun metadata(vararg urlEntityNames: String) = urlEntityNames.joinToString(",", "{\"artifacts\":[", "]}") { name ->
        """{"url":"/api/v2/projects/p1/tasks/generation-1/artifacts/$name/download","size":"6"}"""
    }

    /** The 4.2.0 platform answers `path` and a percent-encoded `url` for each artifact. */
    private fun pathMetadata(vararg paths: String) = paths.joinToString(",", "{\"artifacts\":[", "]}") { path ->
        val url = path.split("/").joinToString("/") { java.net.URLEncoder.encode(it, Charsets.UTF_8).replace("+", "%20") }
        """{"path":${kotlinx.serialization.json.JsonPrimitive(path)},"url":"/api/v2/projects/p1/tasks/generation-1/artifacts/$url/download","size":"6 B"}"""
    }

    private fun segments(count: Int) = (1..count).joinToString("/") { "d$it" }

    private fun target(name: String, bytes: ByteArray): Path = temp.newFile(name).toPath().also { Files.write(it, bytes) }

    private fun assertNoDownloadTemps(target: Path) {
        Files.list(target.parent).use { files ->
            assertTrue(files.noneMatch { it.fileName.toString().startsWith(".datamimic-") })
        }
    }

    private companion object {
        /** What the platform rejects with 400 INVALID_FILE_PATH: empty, dot and parent segments, backslashes, control characters. */
        val UNSAFE_PATHS = listOf(
            "", ".", "..", "../escape.xml", "xml/../escape.xml", "xml/..", "./order.xml", "xml/./order.xml", "xml/.",
            "/order.xml", "xml//order.xml", "xml/", "xml\\order.xml", "..\\escape.xml",
            "xml/or\u0000der.xml", "xml/or\nder.xml", "xml/or\tder.xml", "xml/or\u007fder.xml", "xml/or\u0085der.xml",
        )
    }

    private class FirstWriteOutput(private val delegate: OutputStream, private val wrote: CountDownLatch) : OutputStream() {
        override fun write(value: Int) {
            wrote.countDown()
            delegate.write(value)
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            wrote.countDown()
            delegate.write(bytes, offset, length)
        }
    }
}
