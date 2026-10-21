package com.stash.data.lyrics.source

import android.util.Log
import com.stash.core.common.Clock
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One skip window for the lrc.red host family, shared by everything that talks to it: the
 * title/artist -> ISRC search ([IsrcResolver]) and both ISRC-keyed TTML stores
 * ([IsrcTtmlLyricsSource] for lrc.red and Binimum). They are one operator behind one Cloudflare
 * front (`lyrics-api.binimum.org` 307-redirects to `lrc.red/api/v1`), so when one is black-holed
 * the others usually are too, and three separate breakers would cost three timeouts per open.
 *
 * Same pattern as [AppleTtmlLyricsSource]'s skip window, tuned for an optional extra: these
 * sources only ever add word timing on top of lyrics another source already found (or try last,
 * after every other source missed), so a single transport failure ([SKIP_AFTER_FAILURES]) or
 * any HTTP 429 / challenge page is enough to stop asking for [SKIP_WINDOW_MS]. A black-holed host
 * therefore costs at most one timeout per window instead of a few seconds on every lyrics open.
 * While the window is open, [call] throws [SkippedException] without touching the network.
 *
 * Process-wide because it is a Hilt singleton; thread-safe via `@Synchronized`.
 */
@Singleton
class IsrcLyricsHostBreaker @Inject constructor(private val clock: Clock) {

    /** Thrown while the window is open: the request was not sent. */
    class SkippedException : IOException("lrc.red/Binimum lyrics skipped: the host keeps failing or rate-limiting")

    private var consecutiveFailures = 0
    private var skipUntilMs = 0L

    /** True while requests are being skipped. */
    @Synchronized
    fun isOpen(): Boolean = clock.now() < skipUntilMs

    /**
     * Runs one request [block] through the breaker: skipped while open, counted on the way out.
     * An HTTP answer (any status but 429) resets the failure count; a 429 opens the window; a
     * transport failure (timeout, refused connection, TLS error) counts toward opening it.
     */
    fun <T> call(block: () -> T): T {
        if (isOpen()) throw SkippedException()
        return try {
            block().also { onAnswer() }
        } catch (e: AppleTtmlLyricsSource.HttpStatusException) {
            if (e.code == 429) open("HTTP 429") else onAnswer()
            throw e
        } catch (e: IOException) {
            onTransportFailure()
            throw e
        }
    }

    /** The host answered with something that isn't ours (a Cloudflare challenge page): stop asking. */
    fun onBlocked(reason: String) = open(reason)

    @Synchronized
    private fun onAnswer() {
        consecutiveFailures = 0
    }

    @Synchronized
    private fun onTransportFailure() {
        if (++consecutiveFailures >= SKIP_AFTER_FAILURES) open("$consecutiveFailures transport failure(s)")
    }

    @Synchronized
    private fun open(reason: String) {
        consecutiveFailures = 0
        skipUntilMs = clock.now() + SKIP_WINDOW_MS
        Log.w(TAG, "Skipping lrc.red/Binimum lyrics for ${SKIP_WINDOW_MS / 60_000} min: $reason")
    }

    companion object {
        private const val TAG = "IsrcLyricsHostBreaker"

        /** One failure opens the window: these sources are an optional extra, never the only answer. */
        internal const val SKIP_AFTER_FAILURES = 1

        /** Same window as the Apple source's. */
        internal const val SKIP_WINDOW_MS = AppleTtmlLyricsSource.SKIP_WINDOW_MS
    }
}
