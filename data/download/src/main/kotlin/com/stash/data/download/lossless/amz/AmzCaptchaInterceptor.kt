package com.stash.data.download.lossless.amz

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Authenticates amz.squid.wtf data requests as the web interface.
 *
 * Since v0.9.55 amz requires three things on the `/api/` data calls (search,
 * track, stream), all attached via [amzWebHeaders] so every amz code path gets
 * them from one place:
 *  - the `x-captcha-token` (minted by [AmzCaptchaClient]),
 *  - the `amz_web_sess` session cookie the token is bound to, and
 *  - a browser header fingerprint — without it amz replies 403 "This API is
 *    only available through the web interface."
 *
 * Host-scoped: a no-op for every other host, safe on the SHARED OkHttpClient.
 * Bypasses the `/api/captcha` mint round-trips AND the non-`/api` root `/` GET
 * (both made by [AmzCaptchaClient] during minting) so they never recurse here.
 *
 * Token is minted lazily and cached `@Volatile`; minting is single-flight
 * (one PoW solve shared by concurrent callers). On a stale-token response
 * ([STALE_CODE]) the token is re-minted once and the request retried once.
 */
@Singleton
class AmzCaptchaInterceptor @Inject constructor(
    private val captchaClient: AmzCaptchaClient,
) : Interceptor {

    @Volatile private var token: String? = null
    private val mintMutex = Mutex()

    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        if (req.url.host != HOST) return chain.proceed(req)
        // Only the /api/ DATA endpoints get authenticated. Skip the captcha mint
        // calls and the root "/" page-session GET — both are part of minting and
        // would recurse if we tried to tokenize them.
        val path = req.url.encodedPath
        if (!path.startsWith("/api/") || path.startsWith("/api/captcha")) return chain.proceed(req)

        val current = token ?: mintBlocking(force = false)
        if (current == null) return chain.proceed(req) // let it fail; source fails over
        val resp = chain.proceed(
            req.newBuilder().amzWebHeaders(current, captchaClient.sessionCookie).build(),
        )
        if (resp.code != STALE_CODE) return resp

        resp.close()
        val fresh = mintBlocking(force = true) ?: return chain.proceed(req)
        // Re-read the cookie: a re-mint may have rotated amz_web_sess.
        return chain.proceed(
            req.newBuilder().amzWebHeaders(fresh, captchaClient.sessionCookie).build(),
        )
    }

    private fun mintBlocking(force: Boolean): String? = runBlocking {
        mintMutex.withLock {
            if (!force) token?.let { return@withLock it } // another caller already minted
            captchaClient.mint()?.also { token = it }
        }
    }

    private companion object {
        const val HOST = "amz.squid.wtf"
        // Verified by live recon 2026-06-15: amz returns 403 (NOT 401) for a
        // stale/invalid x-captcha-token. amz has its own player routing branch
        // (not RefreshingDataSource, whose 403/410 trigger is YouTube-only), so
        // treating 403 as the re-mint trigger here causes no collision.
        const val STALE_CODE = 403
    }
}
