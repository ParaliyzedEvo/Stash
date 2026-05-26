package com.stash.data.download.preview

import android.content.Context
import android.util.Log
import com.stash.core.auth.TokenManager
import com.stash.data.download.CookieFileWriter
import com.stash.data.download.ytdlp.YtDlpManager
import com.stash.data.ytmusic.InnerTubeClient
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Extracts a direct audio stream URL from YouTube for preview playback.
 *
 * ## Strategy: InnerTube vs yt-dlp race (parallel, split concurrency)
 *
 * Both extractors run concurrently, each bounded by its own shared
 * [Semaphore] so the two pools don't starve each other:
 *
 *  - **InnerTube player API (~1-2s)**: Calls the YouTube Music player
 *    endpoint with authenticated cookies. Parses
 *    `streamingData.adaptiveFormats` for audio-only URLs. These URLs may
 *    be n-parameter throttled (~50KB/s) but that's more than enough for
 *    audio preview (Opus is ~20KB/s). Cap: 8 concurrent.
 *
 *  - **yt-dlp fallback (~15-35s)**: Heavier path using QuickJS for full
 *    signature solving. Slow but reliable, so we cap it at 2 concurrent
 *    to avoid thrashing CPU / the yt-dlp JNI surface.
 *
 * The race returns whichever extractor succeeds first:
 *  - InnerTube returns a non-null URL → cancel yt-dlp and return it.
 *  - InnerTube returns null (ciphered/geo-blocked/unavailable) or
 *    throws → await yt-dlp and return its result (throws on hard fail).
 *
 * Any non-cancellation failure in InnerTube is rescued inside the async
 * as a null result, so the race transparently falls back to yt-dlp
 * without poisoning the enclosing [coroutineScope].
 *
 * Because the InnerTube fast path succeeds for most YouTube Music
 * tracks, previews feel near-instant while preserving yt-dlp as a
 * correctness backstop.
 */
