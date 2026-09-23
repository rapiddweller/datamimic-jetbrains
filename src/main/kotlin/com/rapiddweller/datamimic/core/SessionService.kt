// DATAMIMIC for JetBrains IDEs
// Copyright (c) 2026 Rapiddweller Asia Co., Ltd.
// SPDX-License-Identifier: MIT

package com.rapiddweller.datamimic.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.net.HttpCookie
import java.net.http.HttpClient

internal const val SESSION_COOKIE = "datamimic_session"

@Serializable
data class StoredSession(val origin: PlatformOrigin, val sessionId: String)

@Serializable
data class PlatformUser(
    val id: Long,
    val email: String,
    @SerialName("first_name") val firstName: String? = null,
    @SerialName("last_name") val lastName: String? = null,
)

/**
 * The platform's browser session, used as the IDE credential: the platform keeps it server-side for
 * DM_BROWSER_SESSION_EXPIRE_SECONDS (30 days by default) and has no refresh, so expiry means signing in again.
 * [load]/[save] are the secure store; the password is never stored.
 *
 * @param onExpired called once when a request finds the current session rejected by the platform.
 */
class SessionService(
    private val http: HttpClient,
    private val load: () -> String?,
    private val save: (String?) -> Unit,
    private val onExpired: () -> Unit = {},
) {
    // WHY: every request needs the session; reading the OS credential store each time is slow. This class is its only writer.
    @Volatile
    private var cached: Cached? = null

    private class Cached(val session: StoredSession?)

    fun current(): StoredSession? {
        cached?.let { return it.session }
        val session = load()?.let { runCatching { json.decodeFromString<StoredSession>(it) }.getOrNull() }
        cached = Cached(session)
        return session
    }

    @Synchronized
    private fun store(session: StoredSession?) {
        save(session?.let { json.encodeToString(StoredSession.serializer(), it) })
        cached = Cached(session)
    }

    /**
     * The platform rejected [rejected]. Forgets it only if it is still the current session: a request that was already
     * in flight with an older session must not end a newer sign-in.
     */
    fun expire(rejected: StoredSession) {
        val ended = synchronized(this) {
            (current() == rejected).also { if (it) store(null) }
        }
        if (ended) onExpired()
    }

    fun login(origin: PlatformOrigin, email: String, password: String): StoredSession {
        val response = try {
            http.sendPlatformRequest(
                origin,
                HttpMethod.POST,
                LOGIN_PATH,
                RequestBody.Form(mapOf("username" to email, "password" to password)),
                emptyMap(),
            ).orThrow(HttpMethod.POST, LOGIN_PATH)
        } catch (e: PlatformException) {
            if (e.code != PlatformErrorCode.AUTH_ORIGIN_FORBIDDEN) throw e
            throw PlatformException(
                e.status,
                e.code,
                "The platform only accepts sign-ins from its public URL. Use exactly the address you open it with in the browser " +
                    "(for example, localhost and 127.0.0.1 are different).",
            )
        }
        val sessionId = response.headers(PlatformHeader.SET_COOKIE)
            .flatMap { runCatching { HttpCookie.parse(it) }.getOrDefault(emptyList()) }
            .firstOrNull { it.name == SESSION_COOKIE }
            ?.value
            ?: throw IllegalStateException("The platform accepted the sign-in but returned no session.")
        val previous = current()
        val session = StoredSession(origin, sessionId)
        store(session)
        previous?.let(::revoke)
        return session
    }

    /** @return false when the platform could not revoke the session; it then expires there on its own. */
    fun logout(): Boolean {
        val session = current() ?: return true
        store(null)
        return revoke(session)
    }

    private fun revoke(session: StoredSession): Boolean = runCatching {
        http.sendPlatformRequest(
            session.origin,
            HttpMethod.POST,
            LOGOUT_PATH,
            RequestBody.Empty,
            mapOf(PlatformHeader.COOKIE to "$SESSION_COOKIE=${session.sessionId}"),
        ).orThrow(HttpMethod.POST, LOGOUT_PATH)
    }.isSuccess

    private companion object {
        const val LOGIN_PATH = "/api/v2/session/login"
        const val LOGOUT_PATH = "/api/v2/session/logout"
    }
}

class AccountApi(private val http: PlatformHttp) {
    fun me(): PlatformUser = json.decodeFromString(http.getJson("/api/v2/me"))
}
