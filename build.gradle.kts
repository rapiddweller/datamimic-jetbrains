// DATAMIMIC for JetBrains IDEs
// Copyright (c) 2026 Rapiddweller Asia Co., Ltd.
// SPDX-License-Identifier: MIT

import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.intellij.platform")
}

// WHY: built and tested on 2025.3, but must also run on 2024.2, which ships Kotlin 1.9.
kotlin {
    jvmToolchain(21)
    compilerOptions {
        apiVersion = KotlinVersion.KOTLIN_1_9
        // WHY: without compatibility bridges, classes do not re-declare platform interface methods that are internal in older IDEs.
        jvmDefault = JvmDefaultMode.NO_COMPATIBILITY
    }
}

// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
dependencies {
    // WHY: compile against 2024.2's runtime; the IDE provides the runtime at run time.
    compileOnly(libs.serialization.core)
    compileOnly(libs.serialization.json)
    testImplementation(libs.junit)

    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        intellijIdea("2025.3.6.1")
        testFramework(TestFrameworkType.Platform)
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "242"
        }
    }
    // WHY: compiling against 2025.3 cannot see what older IDEs lack.
    pluginVerification {
        ides {
            create(IntelliJPlatformType.PyCharmProfessional, "2024.2.6")
            create(IntelliJPlatformType.IntellijIdeaUltimate, "2024.2.6")
            create(IntelliJPlatformType.IntellijIdeaUltimate, "2025.2.1")
            create(IntelliJPlatformType.IntellijIdea, "2025.3.6.1")
            create(IntelliJPlatformType.IntellijIdea, "2026.2.3")
        }
    }
}
