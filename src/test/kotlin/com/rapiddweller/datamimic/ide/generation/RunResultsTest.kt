// DATAMIMIC for JetBrains IDEs
// Copyright (c) 2026 Rapiddweller Asia Co., Ltd.
// SPDX-License-Identifier: MIT

package com.rapiddweller.datamimic.ide.generation

import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.runInEdtAndWait
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextArea
import com.rapiddweller.datamimic.core.FakePlatform
import com.rapiddweller.datamimic.core.PlatformHttp
import com.rapiddweller.datamimic.core.SessionService
import com.rapiddweller.datamimic.core.generation.GenerationApi
import com.rapiddweller.datamimic.core.generation.GenerationRun
import com.rapiddweller.datamimic.core.workspace.FolderIdentity
import com.rapiddweller.datamimic.ide.AuthState
import com.rapiddweller.datamimic.ide.DatamimicPlatform
import com.rapiddweller.datamimic.ide.LoginInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.awt.Component
import java.awt.Container
import java.net.http.HttpClient
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JLabel

class RunResultsTest : BasePlatformTestCase() {
    fun `test artifact publication holds the unsaved check in the IDE write action`() {
        val platform = FakePlatform()
        val client = HttpClient.newHttpClient()
        val target = Files.createTempFile("artifact-download", ".bin")
        val protected = AtomicBoolean()
        platform.artifactPayload = byteArrayOf(0, -1, 3, 4)
        try {
            val sessions = SessionService(client, { null }, {})
            sessions.login(platform.origin, "ada@example.com", "secret")
            val api = GenerationApi(PlatformHttp(client, sessions, "artifact-test"))
            val future = CompletableFuture.runAsync {
                downloadAtomically(target, { true }, {
                    protected.set(ApplicationManager.getApplication().isWriteAccessAllowed())
                    false
                }) { output, keepGoing ->
                    api.downloadArtifact("p1", "generation-1", "result.bin", output, keepGoing)
                }
            }

            PlatformTestUtil.waitWithEventsDispatching("artifact publication did not finish", future::isDone, 5)
            future.get()
            assertTrue(protected.get())
            assertArrayEquals(platform.artifactPayload, Files.readAllBytes(target))
        } finally {
            Files.deleteIfExists(target)
            client.shutdownNow()
            platform.close()
        }
    }

    fun `test log update retains manual scroll position and follows the end`() {
        lateinit var area: JBTextArea
        lateinit var scroll: JBScrollPane
        var middle = 0
        val retained = AtomicBoolean()
        runInEdtAndWait {
            area = JBTextArea(lines(100))
            scroll = JBScrollPane(area).apply { setSize(200, 100) }
            scroll.doLayout()
            val bar = scroll.verticalScrollBar

            bar.value = bar.maximum / 2
            middle = bar.value
            updateOutput(scroll, area, lines(110))
            javax.swing.SwingUtilities.invokeLater {
                scroll.doLayout()
                retained.set(middle == scroll.verticalScrollBar.value)
            }
        }
        PlatformTestUtil.waitWithEventsDispatching("manual scroll position was not retained", retained::get, 5)

        val followed = AtomicBoolean()
        runInEdtAndWait {
            val bar = scroll.verticalScrollBar
            bar.value = bar.maximum
            updateOutput(scroll, area, lines(120))
            javax.swing.SwingUtilities.invokeLater {
                scroll.doLayout()
                followed.set(bar.maximum - bar.visibleAmount == bar.value)
            }
        }
        PlatformTestUtil.waitWithEventsDispatching("log did not follow its end", followed::get, 5)
        assertTrue(followed.get())
    }

