package com.stash.data.download.lossless.antra

/**
 * The single source of truth for antra's browser fingerprint headers.
 *
 * Cloudflare binds the `cf_clearance` cookie to the requesting browser's
 * fingerprint (User-Agent + client hints). For the OkHttp cookie-replay
 * (Approach A) to satisfy Cloudflare, the [AntraConnectScreen] WebView that
 * *mints* `cf_clearance` and the [AntraCookieInterceptor] that *replays* it
 * MUST send byte-identical headers — hence this shared, public holder
 * consumed by both (the interceptor lives in `:data:download`, the WebView
 * in `:feature:settings`).
 *
 * If on-device testing (plan Task 10) shows 403s, the device's real WebView
 * UA likely differs from [USER_AGENT]; the fix is to harvest the live UA at
 * connect time and store it alongside the cookies rather than hardcoding.
 */
object AntraFingerprint {
    /** Android Chrome WebView UA used by BOTH the WebView and the replay. */
    const val USER_AGENT: String =
        "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

    /** Matching `sec-ch-ua` client-hint header. */
    const val SEC_CH_UA: String =
        "\"Google Chrome\";v=\"126\", \"Chromium\";v=\"126\", \"Not.A/Brand\";v=\"24\""
}
