// DATAMIMIC for JetBrains IDEs
// Copyright (c) 2026 Rapiddweller Asia Co., Ltd.
// SPDX-License-Identifier: MIT

package com.rapiddweller.datamimic.ide

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.WelcomeScreen
import com.intellij.openapi.wm.WelcomeScreenTab
import com.intellij.openapi.wm.WelcomeTabFactory
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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JTree
import javax.swing.SwingConstants
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
        val panel = ProjectsPanel(project, project.getService(ToolWindowScope::class.java).scope)
        toolWindow.contentManager.addContent(ContentFactory.getInstance().createContent(panel, "Projects", false))
    }
}

/** The DATAMIMIC entry of the Welcome screen, to sign in and open a platform project before any project is open. */
class DatamimicWelcomeTabFactory : WelcomeTabFactory {
    override fun createWelcomeTabs(welcomeScreen: WelcomeScreen, parentDisposable: Disposable): List<WelcomeScreenTab> = listOf(object : WelcomeScreenTab {
        private val component by lazy {
            val platform = DatamimicPlatform.getInstance()
            val scope = CoroutineScope(SupervisorJob(platform.scope.coroutineContext.job))
            Disposer.register(parentDisposable) { scope.cancel() }
            ProjectsPanel(null, scope)
        }
        private val key = JLabel("DATAMIMIC", IconLoader.getIcon("/icons/datamimic.svg", DatamimicWelcomeTabFactory::class.java), SwingConstants.LEADING)

        override fun getKeyComponent(parentComponent: JComponent): JComponent = key

        override fun getAssociatedComponent(): JComponent = component
    })
}

/** Sign-in and the platform's projects; [project] is null on the Welcome screen. */
internal class ProjectsPanel(private val project: Project?, private val scope: CoroutineScope) : SimpleToolWindowPanel(true, true) {
    private val platform = DatamimicPlatform.getInstance()
    private val active = project?.activeProject()
    internal val projectActionsVisible = project != null
    private val root = DefaultMutableTreeNode()
    private val model = DefaultTreeModel(root)
    private val tree = Tree(model).apply {
        isRootVisible = false
        showsRootHandles = false
        cellRenderer = NodeRenderer { active?.identity?.projectId }
    }
    private val operations = PlatformOperations(project, scope, active, ::selectedNode)
    internal val toolbarActions = DefaultActionGroup(
        action("Sign In…", AllIcons.General.User, { it is AuthState.SignedOut }, ::signIn),
        action("Refresh", AllIcons.Actions.Refresh, ::isSignedIn) { loadProjects() },
    )
    internal val contextActions = DefaultActionGroup(operations.openProject)
    private var loadJob: Job? = null

    init {
        // WHY: generating and agent configuration act on a project window, which the Welcome screen does not have.
        if (projectActionsVisible) {
            toolbarActions.addAll(Separator.getInstance(), operations.generate)
            contextActions.addAll(operations.generate, operations.copyAiAssistantConfiguration)
        }
        toolbarActions.addAll(Separator.getInstance(), action("Sign Out", AllIcons.Actions.Exit, ::isSignedIn) { operations.signOut() })
        toolbar = ActionManager.getInstance().createActionToolbar("DatamimicProjects", toolbarActions, true)
            .also { it.targetComponent = this }
            .component
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
        scope.launch(Dispatchers.EDT) { signInInteractively(project) }
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
