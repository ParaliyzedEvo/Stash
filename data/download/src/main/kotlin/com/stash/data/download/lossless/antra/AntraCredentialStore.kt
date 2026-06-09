package com.stash.data.download.lossless.antra

import com.stash.data.download.lossless.LosslessSourcePreferences
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds the credentials Stash uses to authenticate against
 * antra.hoshi.cfd's lossless download endpoint.
 *
 * antra authenticates on cookies harvested by the in-app connect WebView
 * (verified on-device 2026-06-09):
 *
 *  - `antra_session` — HttpOnly login cookie; alone sufficient, required.
 *  - `cf_clearance`  — only exists while Cloudflare is actively
 *                      challenging; replayed when present, never required.
 *
 * [isConnected] is therefore true when the session cookie is non-blank.
 * The persistence itself lives on [LosslessSourcePreferences] (the shared
 * DataStore-backed prefs the squid captcha cookie also uses), so
 * credentials survive process restarts; this class is the thin domain
 * wrapper that derives the "connected?" state and the ready-to-send Cookie
 * header from them.
 */
@Singleton
class AntraCredentialStore @Inject constructor(
    private val prefs: LosslessSourcePreferences,
) {

    /**
     * True when the antra session cookie is present. `cf_clearance` is NOT
     * required: on-device evidence (2026-06-09) shows antra authenticates on
     * the `antra_session` cookie alone, and `cf_clearance` is only set while
     * Cloudflare is actively challenging (usually absent).
     */
    suspend fun isConnected(): Boolean {
        return !prefs.antraSessionCookieNow().isNullOrBlank()
    }

    /**
     * The `Cookie` header value for an authenticated antra request:
     * `antra_session=<s>`, plus `; cf_clearance=<c>` only when a clearance
     * cookie was captured (it usually isn't). Returns null when there's no
     * session cookie (so callers fall through cleanly).
     */
    suspend fun cookieHeader(): String? {
        val session = prefs.antraSessionCookieNow()?.takeIf { it.isNotBlank() } ?: return null
        val cfClearance = prefs.antraCfClearanceNow()?.takeIf { it.isNotBlank() }
        return buildString {
            append("$SESSION_COOKIE=$session")
            if (cfClearance != null) append("; $CF_CLEARANCE_COOKIE=$cfClearance")
        }
    }

    /** The connected antra account's username, for Settings display. */
    suspend fun username(): String? = prefs.antraUsernameNow()

    /** Persists the login `session` + `cf_clearance` cookies and username. */
    suspend fun save(session: String, cfClearance: String, username: String?) {
        prefs.setAntraCredentials(session = session, cfClearance = cfClearance, username = username)
    }

    /**
     * Clears the connection — call when the session is detected stale
     * (e.g. a 401/403 from antra). Afterwards [isConnected] is false and
     * [cookieHeader] returns null until the user re-authenticates.
     */
    suspend fun markStale() {
        prefs.clearAntraCredentials()
    }

    private companion object {
        // antra's login cookie is named `antra_session` (verified on-device
        // 2026-06-09), NOT `session`.
        const val SESSION_COOKIE = "antra_session"
        const val CF_CLEARANCE_COOKIE = "cf_clearance"
    }
}