@Singleton
class PreviewUrlExtractor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ytDlpManager: YtDlpManager,
    private val tokenManager: TokenManager,
    private val innerTubeClient: InnerTubeClient,
) {
    /** Test-only injection point for race logic. Not wired in production. */
    internal interface TestHooks {
        suspend fun innerTubeExtract(id: String): String?
        suspend fun ytDlpExtract(id: String): String
    }

    /**
     * Shared scope for in-flight extracts. Caller cancellation only stops
     * the *caller's* await(); the underlying extract keeps running so any
     * other awaiter (or a future tap) still gets the URL. The scope lives
     * for the singleton's lifetime; entries clean up via invokeOnCompletion.
     */
    private val extractorScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Single-flight cache. Key = videoId; value = the in-flight Deferred.
     * On completion (success OR failure) the entry self-removes via
     * invokeOnCompletion so the next call for the same videoId starts a
     * fresh extract.
     */
    private val inFlightExtracts = ConcurrentHashMap<String, Deferred<String>>()

    companion object {
        private const val TAG = "PreviewUrlExtractor"
        private const val YTDLP_TIMEOUT_MS = 60_000L
        private const val INNERTUBE_TIMEOUT_MS = 10_000L
        private const val FORMAT_SELECTOR = "251/250/bestaudio"

        /**
         * Concurrency caps for the two extractors. Shared process-wide.
         *
         * yt-dlp-android throws YoutubeDLException at ~120ms when a second
         * execute() runs while one is in flight (or shortly after a cancelled
         * one). On-device LATDIAG from feat/tap-cancel-hardening showed
         * cascading failures with cap=2. Cap=1 forces serialization at the
         * JNI boundary; coalescing (above) ensures redundant calls for the
         * same videoId never reach the semaphore at all.
         */
        private const val INNERTUBE_CONCURRENCY = 8
        private const val YTDLP_CONCURRENCY = 1   // was 2 — see spec

        /**
         * Shared so parallel callers (e.g. `PreviewPrefetcher`) respect the
         * cap regardless of how many [PreviewUrlExtractor] instances exist.
         */
        private val innerTubeSemaphore = Semaphore(INNERTUBE_CONCURRENCY)
        private val ytDlpSemaphore = Semaphore(YTDLP_CONCURRENCY)

        /**
         * Test-only: exercises [race] directly without Android deps. Reuses
         * the shared semaphores so the tests also assert the real caps.
         */
        internal suspend fun raceForTest(hooks: TestHooks, videoId: String): String =
            race(
                videoId = videoId,
                innerTubeExtract = hooks::innerTubeExtract,
                ytDlpExtract = hooks::ytDlpExtract,
                itSem = innerTubeSemaphore,
                ytSem = ytDlpSemaphore,
            )

        /**
         * Races the two extractors. InnerTube is preferred when it succeeds
         * (non-null return); otherwise yt-dlp's result wins. Each extractor
         * acquires/releases its own semaphore, so a flood of InnerTube
         * requests can never block yt-dlp (or vice versa).
         *
         * Wrapped in [coroutineScope] so a cancel on either side also tears
         * down the sibling job — important so `yt.cancel()` actually stops
         * work and frees the yt-dlp permit.
         *
         * Any non-cancellation failure in InnerTube is rescued *inside* the
         * async (via [runCatching]) and surfaced as a null result. This
         * keeps the exception from escaping the async and poisoning the
         * enclosing [coroutineScope] (which would cancel yt-dlp and rethrow),
         * so the race transparently falls back to yt-dlp on any throw.
         */
        private suspend fun race(
            videoId: String,
            innerTubeExtract: suspend (String) -> String?,
            ytDlpExtract: suspend (String) -> String,
            itSem: Semaphore,
            ytSem: Semaphore,
        ): String {
            itSem.acquire()
            val itResult = try {
                // Treat any non-cancellation failure as null so the
                // race falls back to yt-dlp. CancellationException MUST
                // propagate to preserve structured concurrency.
                runCatching { innerTubeExtract(videoId) }
                    .getOrElse { t ->
                        if (t is CancellationException) throw t
                        null
                    }
            } finally {
                itSem.release()
            }
            if (itResult != null) {
                return itResult
            }
            ytSem.acquire()
            return try {
                ytDlpExtract(videoId)
            } finally {
                ytSem.release()
            }
        }
    }

    /**
     * Extracts a direct audio stream URL for the given YouTube video ID.
     *
     * Races InnerTube (fast, ~1-2s) against yt-dlp (slow, ~15-35s). See
     * the class KDoc for the full strategy.
     *
     * Concurrent calls for the same [videoId] are coalesced via [coalesce]
     * so only one extract runs at a time per ID.
     */
    suspend fun extractStreamUrl(videoId: String, bypassYtDlp: Boolean = false): String =
        coalesce(videoId, bypassYtDlp) { id, bypass -> doExtract(id, bypass) }

    /**
     * Test-only: exercises the coalescing wrapper with the existing
     * TestHooks SPI. The race body is replaced by a hook-driven function
     * so the test can observe coalescing without needing real Android deps.
     *
     * Does NOT exercise the real race(...) semantics — those are tested
     * by raceForTest in the same file. This helper tests only the
     * single-flight / coalescing layer above the race.
     *
     * Instance member (not companion) because `coalesce` needs `this`.
     */
    internal suspend fun extractStreamUrlForTest(
        hooks: TestHooks,
        videoId: String,
    ): String = coalesce(videoId, bypassYtDlp = false) { id, _ ->
        hooks.innerTubeExtract(id) ?: hooks.ytDlpExtract(id)
    }

    /**
     * Single-flight wrapper. If an extract for [videoId] is already in flight,
     * the new caller awaits its result; otherwise a fresh Deferred is started
     * on [extractorScope] and other callers join it.
     *
     * The Deferred lives on a scope independent of any caller's lifetime —
     * caller cancellation only stops the caller's own await(); the underlying
     * extract continues to completion so any other awaiter still gets a URL.
     *
     * **Completion ordering.** `invokeOnCompletion` fires synchronously when
     * the Deferred completes, *before* any suspended awaiter is resumed. So
     * by the time `await()` returns to any caller, the map entry is already
     * gone. A racing caller arriving in that microscopic window starts a
     * brand-new extract — which is benign because the just-completed URL
     * is in `PreviewUrlCache` (populated by the prefetcher and other
     * post-success caching paths), so the redundant extract returns fast
     * from cache lookup rather than re-running yt-dlp.
     *
     * **Two-arg `remove(key, value)`** is used so a stale cleanup callback
     * cannot accidentally remove a *successor's* Deferred if a new caller
     * for the same videoId raced in just after the prior one completed.
     */
    private suspend fun coalesce(
        videoId: String,
        bypassYtDlp: Boolean,
        doRace: suspend (String, Boolean) -> String,
    ): String {
        val mapKey = if (bypassYtDlp) videoId else "$videoId-fallback"
        // Fast path — share an in-flight extract if one exists.
        inFlightExtracts[mapKey]?.let { return it.await() }

        // Create a LAZY Deferred so a putIfAbsent loser's Deferred never starts.
        val freshlyCreated = extractorScope.async(start = CoroutineStart.LAZY) {
            doRace(videoId, bypassYtDlp)
        }
        val deferred = inFlightExtracts.putIfAbsent(mapKey, freshlyCreated)
            ?: freshlyCreated.also { d ->
                d.invokeOnCompletion { inFlightExtracts.remove(mapKey, d) }
                d.start()
            }
        return deferred.await()
    }

    /**
     * Underlying race body — original `extractStreamUrl` implementation.
     * Called via [coalesce] from the public entry point.
     */
    private suspend fun doExtract(videoId: String, bypassYtDlp: Boolean): String {
        val t0 = System.currentTimeMillis()
        Log.d("LATDIAG", "extract-start videoId=$videoId bypassYtDlp=$bypassYtDlp")
        return try {
            val url = race(
                videoId = videoId,
                innerTubeExtract = { id ->
                    val it0 = System.currentTimeMillis()
                    val result = runCatching { extractViaInnerTube(id) }
                    val dt = System.currentTimeMillis() - it0
                    val outcome = result.fold(
                        onSuccess = { if (it != null) "url" else "null" },
                        onFailure = { "throw:${it.javaClass.simpleName}" },
                    )
                    Log.d("LATDIAG", "innertube-end videoId=$id dt=${dt}ms outcome=$outcome")
                    result.getOrThrow()
                },
                ytDlpExtract = { id ->
                    if (bypassYtDlp) {
                        throw NoSuchElementException("No unciphered stream URL available (yt-dlp bypassed)")
                    }
                    val yt0 = System.currentTimeMillis()
                    val result = runCatching { extractViaYtDlp(id) }
                    val dt = System.currentTimeMillis() - yt0
                    val outcome = result.fold(
                        onSuccess = { "url" },
                        onFailure = { "throw:${it.javaClass.simpleName}" },
                    )
                    Log.d("LATDIAG", "ytdlp-end videoId=$id dt=${dt}ms outcome=$outcome")
                    result.getOrThrow()
                },
                itSem = innerTubeSemaphore,
                ytSem = ytDlpSemaphore,
            )
            Log.d("LATDIAG", "extract-end videoId=$videoId dt=${System.currentTimeMillis() - t0}ms")
            url
        } catch (t: Throwable) {
            Log.d("LATDIAG", "extract-fail videoId=$videoId dt=${System.currentTimeMillis() - t0}ms err=${t.javaClass.simpleName}")
            throw t
        }
    }

    /**
     * Retry-only entry point: bypass the InnerTube race and go straight to
     * yt-dlp. Used by [com.stash.feature.search.SearchViewModel.onPreviewError]
     * when ExoPlayer rejects an InnerTube URL (typically because the URL is
     * n-parameter-throttled past what ExoPlayer is willing to wait for).
     *
     * yt-dlp's QuickJS cipher path produces unthrottled URLs that play
     * reliably, at the cost of ~15-35 s extraction latency.
     */
    suspend fun extractViaYtDlpForRetry(videoId: String): String =
        extractViaYtDlp(videoId)

    /**
     * Fast path: extract stream URL via InnerTube player API.
     *
     * Calls `/youtubei/v1/player` with the user's YouTube session cookies.
     * Parses `streamingData.adaptiveFormats` for audio-only formats with
     * direct `url` fields (not `signatureCipher`).
     *
     * Returns null if no usable URL is found (all formats ciphered, video
     * unavailable, etc.).
     */
    private suspend fun extractViaInnerTube(videoId: String): String? {
        return withTimeout(INNERTUBE_TIMEOUT_MS) {
            Log.d(TAG, "Trying InnerTube fast path for videoId=$videoId")

            // Ordered client-variant attempt. ANDROID_VR / IOS frequently
            // return unciphered adaptiveFormats urls, letting us skip the
            // ~14 s yt-dlp + QuickJS cipher-solve path entirely. Falls
            // back transparently to WEB_REMIX if neither yields a direct URL.
            val response = innerTubeClient.playerForAudio(videoId) ?: run {
                Log.w(TAG, "InnerTube playerForAudio returned null for $videoId")
                return@withTimeout null
            }

            // Check playability
            val status = response["playabilityStatus"]
                ?.jsonObject?.get("status")
                ?.jsonPrimitive?.content
            if (status != "OK") {
                Log.w(TAG, "InnerTube: video $videoId status=$status")
                return@withTimeout null
            }

            // Extract streamingData.adaptiveFormats
            val streamingData = response["streamingData"]?.jsonObject ?: run {
                Log.w(TAG, "InnerTube: no streamingData for $videoId")
                return@withTimeout null
            }

            val adaptiveFormats = streamingData["adaptiveFormats"]?.jsonArray ?: run {
                Log.w(TAG, "InnerTube: no adaptiveFormats for $videoId")
                return@withTimeout null
            }

            // Find the best audio format with a direct URL (not signatureCipher)
            val audioFormats = adaptiveFormats
                .filterIsInstance<JsonObject>()
                .filter { format ->
                    val mimeType = format["mimeType"]?.jsonPrimitive?.content ?: ""
                    val hasDirectUrl = format["url"] != null
                    val isAudio = mimeType.startsWith("audio/")
                    isAudio && hasDirectUrl
                }
                .sortedByDescending { it["bitrate"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L }

            val bestAudio = audioFormats.firstOrNull() ?: run {
                Log.d(TAG, "InnerTube: no audio formats with direct URL for $videoId " +
                    "(${adaptiveFormats.size} total formats, all may be ciphered)")
                return@withTimeout null
            }

            val streamUrl = bestAudio["url"]!!.jsonPrimitive.content
            val mimeType = bestAudio["mimeType"]?.jsonPrimitive?.content ?: "unknown"
            val bitrate = bestAudio["bitrate"]?.jsonPrimitive?.content ?: "?"

            Log.d(TAG, "InnerTube: SUCCESS videoId=$videoId mime=$mimeType bitrate=$bitrate urlLen=${streamUrl.length}")
            streamUrl
        }
    }

    /**
     * Slow fallback: extract stream URL via yt-dlp with QuickJS cipher solving.
     */
    private suspend fun extractViaYtDlp(videoId: String): String {
        val cookieFile = File(context.noBackupFilesDir, "yt_preview_cookies_${System.nanoTime()}.txt")

        return withTimeout(YTDLP_TIMEOUT_MS) {
            withContext(Dispatchers.IO) {
                ytDlpManager.initialize()

                try {
                    val url = "https://www.youtube.com/watch?v=$videoId"

                    val request = YoutubeDLRequest(url).apply {
                        addOption("-f", FORMAT_SELECTOR)
                        addOption("--print", "urls")
                        addOption("--no-download")

                        val qjsPath = ytDlpManager.quickJsPath
                        if (qjsPath != null) {
                            addOption("--js-runtimes", "quickjs:$qjsPath")
                            addOption("--remote-components", "ejs:github")
                        }
                    }

                    val cookie = tokenManager.getYouTubeCookie()
                    if (cookie != null) {
                        CookieFileWriter.write(cookie, cookieFile)
                        cookieFile.setReadable(false, false)
                        cookieFile.setReadable(true, true)
                        cookieFile.setWritable(false, false)
                        cookieFile.setWritable(true, true)
                        request.addOption("--cookies", cookieFile.absolutePath)
                    }

                    Log.d(TAG, "yt-dlp: invoking for videoId=$videoId")
                    val response = YoutubeDL.getInstance().execute(request, url, null)

                    val stdout = response.out.orEmpty()
                    val stderr = response.err.orEmpty()
                    Log.d(TAG, "yt-dlp: exit=${response.exitCode} stdoutLen=${stdout.length}")
                    if (stderr.isNotBlank()) {
                        Log.d(TAG, "yt-dlp stderr: ${stderr.take(500)}")
                    }

                    val streamUrl = stdout.trim().lines().firstOrNull { it.startsWith("http") }
                    check(!streamUrl.isNullOrBlank()) {
                        "yt-dlp returned no stream URL for videoId=$videoId. stderr: ${stderr.take(500)}"
                    }

                    Log.d(TAG, "yt-dlp: SUCCESS videoId=$videoId urlLen=${streamUrl.length}")
                    streamUrl
                } finally {
                    if (cookieFile.exists()) cookieFile.delete()
                }
            }
        }
    }
}
