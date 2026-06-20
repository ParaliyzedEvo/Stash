package com.stash.data.download.lossless.amz

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Tests for [FlacStreamInfo.parse]: reads sample rate / channels / bit depth
 * out of a FLAC STREAMINFO metadata block. Used to label amz streams with their
 * real quality (24-bit / 96 kHz) instead of a bare "FLAC" — the amz `/api/track`
 * response carries `bitDepth: null`, so the decrypted file's STREAMINFO is the
 * source of truth.
 */
class FlacStreamInfoTest {

    /**
     * Builds a minimal FLAC header: "fLaC" + a STREAMINFO block (type 0, len 34)
     * whose packed sample-rate/channels/bits-per-sample triple encodes the given
     * values. Only the four bytes that hold that triple are set; the rest are 0.
     */
    private fun flacHeader(sampleRate: Int, channels: Int, bits: Int): ByteArray {
        val h = ByteArray(8 + 34)
        h[0] = 'f'.code.toByte(); h[1] = 'L'.code.toByte(); h[2] = 'a'.code.toByte(); h[3] = 'C'.code.toByte()
        h[4] = 0x00            // last-block flag clear, block type 0 (STREAMINFO)
        h[5] = 0x00; h[6] = 0x00; h[7] = 0x22 // block length = 34
        // STREAMINFO data starts at offset 8; the triple lives at STREAMINFO
        // bytes 10..13 == file offsets 18..21.
        val sr = sampleRate
        h[18] = ((sr ushr 12) and 0xFF).toByte()
        h[19] = ((sr ushr 4) and 0xFF).toByte()
        val ch = (channels - 1) and 0x07
        val b = (bits - 1) and 0x1F
        h[20] = (((sr and 0x0F) shl 4) or (ch shl 1) or ((b ushr 4) and 0x01)).toByte()
        h[21] = (((b and 0x0F) shl 4)).toByte() // top 4 bits of totalSamples = 0
        return h
    }

    @Test fun `parses CD-quality 16-bit 44_1kHz stereo`() {
        val info = FlacStreamInfo.parse(flacHeader(44100, 2, 16))
        assertThat(info).isNotNull()
        assertThat(info!!.sampleRateHz).isEqualTo(44100)
        assertThat(info.channels).isEqualTo(2)
        assertThat(info.bitsPerSample).isEqualTo(16)
    }

    @Test fun `parses hi-res 24-bit 96kHz stereo`() {
        val info = FlacStreamInfo.parse(flacHeader(96000, 2, 24))
        assertThat(info!!.sampleRateHz).isEqualTo(96000)
        assertThat(info.bitsPerSample).isEqualTo(24)
    }

    @Test fun `parses 24-bit 192kHz`() {
        val info = FlacStreamInfo.parse(flacHeader(192000, 2, 24))
        assertThat(info!!.sampleRateHz).isEqualTo(192000)
        assertThat(info.bitsPerSample).isEqualTo(24)
    }

    @Test fun `returns null when the fLaC magic is missing`() {
        val bad = flacHeader(44100, 2, 16).copyOf().also { it[0] = 'X'.code.toByte() }
        assertThat(FlacStreamInfo.parse(bad)).isNull()
    }

    @Test fun `returns null when the header is too short`() {
        assertThat(FlacStreamInfo.parse(ByteArray(10))).isNull()
    }
}