    fun `test empty terminal preview is rendered unavailable`() {
        val platform = FakePlatform()
        val client = HttpClient.newHttpClient()
        val scope = CoroutineScope(SupervisorJob())
        val stored = arrayOfNulls<String>(1)
        var console: NativeGenerationConsole? = null
        try {
            platform.generationStatus = "SUCCESS"
            val sessions = SessionService(client, { stored[0] }, { stored[0] = it })
            sessions.login(platform.origin, "ada@example.com", "secret")
            val run = GenerationRun(GenerationApi(PlatformHttp(client, sessions, "binding")), "p1", "generation-1")
            val handler = ServerGenerationProcessHandler({}, {})
            runInEdtAndWait {
                handler.startNotify()
                console = NativeGenerationConsole(project, "Payments", scope, handler)
                assertTrue(checkNotNull(console).attach(run))
            }

            val previewReady = AtomicBoolean()
            PlatformTestUtil.waitWithEventsDispatching("empty preview was not rendered unavailable", {
                runInEdtAndWait {
                    previewReady.set((tabbedPane(checkNotNull(console))?.indexOfTab("Preview sample") ?: -1) >= 0)
                }
                previewReady.get()
            }, 5)
            runInEdtAndWait {
                val preview = checkNotNull(tabbedPane(checkNotNull(console)))
                val previewComponent = preview.getComponentAt(preview.indexOfTab("Preview sample"))
                assertTrue(textArea(previewComponent)?.text == "Preview unavailable.")
                assertTrue(preview.indexOfTab("Artifacts") < 0)
            }
        } finally {
            console?.let { runInEdtAndWait(it::dispose) }
            scope.cancel()
            client.shutdownNow()
            platform.close()
        }
    }

    fun `test history failure replaces the loading message in both panes`() {
        val platform = FakePlatform()
        val scope = CoroutineScope(SupervisorJob())
        val service = DatamimicPlatform.getInstance()
        var view: GenerationTasksView? = null
        try {
            PlatformTestUtil.waitWithEventsDispatching("platform state stayed unknown", { service.state.value != AuthState.Unknown }, 5)
            platform.generationHistoryStatus = 422
            platform.generationHistoryResponse = """{
                "detail":"Request validation failed",
                "code":"VALIDATION_ERROR",
                "errors":[{"loc":["body","filters","future_filter"],"msg":"Extra inputs are not permitted"}]
            }"""
            service.signIn(LoginInput(platform.origin, "ada@example.com", "secret", false))
            runInEdtAndWait {
                view = GenerationTasksView(project, scope).also {
                    it.show(FolderIdentity(platform.origin, "p1", "Payments"))
                }
            }

            val ready = AtomicBoolean()
            PlatformTestUtil.waitWithEventsDispatching("history error was not shown", {
                runInEdtAndWait {
                    val messages = labels(checkNotNull(view)).map(JLabel::getText)
                    ready.set(messages.count { it.startsWith("Tasks unavailable: Request validation failed") } == 2)
                }
                ready.get()
            }, 5)

            runInEdtAndWait {
                val messages = labels(checkNotNull(view)).map(JLabel::getText)
                assertEquals(2, messages.count { it.contains("filters.future_filter: Extra inputs are not permitted") })
                assertTrue(messages.none { it == "Loading tasks…" })
            }
        } finally {
            view?.let { runInEdtAndWait(it::dispose) }
            runCatching(service::signOut)
            scope.cancel()
            platform.close()
        }
    }

    private fun lines(count: Int) = (1..count).joinToString("\n") { "line $it" }

    private fun tabbedPane(component: Component): JBTabbedPane? = when (component) {
        is JBTabbedPane -> component
        is Container -> component.components.firstNotNullOfOrNull(::tabbedPane)
        else -> null
    }

    private fun textArea(component: Component): JBTextArea? = when (component) {
        is JBTextArea -> component
        is Container -> component.components.firstNotNullOfOrNull(::textArea)
        else -> null
    }

    private fun labels(component: Component): List<JLabel> = when (component) {
        is JLabel -> listOf(component)
        is Container -> component.components.flatMap(::labels)
        else -> emptyList()
    }
}
