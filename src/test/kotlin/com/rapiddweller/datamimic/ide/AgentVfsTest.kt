// DATAMIMIC for JetBrains IDEs
// Copyright (c) 2026 Rapiddweller Asia Co., Ltd.
// SPDX-License-Identifier: MIT

package com.rapiddweller.datamimic.ide

import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.rapiddweller.datamimic.core.mcp.JunieAgent
import com.rapiddweller.datamimic.core.mcp.AiAssistantAgent
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

    fun `test external Junie guidance updates and deletion are published to the IDE VFS`() {
        val root = Path.of(checkNotNull(project.basePath))
        val guidance = root.resolve(JunieAgent.EXCLUSIVE_GUIDANCE_PATH)
        val directory = Files.createDirectories(guidance.parent)
        val virtualDirectory = checkNotNull(VirtualFileManager.getInstance().refreshAndFindFileByNioPath(directory))
        virtualDirectory.children
        Files.writeString(guidance, "A")

        refreshJunieFiles(root)
        val virtualGuidance = checkNotNull(virtualDirectory.findChild(guidance.fileName.toString()))
        assertEquals("A", virtualGuidance.inputStream.bufferedReader().readText())

        Files.writeString(guidance, "B")
        refreshJunieFiles(root)
        assertEquals("B", virtualGuidance.inputStream.bufferedReader().readText())

        Files.delete(guidance)
        refreshJunieFiles(root)
        assertNull(virtualDirectory.findChild(guidance.fileName.toString()))
    }

    fun `test external AI Assistant configuration updates and deletion are published to the IDE VFS`() {
        val root = Path.of(checkNotNull(project.basePath))
        val config = root.resolve(AiAssistantAgent.CONFIG_PATH)
        val directory = Files.createDirectories(config.parent)
        val virtualDirectory = checkNotNull(VirtualFileManager.getInstance().refreshAndFindFileByNioPath(directory))
        virtualDirectory.children
        Files.writeString(config, "A")

        refreshAiAssistantFiles(root)
        val virtualConfig = checkNotNull(virtualDirectory.findChild(config.fileName.toString()))
        writeSecretFile(config, "B")
        refreshAiAssistantFiles(root)
        assertEquals("B", virtualConfig.inputStream.bufferedReader().readText())

        Files.delete(config)
        refreshAiAssistantFiles(root)
        assertNull(virtualDirectory.findChild(config.fileName.toString()))
    }
}
