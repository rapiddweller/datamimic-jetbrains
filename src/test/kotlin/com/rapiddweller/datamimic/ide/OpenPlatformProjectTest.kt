// DATAMIMIC for JetBrains IDEs
// Copyright (c) 2026 Rapiddweller Asia Co., Ltd.
// SPDX-License-Identifier: MIT

package com.rapiddweller.datamimic.ide

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class OpenPlatformProjectTest : BasePlatformTestCase() {
    fun `test downloaded projects are forced into a new frame`() = assertTrue(downloadedProjectTask().forceOpenInNewFrame)
}
