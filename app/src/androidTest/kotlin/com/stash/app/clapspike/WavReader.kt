package com.stash.app.clapspike

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * SPIKE (spike/clap-on-device) — throwaway. Minimal 48 kHz mono PCM16 WAV
 * reader for the CLAP measurement fixtures, which are produced by
 * `ffmpeg ... -ac 1 -ar 48000 -f wav`. Not a general WAV parser: it assumes
 * 16-bit signed little-endian mono samples, but it DOES walk the RIFF chunk
 * list to find the `data` chunk rather than trusting a fixed 44-byte header
 * (ffmpeg may emit an extra `LIST`/`fact` chunk before `data`).
 */
object WavReader {
    fun read(input: InputStream): FloatArray {
        val bytes = input.readBytes()
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        // RIFF header: "RIFF" <u32 size> "WAVE", then a sequence of chunks.
        require(bytes.size >= 12) { "WAV too short" }
        require(bytes[0] == 'R'.code.toByte() && bytes[3] == 'F'.code.toByte()) { "Not a RIFF file" }
        var pos = 12 // skip "RIFF" + size + "WAVE"

        // Walk chunks: each is <4-byte id><u32 size><payload>, payload padded to even length.
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.US_ASCII)
            val size = buf.getInt(pos + 4)
            val payloadStart = pos + 8
            if (id == "data") {
                val sampleCount = size / 2 // 16-bit samples
                val out = FloatArray(sampleCount)
                val sample = ByteBuffer.wrap(bytes, payloadStart, size).order(ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until sampleCount) {
                    out[i] = sample.short / 32768f // normalize PCM16 -> [-1, 1)
                }
                return out
            }
            // Advance past payload (+1 to honor word-alignment padding).
            pos = payloadStart + size + (size and 1)
        }
        throw IllegalArgumentException("No 'data' chunk found in WAV")
    }
}
