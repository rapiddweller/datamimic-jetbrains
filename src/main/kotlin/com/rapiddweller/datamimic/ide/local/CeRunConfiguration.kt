// DATAMIMIC for JetBrains IDEs
// Copyright (c) 2026 Rapiddweller Asia Co., Ltd.
// SPDX-License-Identifier: MIT

package com.rapiddweller.datamimic.ide.local

import com.intellij.execution.ExecutionException
import com.intellij.execution.Executor
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.LazyRunConfigurationProducer
import com.intellij.execution.configurations.CommandLineState
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationTypeBase
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.LocatableConfigurationBase
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunConfigurationOptions
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.components.service
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.util.Ref
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.PsiElement
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.swing.JComponent
import kotlin.io.path.isRegularFile

internal object DatamimicIcons {
    val Logo = IconLoader.getIcon("/icons/datamimic.svg", DatamimicIcons::class.java)
}

class CeRunConfigurationType : ConfigurationTypeBase(ID, "DATAMIMIC", "Run a DATAMIMIC descriptor with DATAMIMIC CE", DatamimicIcons.Logo) {
    val factory = CeRunConfigurationFactory(this)

    init {
        addFactory(factory)
    }

    companion object {
        const val ID = "DatamimicCeRun"
    }
}

class CeRunConfigurationFactory(type: CeRunConfigurationType) : ConfigurationFactory(type) {
    override fun getId(): String = CeRunConfigurationType.ID

    override fun createTemplateConfiguration(project: Project): RunConfiguration = CeRunConfiguration(project, this, "DATAMIMIC")

    override fun getOptionsClass(): Class<CeRunOptions> = CeRunOptions::class.java
}

class CeRunOptions : RunConfigurationOptions() {
    private val descriptor = string("").provideDelegate(this, "descriptorPath")

    var descriptorPath: String
        get() = descriptor.getValue(this).orEmpty()
        set(value) = descriptor.setValue(this, value)
}

/** Runs `datamimic run <descriptor> --task-id <id>`; each run writes to its own `output/<id>` folder. */
class CeRunConfiguration(project: Project, factory: ConfigurationFactory, name: String) :
    LocatableConfigurationBase<CeRunOptions>(project, factory, name) {

    /** The persisted options; the platform creates them from [CeRunConfigurationFactory.getOptionsClass]. */
    private val runOptions: CeRunOptions get() = checkNotNull(state) { "Run configuration options are not initialized." }

    var descriptorPath: String
        get() = runOptions.descriptorPath
        set(value) {
            runOptions.descriptorPath = value
        }

    override fun getConfigurationEditor(): SettingsEditor<CeRunConfiguration> = CeRunSettingsEditor(project)

    override fun checkConfiguration() {
        if (descriptorPath.isBlank() || !Path.of(descriptorPath).isRegularFile()) throw RuntimeConfigurationError("Choose an existing DATAMIMIC descriptor.")
        if (service<LocalCeSettings>().cli(project) == null) throw RuntimeConfigurationError(CE_MISSING)
    }

    override fun getState(executor: Executor, environment: ExecutionEnvironment) = object : CommandLineState(environment) {
        override fun startProcess(): ProcessHandler {
            val cli = service<LocalCeSettings>().cli(project) ?: throw ExecutionException(CE_MISSING)
            val descriptor = Path.of(descriptorPath)
            val taskId = "ide-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
            val outputDir = descriptor.parent.resolve("output").resolve(taskId)
            val commandLine = GeneralCommandLine(cli.runArguments(descriptor, taskId))
                .withWorkDirectory(descriptor.parent.toFile())
                .withCharset(StandardCharsets.UTF_8)
            val handler = KillableColoredProcessHandler(commandLine)
            ProcessTerminatedListener.attach(handler)
            handler.addProcessListener(object : ProcessListener {
                override fun startNotified(event: ProcessEvent) {
                    handler.notifyTextAvailable("Output folder: $outputDir\n", ProcessOutputTypes.SYSTEM)
                }

                override fun processTerminated(event: ProcessEvent) {
                    VfsUtil.markDirtyAndRefresh(true, true, true, outputDir.toFile())
                }
            })
            return handler
        }
    }

    private companion object {
        const val CE_MISSING = "DATAMIMIC CE was not found. Install it with pip install \"datamimic-ce[mcp]\" " +
            "or set its path in Settings | Tools | DATAMIMIC."
    }
}

private class CeRunSettingsEditor(project: Project) : SettingsEditor<CeRunConfiguration>() {
    private val descriptorField = TextFieldWithBrowseButton().apply {
        addBrowseFolderListener(project, FileChooserDescriptorFactory.createSingleFileDescriptor("xml").withTitle("DATAMIMIC Descriptor"))
    }

    override fun resetEditorFrom(configuration: CeRunConfiguration) {
        descriptorField.text = configuration.descriptorPath
    }

    override fun applyEditorTo(configuration: CeRunConfiguration) {
        configuration.descriptorPath = descriptorField.text.trim()
    }

    override fun createEditor(): JComponent = panel {
        row("Descriptor:") { cell(descriptorField).align(AlignX.FILL) }
    }
}

/** Offers "Run 'datamimic.xml'" for local descriptors in editors and the Project view. */
class CeRunConfigurationProducer : LazyRunConfigurationProducer<CeRunConfiguration>() {
    override fun getConfigurationFactory(): ConfigurationFactory =
        ConfigurationTypeUtil.findConfigurationType(CeRunConfigurationType::class.java).factory

    override fun setupConfigurationFromContext(
        configuration: CeRunConfiguration,
        context: ConfigurationContext,
        sourceElement: Ref<PsiElement>,
    ): Boolean {
        val file = context.psiLocation?.containingFile ?: return false
        if (!file.isLocalDescriptor()) return false
        configuration.descriptorPath = file.virtualFile.toNioPath().toString()
        configuration.name = file.name
        return true
    }

    override fun isConfigurationFromContext(configuration: CeRunConfiguration, context: ConfigurationContext): Boolean {
        val file = context.psiLocation?.containingFile ?: return false
        return file.isLocalDescriptor() && configuration.descriptorPath == file.virtualFile.toNioPath().toString()
    }
}
