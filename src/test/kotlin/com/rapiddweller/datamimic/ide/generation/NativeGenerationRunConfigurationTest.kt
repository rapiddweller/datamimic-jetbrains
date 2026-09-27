// DATAMIMIC for JetBrains IDEs
// Copyright (c) 2026 Rapiddweller Asia Co., Ltd.
// SPDX-License-Identifier: MIT

package com.rapiddweller.datamimic.ide.generation

import com.intellij.execution.RunManager
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.openapi.util.JDOMUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.rapiddweller.datamimic.core.PlatformOrigin
import com.rapiddweller.datamimic.core.FakePlatform
import com.rapiddweller.datamimic.core.PlatformHttp
import com.rapiddweller.datamimic.core.SessionService
import com.rapiddweller.datamimic.core.generation.GenerationApi
import com.rapiddweller.datamimic.core.generation.GenerationRun
import com.rapiddweller.datamimic.core.generation.TaskType
import com.rapiddweller.datamimic.core.generation.TaskStatus
import com.rapiddweller.datamimic.core.workspace.FolderIdentity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.jdom.Element
import java.net.http.HttpClient

class NativeGenerationRunConfigurationTest : BasePlatformTestCase() {
    private var fakePlatform: FakePlatform? = null

    override fun tearDown() {
        try {
            fakePlatform?.close()
        } finally {
            super.tearDown()
        }
    }

    fun `test explicit open creates exactly one local configuration with the standard task type`() {
        val identity = FolderIdentity(PlatformOrigin.parse("https://platform.example"), "new", "Payments")
        RunManager.getInstance(project).selectedConfiguration = null

        ensureNativeRunConfiguration(project, identity)
        ensureNativeRunConfiguration(project, identity)

        val type = ConfigurationTypeUtil.findConfigurationType(DatamimicRunConfigurationType::class.java)
        val settings = RunManager.getInstance(project).getConfigurationSettingsList(type)
            .filter { (it.configuration as? DatamimicRunConfiguration)?.projectId == identity.projectId }
        assertEquals(1, settings.size)
        assertTrue(settings.single().isStoredInLocalWorkspace)
        assertSame(settings.single(), RunManager.getInstance(project).selectedConfiguration)
        assertEquals(TaskType.STANDARD, (settings.single().configuration as DatamimicRunConfiguration).taskType)
    }

    fun `test existing matching configurations keep their storage task type and selection`() {
        val identity = FolderIdentity(PlatformOrigin.parse("https://platform.example"), "existing", "Payments")
        val type = ConfigurationTypeUtil.findConfigurationType(DatamimicRunConfigurationType::class.java)
        val manager = RunManager.getInstance(project)
        val first = configuration(manager, type, identity, TaskType.INFINITE, "Infinite").also {
            manager.addConfiguration(it)
            it.storeInDotIdeaFolder()
        }
        val second = configuration(manager, type, identity, TaskType.TIMED_5_MIN, "Timed").also(manager::addConfiguration)
        manager.selectedConfiguration = second

        ensureNativeRunConfiguration(project, identity)

        val configurations = manager.getConfigurationSettingsList(type).filter {
            (it.configuration as? DatamimicRunConfiguration)?.projectId == identity.projectId
        }
        assertEquals(listOf(TaskType.INFINITE, TaskType.TIMED_5_MIN), configurations.map { (it.configuration as DatamimicRunConfiguration).taskType })
        assertFalse(first.isStoredInLocalWorkspace)
        assertSame(second, manager.selectedConfiguration)
    }

    fun `test creating configuration preserves an unrelated selection`() {
        val type = ConfigurationTypeUtil.findConfigurationType(DatamimicRunConfigurationType::class.java)
        val manager = RunManager.getInstance(project)
        val selected = configuration(
            manager,
            type,
            FolderIdentity(PlatformOrigin.parse("https://platform.example"), "other", "Other"),
            TaskType.STANDARD,
        ).also(manager::addConfiguration)
        manager.selectedConfiguration = selected

        ensureNativeRunConfiguration(
            project,
            FolderIdentity(PlatformOrigin.parse("https://platform.example"), "new", "Payments"),
        )

        assertSame(selected, manager.selectedConfiguration)
    }

    fun `test configuration persists only identity and task type`() {
        val type = ConfigurationTypeUtil.findConfigurationType(DatamimicRunConfigurationType::class.java)
        val source = type.createTemplateConfiguration(project) as DatamimicRunConfiguration
        source.origin = "https://platform.example"
        source.projectId = "p1"
        source.taskType = TaskType.INFINITE
        val xml = Element("configuration")
        source.writeExternal(xml)

        val restored = type.createTemplateConfiguration(project) as DatamimicRunConfiguration
        restored.readExternal(xml)

        assertEquals("https://platform.example", restored.origin)
        assertEquals("p1", restored.projectId)
        assertEquals(TaskType.INFINITE, restored.taskType)
        assertFalse(JDOMUtil.writeElement(xml).contains("token", ignoreCase = true))
    }

