// DATAMIMIC for JetBrains IDEs
// Copyright (c) 2026 Rapiddweller Asia Co., Ltd.
// SPDX-License-Identifier: MIT

package com.rapiddweller.datamimic.ide

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.rapiddweller.datamimic.core.PlatformOrigin
import javax.swing.JComponent

/** What the last sign-in left behind; the password only when the user chose to remember it. */
class SavedLogin(val platformUrl: String?, val email: String?, val password: String?, val rememberPassword: Boolean)

class LoginInput(val origin: PlatformOrigin, val email: String, val password: String, val rememberPassword: Boolean)

/** Asks for the platform URL and credentials, prefilled with the last sign-in. */
class LoginDialog(project: Project?, saved: SavedLogin) : DialogWrapper(project) {
    private val urlField = JBTextField(saved.platformUrl ?: "https://")
    private val emailField = JBTextField(saved.email.orEmpty())
    private val passwordField = JBPasswordField().apply { text = saved.password.orEmpty() }
    private val rememberPassword = JBCheckBox("Remember password", saved.rememberPassword)

    init {
        title = "Sign In to DATAMIMIC"
        setOKButtonText("Sign In")
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row("Platform URL:") {
            cell(urlField).align(AlignX.FILL).comment("The public URL you open the platform with, for example https://platform.datamimic.io")
        }
        row("Email:") { cell(emailField).align(AlignX.FILL) }
        row("Password:") { cell(passwordField).align(AlignX.FILL) }
        row("") { cell(rememberPassword).comment("Stored in the operating system's credential store.") }
    }

    override fun getPreferredFocusedComponent(): JComponent = when {
        urlField.text.length <= "https://".length -> urlField
        emailField.text.isBlank() -> emailField
        else -> passwordField
    }

    override fun doValidate(): ValidationInfo? {
        runCatching { PlatformOrigin.parse(urlField.text) }.onFailure { return ValidationInfo(it.message.orEmpty(), urlField) }
        if (emailField.text.isBlank()) return ValidationInfo("Enter your email address.", emailField)
        if (passwordField.password.isEmpty()) return ValidationInfo("Enter your password.", passwordField)
        return null
    }

    fun input(): LoginInput =
        LoginInput(PlatformOrigin.parse(urlField.text), emailField.text.trim(), String(passwordField.password), rememberPassword.isSelected)
}
