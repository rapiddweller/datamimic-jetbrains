// DATAMIMIC for JetBrains IDEs
// Copyright (c) 2026 Rapiddweller Asia Co., Ltd.
// SPDX-License-Identifier: MIT

package com.rapiddweller.datamimic.ide

import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.rapiddweller.datamimic.core.mcp.JunieAgent
import com.rapiddweller.datamimic.core.mcp.writeSecretFile
import java.nio.file.Files
import java.nio.file.Path

class AgentVfsTest : BasePlatformTestCase() {
    fun `test external Junie configuration updates and deletion are published to the IDE VFS`() {
        val root = Path.of(checkNotNull(project.basePath))
        val config = root.resolve(JunieAgent.CONFIG_PATH)
        val directory = Files.createDirectories(config.parent)
        val virtualDirectory = checkNotNull(VirtualFileManager.getInstance().refreshAndFindFileByNioPath(directory))
        virtualDirectory.children
        Files.writeString(config, "A")

        refreshJunieFiles(root)

        val virtualConfig = checkNotNull(virtualDirectory.findChild(config.fileName.toString()))
        assertEquals("A", virtualConfig.inputStream.bufferedReader().readText())

        writeSecretFile(config, "B")
        refreshJunieFiles(root)
        assertEquals("B", virtualConfig.inputStream.bufferedReader().readText())

        Files.delete(config)
        refreshJunieFiles(root)
        assertNull(virtualDirectory.findChild(config.fileName.toString()))
    }
}
