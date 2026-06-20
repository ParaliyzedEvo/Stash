package com.stash.data.download.lossless.amz

import android.util.Log
import java.io.File

/**
 * The audio format read out of a FLAC STREAMINFO metadata block.
 */
data class FlacFormat(
    val sampleRateHz: Int,
    val channels: Int,
    val bitsPerSample: Int,
)

/**
 * Minimal reader for a FLAC file's STREAMINFO block — enough to label a stream
 * with its real quality (sample rate + bit depth). amz's `/api/track` reports
 * `bitDepth: null`, so once we've decrypted the FLAC its STREAMINFO is the
 * authoritative source.
 *
 * Layout (per the FLAC spec): a 4-byte `fLaC` magic, then metadata blocks. The
 * first block is always STREAMINFO (a 4-byte block header — last-block flag +
 * 7-bit type 0 + 24-bit length — followed by 34 bytes of data). The packed
 * sample-rate (20 bits) / channels (3 bits, +1) / bits-per-sample (5 bits, +1)
 * triple sits at STREAMINFO bytes 10..13, i.e. file offsets 18..21.
 */
object FlacStreamInfo {

    private const val TAG = "FlacStreamInfo"

    /** Reads the first bytes of [file] and parses STREAMINFO; null on failure. */
    fun read(file: File): FlacFormat? = runCatching {
        val head = ByteArray(MIN_HEADER_BYTES)
        file.inputStream().use { stream ->
            var off = 0
            while (off < head.size) {
                val n = stream.read(head, off, head.size - off)
                if (n < 0) break
                off += n
            }
            if (off < head.size) return@runCatching null
        }
        parse(head)
    }.getOrElse { e ->
        Log.w(TAG, "FLAC STREAMINFO read failed for ${file.name}: ${e.message}")
        null
    }

    /** Parses STREAMINFO out of a buffer that starts at the `fLaC` magic. */
    fun parse(header: ByteArray): FlacFormat? {
        if (header.size < MIN_HEADER_BYTES) return null
        if (header[0] != 'f'.code.toByte() || header[1] != 'L'.code.toByte() ||
            header[2] != 'a'.code.toByte() || header[3] != 'C'.code.toByte()
        ) {
            return null
        }
        // Block type (low 7 bits of byte 4) must be 0 == STREAMINFO.
        if ((header[4].toInt() and 0x7F) != 0) return null

        val b18 = header[18].toInt() and 0xFF
        val b19 = header[19].toInt() and 0xFF
        val b20 = header[20].toInt() and 0xFF
        val b21 = header[21].toInt() and 0xFF

        val sampleRate = (b18 shl 12) or (b19 shl 4) or (b20 ushr 4)
        val channels = ((b20 ushr 1) and 0x07) + 1
        val bitsPerSample = ((((b20 and 0x01) shl 4) or (b21 ushr 4)) and 0x1F) + 1

        if (sampleRate <= 0) return null
        return FlacFormat(sampleRateHz = sampleRate, channels = channels, bitsPerSample = bitsPerSample)
    }

    /** Bytes needed to reach the sample-rate/bits triple (file offsets 18..21). */
    private const val MIN_HEADER_BYTES = 22
}
