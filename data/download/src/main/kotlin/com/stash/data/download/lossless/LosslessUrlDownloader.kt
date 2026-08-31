package com.stash.data.download.lossless

import android.util.Log
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.buffer
import okio.sink

/**
 * Downloads a [SourceResult] to a local temp file. Used by the
 * lossless-source path to fetch the signed CDN URL produced by
 * `LosslessSourceRegistry.resolve(query)` — the resulting file is
 * then handed off to the existing `MetadataEmbedder` and `FileOrganizer`
 * pipeline (same as a yt-dlp output) and ends up in the same on-disk
 * location format as a yt-dlp-sourced track would.
 *
 * Failure modes (signed URL expired, network blip, partial body) all
 * return [Result.failure] rather than throwing, so the caller can
 * fall through to the next download strategy without try/catch noise.
 *
 * Streams via OkHttp+Okio rather than buffering in memory — FLAC files
 * are typically 25-50 MB and would put unnecessary pressure on the
 * heap if read whole.
 */
@Singleton
class LosslessUrlDownloader @Inject constructor(
    httpClient: OkHttpClient,
) {
    // Whole-file lossless fetches are large (a Hi-Res FLAC runs 70-140 MB). The
    // shared client's 30 s read timeout is an inter-byte stall limit — fine for
    // small JSON, too tight when a congested/shared network pauses mid-stream on
    // a big FLAC. Derive a client with a roomier read/write stall window (no
    // callTimeout, so total duration stays uncapped for big files). Shares the
    // pool/dispatcher/TLS.
    private val fetchClient: OkHttpClient = httpClient.newBuilder()
        .readTimeout(FETCH_STALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(FETCH_STALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    // JioSaavn URLs are accepted only on the exact aac.saavncdn.com host.
    // Disable redirects for the actual whole-file fetch so a URL that was
    // safe during the range probe cannot later pivot to an arbitrary host.
    private val noRedirectFetchClient: OkHttpClient = fetchClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    /**
     * Fetch [source] to [destination]. Returns the file on success, or
     * a failure with the reason to log at the call site.
     *
     * @param source       The resolved match from a [LosslessSource].
     * @param destination  Pre-allocated temp file path; will be
     *   truncated and overwritten. Caller chooses the extension based
     *   on [SourceResult.format].
     * @param onProgress   Bytes-downloaded callback. Total size is the
     *   `Content-Length` header when provided, else 0 — so callers
     *   should treat 0/total as "indeterminate" rather than "complete".
     */
    suspend fun download(
        source: SourceResult,
        destination: File,
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): Result<File> = withContext(Dispatchers.IO) {
        val requestBuilder = Request.Builder().url(source.downloadUrl).get()
        for ((name, value) in source.downloadHeaders) {
            requestBuilder.header(name, value)
        }
        val request = requestBuilder.build()

        try {
            val client = if (source.sourceId == NO_REDIRECT_SOURCE_ID) {
                noRedirectFetchClient
            } else {
                fetchClient
            }
            val call = client.newCall(request)
            coroutineScope {
                // A blocking response-body read does not observe coroutine
                // cancellation itself. This child is started immediately and
                // cancels the OkHttp call as soon as the parent is cancelled.
                val cancellationWatcher = launch(
                    context = Dispatchers.IO,
                    start = CoroutineStart.UNDISPATCHED,
                ) {
                    try {
                        awaitCancellation()
                    } finally {
                        call.cancel()
                    }
                }
                try {
                    call.execute().use { response ->
                if (!response.isSuccessful) {
                    return@use Result.failure(
                        IllegalStateException(
                            "fetch ${source.sourceId} failed: HTTP ${response.code} ${response.message}",
                        ),
                    )
                }
                val body = response.body ?: return@use Result.failure(
                    IllegalStateException("fetch ${source.sourceId} failed: empty body"),
                )
                val totalBytes = body.contentLength().coerceAtLeast(0L)

                // Stream body → file in 64 KB chunks. Okio's BufferedSink
                // gives us flush guarantees without us managing a manual
                // buffer; the per-chunk callback drives the progress UI.
                destination.parentFile?.mkdirs()
                destination.sink().buffer().use { sink ->
                    val bodySource = body.source()
                    var bytesRead = 0L
                    val buf = okio.Buffer()
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = bodySource.read(buf, 64 * 1024)
                        if (read == -1L) break
                        sink.write(buf, read)
                        bytesRead += read
                        onProgress(bytesRead, totalBytes)
                    }
                    sink.flush()
                }

                if (destination.length() == 0L) {
                    runCatching { if (destination.exists()) destination.delete() }
                    return@use Result.failure(
                        IllegalStateException("fetch ${source.sourceId} produced empty file"),
                    )
                }

                Result.success(destination)
                    }
                } finally {
                    cancellationWatcher.cancel()
                }
            }
        } catch (e: CancellationException) {
            runCatching { if (destination.exists()) destination.delete() }
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "fetch ${source.sourceId} threw: ${e.javaClass.simpleName}: ${e.message}")
            // Best-effort cleanup of any partial file so the caller's
            // fallback path doesn't accidentally treat a 0-byte temp
            // file as a successful download.
            runCatching { if (destination.exists()) destination.delete() }
            Result.failure(e)
        }
    }

    private companion object {
        const val TAG = "LosslessUrlDownloader"

        // Inter-byte stall timeout for the big-file fetch (was 30 s on the shared
        // client). A Hi-Res FLAC pull can stall past 30 s on a congested hotspot;
        // 90 s tolerates the pause without masking a truly dead socket.
        const val FETCH_STALL_TIMEOUT_SECONDS = 90L
        const val NO_REDIRECT_SOURCE_ID = "jiosaavn"
    }
}
