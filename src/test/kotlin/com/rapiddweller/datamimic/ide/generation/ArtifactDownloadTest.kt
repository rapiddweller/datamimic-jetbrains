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
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
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

        assertEquals(listOf(name, literalPercentName), api.artifacts("p1", "generation-1").map { it.entityName })
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

        listOf("../escape.bin", "folder/file.bin", "folder\\file.bin", ".", "..").forEach { name ->
            platform.generationArtifactMetadata = metadata(name)
            assertThrows(IllegalArgumentException::class.java) { api.artifacts("p1", "generation-1") }
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

    private fun target(name: String, bytes: ByteArray): Path = temp.newFile(name).toPath().also { Files.write(it, bytes) }

    private fun assertNoDownloadTemps(target: Path) {
        Files.list(target.parent).use { files ->
            assertTrue(files.noneMatch { it.fileName.toString().startsWith(".datamimic-") })
        }
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
