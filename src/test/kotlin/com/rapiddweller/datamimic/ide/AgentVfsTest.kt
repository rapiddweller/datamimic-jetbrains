// DATAMIMIC for JetBrains IDEs
// Copyright (c) 2026 Rapiddweller Asia Co., Ltd.
// SPDX-License-Identifier: MIT

package com.rapiddweller.datamimic.ide

import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.rapiddweller.datamimic.core.mcp.JunieAgent
import java.nio.file.Files
import java.nio.file.Path

class AgentVfsTest : BasePlatformTestCase() {
    fun `test external Junie configuration write is published to the IDE VFS`() {
        val root = Path.of(checkNotNull(project.basePath))
        val config = root.resolve(JunieAgent.CONFIG_PATH)
        val directory = Files.createDirectories(config.parent)
        val virtualDirectory = checkNotNull(VirtualFileManager.getInstance().refreshAndFindFileByNioPath(directory))
        virtualDirectory.children
        Files.writeString(config, "{}")

        refreshJunieFiles(root)

        assertNotNull(virtualDirectory.findChild(config.fileName.toString()))
    }
}
