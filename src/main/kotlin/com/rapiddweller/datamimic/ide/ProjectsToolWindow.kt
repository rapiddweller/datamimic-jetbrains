// DATAMIMIC for JetBrains IDEs
// Copyright (c) 2026 Rapiddweller Asia Co., Ltd.
// SPDX-License-Identifier: MIT

package com.rapiddweller.datamimic.ide

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.treeStructure.Tree
import com.rapiddweller.datamimic.core.PlatformProject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Icon
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

/** What a row of the DATAMIMIC project list stands for. */
sealed interface PlatformNode {
    data class ProjectNode(val project: PlatformProject) : PlatformNode

    data class Message(val text: String, val action: MessageAction? = null) : PlatformNode
}

enum class MessageAction { SIGN_IN, RETRY }

/** Swing tree node that only ever carries a [PlatformNode]. */
internal class PlatformTreeNode(val node: PlatformNode) : DefaultMutableTreeNode(node)

/** Lifecycle owner for the tool window's coroutines of one IDE project. */
@Service(Service.Level.PROJECT)
class ToolWindowScope(val scope: CoroutineScope)

class ProjectsToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        toolWindow.contentManager.addContent(ContentFactory.getInstance().createContent(ProjectsPanel(project), "Projects", false))
    }
}

internal class ProjectsPanel(private val project: Project) : SimpleToolWindowPanel(true, true) {
    private val platform = DatamimicPlatform.getInstance()
    private val scope = project.service<ToolWindowScope>().scope
    private val active = project.activeProject()
    private val root = DefaultMutableTreeNode()
    private val model = DefaultTreeModel(root)
    private val tree = Tree(model).apply {
        isRootVisible = false
        showsRootHandles = false
        cellRenderer = NodeRenderer { active.identity?.projectId }
    }
    private val operations = PlatformOperations(project, scope, active, ::selectedNode)
    private var loadJob: Job? = null

    init {
        val toolbarActions = DefaultActionGroup(
            action("Sign In…", AllIcons.General.User, { it is AuthState.SignedOut }, ::signIn),
            action("Refresh", AllIcons.Actions.Refresh, ::isSignedIn) { loadProjects() },
            Separator.getInstance(),
            operations.generate,
            Separator.getInstance(),
            action("Sign Out", AllIcons.Actions.Exit, ::isSignedIn) { operations.signOut() },
        )
        toolbar = ActionManager.getInstance().createActionToolbar("DatamimicProjects", toolbarActions, true)
            .also { it.targetComponent = this }
            .component
        val contextActions = DefaultActionGroup(operations.openProject, operations.generate, operations.copyAiAssistantConfiguration)
        PopupHandler.installPopupMenu(tree, contextActions, "DatamimicProjectsPopup")
        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                if (event.clickCount != 2) return
                when (val node = selectedNode()) {
                    is PlatformNode.ProjectNode -> operations.openProject(node.project)
                    is PlatformNode.Message -> node.action?.let(::runMessageAction)
                    null -> Unit
                }
            }
        })
        setContent(ScrollPaneFactory.createScrollPane(tree, true))
        scope.launch(Dispatchers.EDT) { platform.state.collect(::render) }
    }

    private fun render(state: AuthState) {
        when (state) {
            AuthState.Unknown -> showMessage(PlatformNode.Message("Checking sign-in…"))
            is AuthState.SignedOut -> {
                loadJob?.cancel()
                showMessage(PlatformNode.Message(state.notice ?: "Sign in to browse your DATAMIMIC projects.", MessageAction.SIGN_IN))
                if (state.notice != null) showMessageRow(PlatformNode.Message("Sign in…", MessageAction.SIGN_IN))
            }
            is AuthState.SignedIn -> loadProjects()
        }
    }

    private fun runMessageAction(action: MessageAction) = when (action) {
        MessageAction.SIGN_IN -> signIn()
        MessageAction.RETRY -> loadProjects()
    }

    private fun signIn() {
        scope.launch(Dispatchers.EDT) {
            val saved = withContext(Dispatchers.IO) { platform.savedLogin() }
            val dialog = LoginDialog(project, saved)
            if (!dialog.showAndGet()) return@launch
            val input = dialog.input()
            showMessage(PlatformNode.Message("Signing in…"))
            try {
                withContext(Dispatchers.IO) { platform.signIn(input) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                render(AuthState.SignedOut(e.message ?: "Sign-in failed."))
            }
        }
    }

    private fun loadProjects() {
        loadJob?.cancel()
        loadJob = scope.launch(Dispatchers.EDT) {
            showMessage(PlatformNode.Message("Loading projects…"))
            try {
                val projects = withContext(Dispatchers.IO) { platform.projects.list() }
                root.removeAllChildren()
                if (projects.isEmpty()) root.add(PlatformTreeNode(PlatformNode.Message("No projects yet.")))
                projects.forEach { root.add(PlatformTreeNode(PlatformNode.ProjectNode(it))) }
                model.reload()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showMessage(PlatformNode.Message(e.message ?: "Could not load projects.", MessageAction.RETRY))
            }
        }
    }

    private fun showMessage(message: PlatformNode.Message) {
        root.removeAllChildren()
        root.add(PlatformTreeNode(message))
        model.reload()
    }

    private fun showMessageRow(message: PlatformNode.Message) {
        root.add(PlatformTreeNode(message))
        model.reload()
    }

    private fun selectedNode(): PlatformNode? = when (val selected = tree.lastSelectedPathComponent) {
        is PlatformTreeNode -> selected.node
        else -> null
    }

    private fun isSignedIn(state: AuthState) = state is AuthState.SignedIn

    private fun action(text: String, icon: Icon, enabledWhen: (AuthState) -> Boolean, perform: () -> Unit) =
        object : DumbAwareAction(text, null, icon) {
            override fun getActionUpdateThread() = ActionUpdateThread.BGT

            override fun update(e: AnActionEvent) {
                e.presentation.isEnabledAndVisible = enabledWhen(platform.state.value)
            }

            override fun actionPerformed(e: AnActionEvent) = perform()
        }
}

private class NodeRenderer(private val activeProjectId: () -> String?) : ColoredTreeCellRenderer() {
    override fun customizeCellRenderer(
        tree: JTree,
        value: Any?,
        selected: Boolean,
        expanded: Boolean,
        leaf: Boolean,
        row: Int,
        hasFocus: Boolean,
    ) {
        if (value !is PlatformTreeNode) return
        when (val node = value.node) {
            is PlatformNode.ProjectNode -> {
                icon = AllIcons.Nodes.Module
                if (node.project.id == activeProjectId()) {
                    append(node.project.name, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    append("  this window", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                } else {
                    append(node.project.name)
                }
            }
            is PlatformNode.Message -> append(node.text, if (node.action != null) SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES else SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }
    }
}