    fun `test target validation rejects stale identity and origin`() {
        val expected = FolderIdentity(PlatformOrigin.parse("https://one.example"), "p1", "Payments")
        assertEquals(
            "This run configuration belongs to another DATAMIMIC project.",
            generationTargetError(expected, expected.copy(projectId = "p2"), expected.origin, null),
        )
        assertEquals(
            "Sign in to https://one.example before running this configuration.",
            generationTargetError(expected, expected, PlatformOrigin.parse("https://two.example"), null),
        )
        assertEquals(
            "DATAMIMIC is not connected to this project.",
            generationTargetError(expected, expected, expected.origin, null),
        )
    }

    fun `test handler uses terminal status and sends one cancellation`() {
        val handler = ServerGenerationProcessHandler({}, {}) { _, _ -> cancelled++ }
        handler.startNotify()
        handler.started(generationRun())
        handler.destroyProcess()
        handler.destroyProcess()
        assertEquals(1, cancelled)
        assertFalse(handler.isProcessTerminated)
        handler.completed(TaskStatus.CANCELLED)
        assertEquals(1, handler.exitCode)

        val success = ServerGenerationProcessHandler({}, {}) { _, _ -> }
        success.startNotify()
        success.started(generationRun())
        success.completed(TaskStatus.SUCCESS_WITH_WARNING)
        assertEquals(0, success.exitCode)
    }

    fun `test failed cancellation can retry while duplicate successful stops are deduplicated`() {
        var requests = 0
        var failures = 0
        val handler = ServerGenerationProcessHandler({}, { failures++ }) { _, failed ->
            requests++
            if (requests == 1) failed(IllegalStateException("temporary platform error"))
        }
        handler.startNotify()
        handler.started(generationRun())

        handler.requestStop()
        assertEquals(1, requests)
        assertEquals(1, failures)
        handler.requestStop()
        handler.requestStop()
        assertEquals(2, requests)
    }

    fun `test detach and console close never cancel`() {
        var detached = 0
        val handler = ServerGenerationProcessHandler({ detached++ }, {}) { _, _ -> cancelled++ }
        handler.startNotify()
        handler.started(generationRun())
        handler.detachProcess()
        assertEquals(1, detached)
        assertEquals(0, cancelled)

        val scope = CoroutineScope(SupervisorJob())
        lateinit var console: NativeGenerationConsole
        val consoleHandler = ServerGenerationProcessHandler({ console.detach() }, {}) { _, _ -> cancelled++ }
        console = NativeGenerationConsole(project, "Payments", scope, consoleHandler)
        consoleHandler.startNotify()
        consoleHandler.started(generationRun())
        console.dispose()
        assertEquals(0, cancelled)
        scope.cancel()
    }

    fun `test console close detaches after failed native stop`() {
        var cancels = 0
        lateinit var console: NativeGenerationConsole
        val scope = CoroutineScope(SupervisorJob())
        val handler = ServerGenerationProcessHandler({ console.detach() }, {}) { _, failed ->
            cancels++
            failed(IllegalStateException("platform unavailable"))
        }
        console = NativeGenerationConsole(project, "Payments", scope, handler)
        handler.startNotify()
        handler.started(generationRun())
        handler.destroyProcess()
        console.dispose()

        assertEquals(1, cancels)
        assertTrue(handler.isProcessTerminated)
        scope.cancel()
    }

    private var cancelled = 0

    private fun configuration(
        manager: RunManager,
        type: DatamimicRunConfigurationType,
        identity: FolderIdentity,
        taskType: TaskType,
        suffix: String = "",
    ) = manager.createConfiguration("DATAMIMIC: ${identity.projectName} $suffix".trim(), type).apply {
        (configuration as DatamimicRunConfiguration).apply {
            origin = identity.origin.value
            projectId = identity.projectId
            this.taskType = taskType
        }
    }

    private fun generationRun(): GenerationRun {
        val platform = fakePlatform ?: FakePlatform().also { fakePlatform = it }
        val stored = arrayOfNulls<String>(1)
        val sessions = SessionService(HttpClient.newHttpClient(), { stored[0] }, { stored[0] = it })
        sessions.login(platform.origin, "ada@example.com", "secret")
        return GenerationRun(GenerationApi(PlatformHttp(HttpClient.newHttpClient(), sessions, "binding")), "p1", "task")
    }
}
