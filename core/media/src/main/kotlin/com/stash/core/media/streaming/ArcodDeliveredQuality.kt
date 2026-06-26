package com.stash.core.media.streaming

import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Derives the *delivered* lossless quality of an arcod stream from the catalog
 * maximums clamped to the requested Qobuz tier. The single stream GET returns
 * only a URL, so — per the deterministic Qobuz contract (delivered = master
 * clamped to the tier ceiling) — this reconstructs what was actually served for
 * Now Playing, with no extra network call.
 */
internal object ArcodDeliveredQuality {

    data class Delivered(val bitsPerSample: Int?, val sampleRateHz: Int?)

    /**
     * @param qobuzCode requested tier code (6=CD, 7=hi-res, 27=max; any other
     *   value is treated as 27 — matching the dev's "unknown defaults to 27").
     * @param maxBitDepth catalog `maximum_bit_depth` (null when absent).
     * @param maxSamplingRateKhz catalog `maximum_sampling_rate` in kHz (null when absent).
     */
    fun of(qobuzCode: Int, maxBitDepth: Int?, maxSamplingRateKhz: Double?): Delivered {
        val (ceilBits, ceilRateHz) = when (qobuzCode) {
            6 -> 16 to 44_100
            7 -> 24 to 96_000
            else -> 24 to 192_000 // 27 and any unknown code
        }
        val bits = maxBitDepth?.let { min(it, ceilBits) }
        val rate = maxSamplingRateKhz
            ?.let { (it * 1000).roundToInt() }
            ?.let { min(it, ceilRateHz) }
        return Delivered(bitsPerSample = bits, sampleRateHz = rate)
    }
}
