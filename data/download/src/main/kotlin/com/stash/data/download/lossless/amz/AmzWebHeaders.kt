package com.stash.data.download.lossless.amz

import okhttp3.Request

/**
 * Attach the captcha token, session cookie, and browser header fingerprint that
 * amz.squid.wtf's "This API is only available through the web interface" gate
 * requires on the `/api/` data calls (verified by live leave-one-out bisect
 * 2026-06-27). Factored into one place so search/track/stream share it.
 *
 * [sessionCookie] is the `amz_web_sess=<value>` pair the token is bound to
 * (null before the first mint → no Cookie header).
 */
internal fun Request.Builder.amzWebHeaders(captchaToken: String, sessionCookie: String?): Request.Builder {
    header("x-captcha-token", captchaToken)
    header("User-Agent", AMZ_UA)
    header("Referer", "https://amz.squid.wtf/")
    header("Origin", "https://amz.squid.wtf")
    header("Accept-Language", "en-US,en;q=0.9")
    header("Sec-Fetch-Site", "same-origin")
    header("Sec-Fetch-Mode", "cors")
    header("Sec-Fetch-Dest", "empty")
    header("sec-ch-ua", "\"Chromium\";v=\"149\", \"Not_A Brand\";v=\"24\"")
    header("sec-ch-ua-mobile", "?1")
    if (sessionCookie != null) header("Cookie", sessionCookie)
    return this
}

private const val AMZ_UA =
    "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Mobile Safari/537.36"
