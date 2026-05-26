package com.stash.core.data.sync.auth

enum class AuthSource { SPOTIFY, YOUTUBE }

/**
 * One per connected source. The sync chain runs these in parallel at the
 * start of every sync to detect silent cookie/token expiry before any
 * downloads attempt to use the bad credentials.
 *
 * [isExpired] must be conservative: network failures and ambiguous
 * responses should return `false` (treat as healthy) rather than risk
 * false-positive banner storms when the user's internet is just flaky.
 */
interface AuthHealthProbe {
    val source: AuthSource
    suspend fun isExpired(): Boolean
}
