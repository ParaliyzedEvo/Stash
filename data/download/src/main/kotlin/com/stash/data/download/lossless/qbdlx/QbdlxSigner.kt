package com.stash.data.download.lossless.qbdlx

import java.security.MessageDigest

/**
 * Signs Qobuz API requests. Qobuz validates `request_sig = md5(object+method
 * + params-in-fixed-order + request_ts + app_secret)`. The param order and
 * literal concatenation per endpoint were reverse-engineered from qbdlx's JS
 * and locked by [QbdlxSignerTest] against real HAR vectors. [clock] returns
 * epoch SECONDS (injectable so the vectors are reproducible).
 *
 * The `app_secret` is a PER-CALL argument, not constructor state: a Qobuz token
 * only returns full-quality FLAC when it is signed with the `app_secret` of the
 * app_id it was minted under. The user-connected account carries its own pair,
 * stored with the token at login, so the secret is looked up per request through
 * [QbdlxSigningResolver] — Stash ships none to hardcode, and a mismatched secret
 * would silently degrade the token to 30-second previews rather than fail.
 */
class QbdlxSigner(
    private val clock: () -> Long = { System.currentTimeMillis() / 1000L },
) {
    fun requestTs(): Long = clock()

    /**
     * Signs with the caller-supplied [ts]. The caller reads [requestTs] ONCE and
     * passes that same value into both the URL's `request_ts` param and here, so
     * the signed timestamp can never drift from the sent timestamp.
     */
    fun signGetFileUrl(ts: Long, trackId: Long, formatId: Int, appSecret: String): String =
        md5("trackgetFileUrl" + "format_id$formatId" + "intentstream" + "track_id$trackId" + ts + appSecret)

    fun signLyricsUrl(ts: Long, trackId: Long, appSecret: String): String =
        md5("tracklyricsUrl" + "track_id$trackId" + ts + appSecret)

    private fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
