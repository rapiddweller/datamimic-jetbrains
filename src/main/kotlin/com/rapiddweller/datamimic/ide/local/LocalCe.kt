// DATAMIMIC for JetBrains IDEs
// Copyright (c) 2026 Rapiddweller Asia Co., Ltd.
// SPDX-License-Identifier: MIT

package com.rapiddweller.datamimic.ide.local

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.ide.util.PropertiesComponent
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileDocumentManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.xml.XmlFile
import com.intellij.util.EnvironmentUtil
import com.intellij.testFramework.LightVirtualFile
import com.intellij.json.JsonFileType
import com.jetbrains.jsonSchema.JsonSchemaVfsListener
import com.jetbrains.jsonSchema.extension.JsonSchemaFileProvider
import com.jetbrains.jsonSchema.extension.JsonSchemaProviderFactory
import com.jetbrains.jsonSchema.extension.SchemaType
import com.rapiddweller.datamimic.core.ce.CeCli
import com.rapiddweller.datamimic.core.ce.LintReport
import com.rapiddweller.datamimic.core.ce.LintSeverity
import com.rapiddweller.datamimic.core.workspace.ProjectFolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

/** Root element of every DATAMIMIC descriptor. */
internal const val DESCRIPTOR_ROOT_TAG = "setup"

/** File name suffix of CE authoring models (`model.dm.json`). */
internal const val AUTHORING_MODEL_SUFFIX = ".dm.json"

/** Where to find DATAMIMIC CE for local descriptors; the configured path is optional. */
@Service(Service.Level.APP)
class LocalCeSettings {
    var configuredCliPath: String?
        get() = PropertiesComponent.getInstance().getValue(CLI_PATH_KEY)
        set(value) = PropertiesComponent.getInstance().setValue(CLI_PATH_KEY, value?.trim()?.ifEmpty { null })

    /** The CE command line for [project]: configured path, then the project's virtual environment, then PATH. */
    // WHY: an IDE started from the Dock has launchd's PATH; EnvironmentUtil has the login shell's (Homebrew, pyenv).
    fun cli(project: Project?): CeCli? =
        CeCli.locate(configuredCliPath, project?.guessProjectDir()?.toNioPathOrNull(), EnvironmentUtil.getValue("PATH"))

    private companion object {
        const val CLI_PATH_KEY = "datamimic.ce.cliPath"
    }
}

internal fun VirtualFile.toNioPathOrNull(): Path? = if (isInLocalFileSystem) fileSystem.getNioPath(this) else null

/** A descriptor CE runs: a local `<setup>` file that is not the synced copy of a platform project file (EE vocabulary). */
internal fun PsiFile.isLocalDescriptor(): Boolean {
    if (this !is XmlFile || rootTag?.name != DESCRIPTOR_ROOT_TAG || virtualFile?.isInLocalFileSystem != true) return false
    return virtualFile.toNioPathOrNull()?.let(ProjectFolder::containing) == null
}

/** CE lint findings on local descriptors. CE reads the saved file, so findings refresh after each save. */
class CeLintAnnotator : ExternalAnnotator<CeLintAnnotator.Input, LintReport>() {
    class Input(val cli: CeCli, val descriptor: Path)

    override fun collectInformation(file: PsiFile, editor: Editor, hasErrors: Boolean): Input? {
        if (!file.isLocalDescriptor()) return null
        val virtualFile = file.virtualFile ?: return null
        val document = FileDocumentManager.getInstance().getDocument(virtualFile) ?: return null
        // WHY: line numbers from linting the saved file would point at the wrong lines of an edited buffer.
        if (FileDocumentManager.getInstance().isDocumentUnsaved(document)) return null
        val cli = service<LocalCeSettings>().cli(file.project) ?: return null
        return Input(cli, virtualFile.toNioPath())
    }

    override fun doAnnotate(input: Input): LintReport? = runCatching { input.cli.lint(input.descriptor) }.getOrNull()

