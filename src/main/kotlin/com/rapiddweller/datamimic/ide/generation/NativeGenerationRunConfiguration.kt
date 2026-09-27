// DATAMIMIC for JetBrains IDEs
// Copyright (c) 2026 Rapiddweller Asia Co., Ltd.
// SPDX-License-Identifier: MIT

package com.rapiddweller.datamimic.ide.generation

import com.intellij.execution.DefaultExecutionResult
import com.intellij.execution.ExecutionException
import com.intellij.execution.Executor
import com.intellij.execution.RunManager
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.configurations.RunConfigurationOptions
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.execution.configurations.SimpleConfigurationType
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.NopProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ProgramRunner
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.NotNullLazyValue
import com.intellij.openapi.options.SettingsEditor
import com.intellij.ui.components.JBLabel
import com.rapiddweller.datamimic.core.generation.GenerationRun
import com.rapiddweller.datamimic.core.generation.TaskType
import com.rapiddweller.datamimic.core.workspace.FolderIdentity
import com.rapiddweller.datamimic.ide.DatamimicPlatform
import com.rapiddweller.datamimic.ide.ToolWindowScope
import com.rapiddweller.datamimic.ide.activeProject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel

private const val CONFIGURATION_TYPE_ID = "DATAMIMICGeneration"

/** The local, per-DATAMIMIC-project entry in Run | Edit Configurations. */
class DatamimicRunConfigurationType : SimpleConfigurationType(
    CONFIGURATION_TYPE_ID,
    "DATAMIMIC Generation",
    "Generate data on DATAMIMIC Platform",
    NotNullLazyValue.createValue { com.intellij.icons.AllIcons.Actions.Execute },
) {
    override fun createTemplateConfiguration(project: Project): RunConfiguration =
        DatamimicRunConfiguration(project, this, "DATAMIMIC Generation")
}

/** Stored locally by [RunManager]; it deliberately contains only the stable Platform project identity. */
class DatamimicRunConfigurationOptions : RunConfigurationOptions() {
    var origin: String? by string("")
    var projectId: String? by string("")
    var taskType: TaskType by enum(TaskType.STANDARD)
}

class DatamimicRunConfiguration(
    project: Project,
    factory: ConfigurationFactory,
    name: String,
) : RunConfigurationBase<DatamimicRunConfigurationOptions>(project, factory, name) {
    private val options: DatamimicRunConfigurationOptions
        get() = super.getOptions() as DatamimicRunConfigurationOptions

    internal var origin: String
        get() = options.origin.orEmpty()
        set(value) {
            options.origin = value
        }

    internal var projectId: String
        get() = options.projectId.orEmpty()
        set(value) {
            options.projectId = value
        }

    internal var taskType: TaskType
        get() = options.taskType
        set(value) {
            options.taskType = value
        }

    override fun getOptionsClass(): Class<out RunConfigurationOptions> = DatamimicRunConfigurationOptions::class.java

    override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> = TaskTypeEditor()

    override fun hideDisabledExecutorButtons() = true

    override fun checkConfiguration() {
        generationTargetError(project, identity())?.let { throw RuntimeConfigurationError(it) }
    }

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState {
        if (executor.id != DefaultRunExecutor.EXECUTOR_ID) throw ExecutionException("DATAMIMIC generation supports Run only.")
        val identity = identity()
        generationTargetError(project, identity)?.let { throw ExecutionException(it) }
        return GenerationState(project, identity, taskType)
    }

    private fun identity() = FolderIdentity(com.rapiddweller.datamimic.core.PlatformOrigin(origin), projectId, name)
}

private class TaskTypeEditor : SettingsEditor<DatamimicRunConfiguration>() {
    private val taskType = JComboBox(TaskType.values()).apply {
        renderer = object : javax.swing.DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: javax.swing.JList<*>?,
                value: Any?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean,
            ) = super.getListCellRendererComponent(list, (value as? TaskType)?.label ?: value, index, isSelected, cellHasFocus)
        }
    }
    private val panel = JPanel(BorderLayout(8, 0)).apply {
        add(JBLabel("Task type:"), BorderLayout.WEST)
        add(taskType, BorderLayout.CENTER)
    }

    override fun resetEditorFrom(settings: DatamimicRunConfiguration) {
        taskType.selectedItem = settings.taskType
    }

    override fun applyEditorTo(settings: DatamimicRunConfiguration) {
        settings.taskType = taskType.selectedItem as TaskType
    }

    override fun createEditor(): JComponent = panel
}

