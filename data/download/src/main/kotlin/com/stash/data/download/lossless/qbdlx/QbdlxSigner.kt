package com.stash.data.download.lossless.qbdlx

import java.security.MessageDigest

/**
 * Signs Qobuz API requests. Qobuz validates `request_sig = md5(object+method
 * + params-in-fixed-order + request_ts + app_secret)`. The param order and
 * literal concatenation per endpoint were reverse-engineered from qbdlx's JS
 * and locked by [QbdlxSignerTest] against real HAR vectors. [clock] returns
 * epoch SECONDS (injectable so the vectors are reproducible).
 */
class QbdlxSigner(
    private val appSecret: String,
    private val clock: () -> Long = { System.currentTimeMillis() / 1000L },
) {
    fun requestTs(): Long = clock()

    /** ts is returned alongside the sig so the caller sends the SAME ts it signed. */
    fun signGetFileUrl(trackId: Long, formatId: Int): String {
        val ts = clock()
        return md5("trackgetFileUrl" + "format_id$formatId" + "intentstream" + "track_id$trackId" + ts + appSecret)
    }

    fun signLyricsUrl(trackId: Long): String {
        val ts = clock()
        return md5("tracklyricsUrl" + "track_id$trackId" + ts + appSecret)
    }

    private fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
