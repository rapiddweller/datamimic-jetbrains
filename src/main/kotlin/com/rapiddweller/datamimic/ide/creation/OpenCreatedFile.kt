// DATAMIMIC for JetBrains IDEs
// Copyright (c) 2026 Rapiddweller Asia Co., Ltd.
// SPDX-License-Identifier: MIT

package com.rapiddweller.datamimic.ide.creation

import com.intellij.openapi.application.EDT
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path

/** Opens the new local [file] in an editor. @return false when the IDE does not find the file. */
internal suspend fun openCreatedFile(project: Project, file: Path): Boolean {
    // WHY: the sync wrote the file outside the VFS, and a synchronous refresh must not block the UI thread.
    val virtualFile = withContext(Dispatchers.IO) { LocalFileSystem.getInstance().refreshAndFindFileByNioFile(file) } ?: return false
    withContext(Dispatchers.EDT) { OpenFileDescriptor(project, virtualFile).navigate(true) }
    return true
}
