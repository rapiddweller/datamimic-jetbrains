// DATAMIMIC for JetBrains IDEs
// Copyright (c) 2026 Rapiddweller Asia Co., Ltd.
// SPDX-License-Identifier: MIT

package com.rapiddweller.datamimic

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class SourceHeaderTest {
    @Test
    fun `every Kotlin and Java source and build script starts with the MIT header`() {
        val sources = File("src").walk().filter { it.extension == "kt" || it.extension == "java" } + File(".").listFiles { file -> file.extension == "kts" }.orEmpty()
        val missing = sources.filterNot { it.readText().startsWith(HEADER) }.map { it.path }.toList()
        assertEquals(emptyList<String>(), missing)
    }

    private companion object {
        const val HEADER = "// DATAMIMIC for JetBrains IDEs\n// Copyright (c) 2026 Rapiddweller Asia Co., Ltd.\n// SPDX-License-Identifier: MIT\n"
    }
}
