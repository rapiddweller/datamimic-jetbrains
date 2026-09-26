// DATAMIMIC for JetBrains IDEs
// Copyright (c) 2026 Rapiddweller Asia Co., Ltd.
// SPDX-License-Identifier: MIT

package com.rapiddweller.datamimic.ide

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.rapiddweller.datamimic.core.AccountApi
import com.rapiddweller.datamimic.core.FakePlatform
import com.rapiddweller.datamimic.core.PlatformHttp
import com.rapiddweller.datamimic.core.PlatformException
import com.rapiddweller.datamimic.core.SessionExpiredException
import com.rapiddweller.datamimic.core.SessionService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import java.net.http.HttpClient

class PlatformHttpClientTest : BasePlatformTestCase() {
    private val platform = FakePlatform()
    private lateinit var http: HttpClient

    override fun setUp() {
        super.setUp()
        http = platformHttpClient()
    }

    override fun tearDown() {
        try {
            http.shutdownNow()
            platform.close()
        } finally {
            super.tearDown()
        }
    }

    fun `test platform 401 without challenge stays a platform error`() {
        val sessions = SessionService(http, { null }, {})

        val error: PlatformException = org.junit.Assert.assertThrows(PlatformException::class.java) {
            sessions.login(platform.origin, "ada@example.com", "wrong")
        }

        assertEquals(401, error.status)
    }

    fun `test an ended session 401 stays a session error`() {
        var stored: String? = null
        var expired = 0
        val sessions = SessionService(http, { stored }, { stored = it }, onExpired = { expired++ })
        val account = AccountApi(PlatformHttp(http, sessions, "binding"))
        sessions.login(platform.origin, "ada@example.com", "secret")
        platform.sessionId = "rotated-on-the-server"

        org.junit.Assert.assertThrows(SessionExpiredException::class.java) { account.me() }

        assertNull(sessions.current())
        assertEquals(1, expired)
    }
}
