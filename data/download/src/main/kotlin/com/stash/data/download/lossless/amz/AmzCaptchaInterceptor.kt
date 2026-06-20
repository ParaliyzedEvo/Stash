package com.stash.data.download.lossless.amz

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Authenticates amz.squid.wtf requests with an `x-captcha-token` header.
 *
 * Host-scoped: a no-op for every other host, so it is safe to install on the
 * SHARED OkHttpClient (Task 2.2). Bypasses the `/api/captcha` path so the token-mint
 * round-trips ([AmzCaptchaClient]) never recurse through here.
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
        if (req.url.encodedPath.startsWith("/api/captcha")) return chain.proceed(req)

        val current = token ?: mintBlocking(force = false)
        if (current == null) return chain.proceed(req) // let it fail; source fails over
        val resp = chain.proceed(req.newBuilder().header(HEADER, current).build())
        if (resp.code != STALE_CODE) return resp

        resp.close()
        val fresh = mintBlocking(force = true) ?: return chain.proceed(req)
        return chain.proceed(req.newBuilder().header(HEADER, fresh).build())
    }

    private fun mintBlocking(force: Boolean): String? = runBlocking {
        mintMutex.withLock {
            if (!force) token?.let { return@withLock it } // another caller already minted
            captchaClient.mint()?.also { token = it }
        }
    }

    private companion object {
        const val HOST = "amz.squid.wtf"
        const val HEADER = "x-captcha-token"
        // Verified by live recon 2026-06-15: amz returns 403 (NOT 401) for a
        // stale/invalid x-captcha-token. amz has its own player routing branch
        // (not RefreshingDataSource, whose 403/410 trigger is YouTube-only), so
        // treating 403 as the re-mint trigger here causes no collision.
        const val STALE_CODE = 403
    }
}
