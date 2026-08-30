package com.stash.data.download.lossless.qbdlx

/**
 * The (app_id, app_secret) pair a given token's requests must be signed with.
 *
 * Qobuz binds a `user_auth_token` to the app_id it was minted under: `getFileUrl`
 * only returns full FLAC when the request is signed with THAT app_id's secret.
 * Sign with the wrong pair and the same token silently yields a 30-second preview
 * (HTTP 200, `sample:true`) — indistinguishable from "track not available" unless
 * you know to look. So the pair travels with the token, not with the client.
 */
data class QbdlxSigning(val appId: String, val appSecret: String)

/**
 * Resolves the [QbdlxSigning] pair to use for a token. Implemented by
 * [QbdlxCredentialStore], where exactly one token has a pair: the user's own
 * connected account, which stores the app_id/secret it was minted under. Stash
 * bundles no app_secret, so there is nothing to fall back to — any other token
 * throws [QbdlxAuthException] rather than be signed with a pair that isn't its own.
 *
 * Narrow interface (not the whole store) so [QbdlxApiClient] depends only on the
 * one thing it needs and stays trivially fake-able in tests.
 */
fun interface QbdlxSigningResolver {
    suspend fun signingFor(token: String): QbdlxSigning
}
