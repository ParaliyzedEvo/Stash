package com.stash.data.download.lossless

import com.stash.data.download.lossless.qobuz.QobuzQuality
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pure classifier over a resolved Qobuz-proxy CDN URL. A degraded source
 * does NOT error — it returns a signed URL for a 30-second preview
 * (`…&range=20-30&…`) or a lossy downgrade (`fmt=5` MP3 when FLAC was
 * requested). Both look like success to the download/stream pipeline, so
 * failover never fires. This classifier turns "degraded" into a detectable
 * signal the source layer can reject. No I/O — fully fixture-testable.
 *
 * See docs/superpowers/specs/2026-06-05-lossless-degradation-detection-design.md
 */
@Singleton
class LosslessUrlInspector @Inject constructor() {

    /**
     * True when [url] is a preview sample (primary signal) or a lossy
     * downgrade relative to [requestedQuality] (secondary signal).
     *
     * @param requestedQuality the Qobuz format_id the app asked for
     *   ([QobuzQuality], e.g. 27 = hi-res FLAC). Pass the same value handed
     *   to `getFileUrl`.
     */
    fun isDegraded(url: String, requestedQuality: Int): Boolean =
        isPreviewSample(url) || isDowngraded(url, requestedQuality)

    /** A `range=<start>-<end>` window marker — the decisive preview signal. */
    fun isPreviewSample(url: String): Boolean = RANGE_REGEX.containsMatchIn(url)

    /**
     * URL serves a lossy `fmt` while a lossless tier was requested — the
     * backing account lost lossless entitlement. No-op when the user
     * deliberately requested a lossy tier (then `fmt=5` is expected).
     */
    fun isDowngraded(url: String, requestedQuality: Int): Boolean {
        if (requestedQuality in LOSSY_CODES) return false
        val servedFmt = FMT_REGEX.find(url)?.groupValues?.get(1)?.toIntOrNull() ?: return false
        return servedFmt in LOSSY_CODES
    }

    private companion object {
        /** Lossy Qobuz format_ids. Today only MP3 320. */
        val LOSSY_CODES = setOf(QobuzQuality.MP3_320)
        val RANGE_REGEX = Regex("""[?&]range=\d+-\d+""")
        val FMT_REGEX = Regex("""[?&]fmt=(\d+)""")
    }
}