private class GenerationState(
    private val project: Project,
    private val identity: FolderIdentity,
    private val taskType: TaskType,
) : RunProfileState {
    override fun execute(executor: Executor, runner: ProgramRunner<*>): DefaultExecutionResult {
        lateinit var console: NativeGenerationConsole
        val scope = project.getService(ToolWindowScope::class.java).scope
        val handler = ServerGenerationProcessHandler({ console.detach() }, { console.stopFailed(it) })
        console = NativeGenerationConsole(project, identity.projectName, scope, handler)
        startGeneration(
            project = project,
            identity = identity,
            taskType = taskType,
            scope = scope,
            shouldStart = handler::accepting,
            acceptRun = { run -> handler.started(run) && console.attach(run) },
            rejected = handler::rejected,
        )
        return DefaultExecutionResult(console, handler)
    }
}

/** Stop is a Platform cancellation; detach and result-view close only stop local observation. */
internal class ServerGenerationProcessHandler(
    private val detachView: () -> Unit,
    private val stopFailed: (Throwable) -> Unit,
    private val cancel: (GenerationRun, (Throwable) -> Unit) -> Unit = { value, failed ->
        DatamimicPlatform.getInstance().scope.launch(Dispatchers.IO) {
            try {
                value.stop()
            } catch (e: Exception) {
                failed(e)
            }
        }
    },
) : NopProcessHandler() {
    private var stopped = false
    private var detached = false
    private var cancelRequested = false
    private var run: GenerationRun? = null

    fun accepting(): Boolean = synchronized(this) { !stopped && !detached }

    fun started(value: GenerationRun): Boolean {
        val cancel = synchronized(this) {
            run = value
            stopped && !detached
        }
        if (cancel) requestStop(value)
        return synchronized(this) { !detached }
    }

    override fun destroyProcessImpl() {
        val current = synchronized(this) {
            stopped = true
            run
        }
        current?.let(::requestStop)
    }

    override fun detachProcessImpl() {
        val notify = synchronized(this) {
            if (detached) false else {
                detached = true
                true
            }
        }
        if (!notify) return
        detachView()
        notifyProcessDetached()
    }

    /** Bypasses ProcessHandler's terminating state: closing the console always detaches local observation. */
    fun detachObservation() = detachProcessImpl()

    fun completed(status: com.rapiddweller.datamimic.core.generation.TaskStatus) {
        if (!isProcessTerminated && !detached) notifyProcessTerminated(if (status.succeeded) 0 else 1)
    }

    fun rejected() {
        if (!isProcessTerminated && !detached) notifyProcessTerminated(1)
    }

    fun requestStop() {
        synchronized(this) { run }?.let(::requestStop)
    }

    private fun requestStop(value: GenerationRun) {
        val first = synchronized(this) {
            if (cancelRequested || detached) false else {
                cancelRequested = true
                true
            }
        }
        if (!first) return
        cancel(value) { error ->
            synchronized(this) { cancelRequested = false }
            stopFailed(error)
        }
    }
}

/** Creates a selected local configuration only when this project has none yet. */
internal fun ensureNativeRunConfiguration(project: Project, identity: FolderIdentity) {
    val runManager = RunManager.getInstance(project)
    val type = ConfigurationTypeUtil.findConfigurationType(DatamimicRunConfigurationType::class.java)
    val matches = runManager.getConfigurationSettingsList(type).filter { settings ->
        (settings.configuration as? DatamimicRunConfiguration)?.let { it.origin == identity.origin.value && it.projectId == identity.projectId } == true
    }
    if (matches.isNotEmpty()) {
        if (runManager.selectedConfiguration == null) runManager.selectedConfiguration = matches.first()
        return
    }
    runManager.createConfiguration("DATAMIMIC: ${identity.projectName}", type).also {
        (it.configuration as DatamimicRunConfiguration).apply {
            origin = identity.origin.value
            projectId = identity.projectId
            taskType = TaskType.STANDARD
        }
        it.storeInLocalWorkspace()
        runManager.addConfiguration(it)
        if (runManager.selectedConfiguration == null) runManager.selectedConfiguration = it
    }
}