    override fun apply(file: PsiFile, report: LintReport, holder: AnnotationHolder) {
        val document = file.viewProvider.document ?: return
        for (diagnostic in report.diagnostics) {
            val line = (diagnostic.line ?: 1) - 1
            if (line !in 0 until document.lineCount) continue
            holder.newAnnotation(severity(diagnostic.severity), "${diagnostic.message} [${diagnostic.rule}]")
                .range(lineRange(document, line))
                .tooltip(tooltip(diagnostic.message, diagnostic.rule, diagnostic.fixHint))
                .create()
        }
    }

    private fun lineRange(document: Document, line: Int): TextRange {
        val start = document.getLineStartOffset(line)
        val end = document.getLineEndOffset(line)
        val text = document.charsSequence.subSequence(start, end)
        val indent = text.indexOfFirst { !it.isWhitespace() }.takeIf { it >= 0 } ?: 0
        return TextRange(start + indent, maxOf(start + indent, start + text.trimEnd().length))
    }

    private fun tooltip(message: String, rule: String, fixHint: String?): String = buildString {
        append("<html><b>").append(StringUtil.escapeXmlEntities(rule)).append("</b> ").append(StringUtil.escapeXmlEntities(message))
        fixHint?.let { append("<br/>").append(StringUtil.escapeXmlEntities(it)) }
        append("</html>")
    }

    private fun severity(severity: LintSeverity): HighlightSeverity = when (severity) {
        LintSeverity.ERROR -> HighlightSeverity.ERROR
        LintSeverity.WARNING -> HighlightSeverity.WARNING
        LintSeverity.INFO, LintSeverity.HINT, LintSeverity.UNKNOWN -> HighlightSeverity.WEAK_WARNING
    }
}

/** Saving a descriptor does not change its PSI, so the annotator has to be asked to lint again. */
class CeLintOnSave : FileDocumentManagerListener {
    override fun afterDocumentSaved(document: Document) {
        for (project in ProjectManager.getInstance().openProjects) {
            val file = PsiDocumentManager.getInstance(project).getPsiFile(document) ?: continue
            if (file.isLocalDescriptor()) DaemonCodeAnalyzer.getInstance(project).restart(file, "DATAMIMIC descriptor saved")
        }
    }
}

/** Serves the authoring-model JSON Schema of the installed CE version to `*.dm.json` files. */
@Service(Service.Level.PROJECT)
class AuthoringSchema(private val project: Project, private val scope: CoroutineScope) {
    @Volatile
    private var schema: VirtualFile? = null
    private val loading = AtomicBoolean(false)

    fun file(): VirtualFile? {
        schema?.let { return it }
        if (loading.compareAndSet(false, true)) load()
        return null
    }

    private fun load() = scope.launch(Dispatchers.IO) {
        val cli = service<LocalCeSettings>().cli(project) ?: return@launch loading.set(false)
        val text = runCatching { cli.authoringSchema() }.getOrNull() ?: return@launch loading.set(false)
        schema = LightVirtualFile("datamimic-authoring-model.schema.json", JsonFileType.INSTANCE, text)
        withContext(Dispatchers.EDT) { project.messageBus.syncPublisher(JsonSchemaVfsListener.JSON_SCHEMA_CHANGED).run() }
    }
}

class AuthoringSchemaProviderFactory : JsonSchemaProviderFactory {
    override fun getProviders(project: Project): List<JsonSchemaFileProvider> = listOf(
        object : JsonSchemaFileProvider {
            override fun isAvailable(file: VirtualFile): Boolean = file.name.endsWith(AUTHORING_MODEL_SUFFIX)

            override fun getName(): String = "DATAMIMIC authoring model"

            override fun getSchemaFile(): VirtualFile? = project.service<AuthoringSchema>().file()

            override fun getSchemaType(): SchemaType = SchemaType.embeddedSchema
        },
    )
}
