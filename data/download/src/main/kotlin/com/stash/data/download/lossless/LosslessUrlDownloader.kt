package com.stash.data.download.lossless

import android.util.Log
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
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
    private val httpClient: OkHttpClient,
    private val decryptor: com.stash.data.download.lossless.amz.AmzDecryptor,
) {
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

        // Encrypted sources (amz CMAF): fetch the encrypted bytes into a
        // sibling `.enc` temp, then ffmpeg-decrypt into [destination]. ffmpeg
        // can't read and write the same path, hence the separate fetch target.
        val key = source.decryptionKey?.takeIf { it.isNotBlank() }
        val fetchTarget = if (key != null) File("${destination.absolutePath}.enc") else destination

        try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        IllegalStateException(
                            "fetch ${source.sourceId} failed: HTTP ${response.code} ${response.message}",
                        ),
                    )
                }
                val body = response.body ?: return@withContext Result.failure(
                    IllegalStateException("fetch ${source.sourceId} failed: empty body"),
                )
                val totalBytes = body.contentLength().coerceAtLeast(0L)

                // Stream body → file in 64 KB chunks. Okio's BufferedSink
                // gives us flush guarantees without us managing a manual
                // buffer; the per-chunk callback drives the progress UI.
                fetchTarget.parentFile?.mkdirs()
                fetchTarget.sink().buffer().use { sink ->
                    val bodySource = body.source()
                    var bytesRead = 0L
                    val buf = okio.Buffer()
                    while (true) {
                        val read = bodySource.read(buf, 64 * 1024)
                        if (read == -1L) break
                        sink.write(buf, read)
                        bytesRead += read
                        onProgress(bytesRead, totalBytes)
                    }
                    sink.flush()
                }

                if (fetchTarget.length() == 0L) {
                    runCatching { if (fetchTarget.exists()) fetchTarget.delete() }
                    return@withContext Result.failure(
                        IllegalStateException("fetch ${source.sourceId} produced empty file"),
                    )
                }

                if (key == null) {
                    return@use Result.success(fetchTarget)
                }

                // amz: decrypt the encrypted CMAF temp → clear FLAC at
                // [destination], then drop the encrypted temp. A decrypt
                // failure is a source failure (fall through to the next).
                val decrypted = decryptor.decryptToFlac(fetchTarget, key, destination)
                runCatching { if (fetchTarget.exists()) fetchTarget.delete() }
                if (decrypted && destination.length() > 0) {
                    Result.success(destination)
                } else {
                    runCatching { if (destination.exists()) destination.delete() }
                    Result.failure(
                        IllegalStateException("decrypt ${source.sourceId} failed for ${destination.name}"),
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "fetch ${source.sourceId} threw: ${e.javaClass.simpleName}: ${e.message}")
            // Best-effort cleanup of any partial files so the caller's
            // fallback path doesn't accidentally treat a 0-byte temp
            // file as a successful download.
            runCatching { if (fetchTarget.exists()) fetchTarget.delete() }
            runCatching { if (destination.exists()) destination.delete() }
            Result.failure(e)
        }
    }

    private companion object {
        const val TAG = "LosslessUrlDownloader"
    }
}
