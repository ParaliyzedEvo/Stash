package com.stash.core.media.streaming

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ArcodDeliveredQualityTest {

    @Test fun `MAX with 24 over 88_2 master delivers 24 over 88200`() {
        val d = ArcodDeliveredQuality.of(qobuzCode = 27, maxBitDepth = 24, maxSamplingRateKhz = 88.2)
        assertThat(d.bitsPerSample).isEqualTo(24)
        assertThat(d.sampleRateHz).isEqualTo(88_200)
    }

    @Test fun `MAX with CD-only master delivers 16 over 44100`() {
        val d = ArcodDeliveredQuality.of(qobuzCode = 27, maxBitDepth = 16, maxSamplingRateKhz = 44.1)
        assertThat(d.bitsPerSample).isEqualTo(16)
        assertThat(d.sampleRateHz).isEqualTo(44_100)
    }

    @Test fun `CD tier clamps a 24 over 192 master down to 16 over 44100`() {
        val d = ArcodDeliveredQuality.of(qobuzCode = 6, maxBitDepth = 24, maxSamplingRateKhz = 192.0)
        assertThat(d.bitsPerSample).isEqualTo(16)
        assertThat(d.sampleRateHz).isEqualTo(44_100)
    }

    @Test fun `HI_RES tier clamps rate to 96000 but keeps 24 bits`() {
        val d = ArcodDeliveredQuality.of(qobuzCode = 7, maxBitDepth = 24, maxSamplingRateKhz = 192.0)
        assertThat(d.bitsPerSample).isEqualTo(24)
        assertThat(d.sampleRateHz).isEqualTo(96_000)
    }

    @Test fun `null maxBitDepth yields null bits`() {
        val d = ArcodDeliveredQuality.of(qobuzCode = 27, maxBitDepth = null, maxSamplingRateKhz = 88.2)
        assertThat(d.bitsPerSample).isNull()
        assertThat(d.sampleRateHz).isEqualTo(88_200)
    }

    @Test fun `null maxSamplingRate yields null rate`() {
        val d = ArcodDeliveredQuality.of(qobuzCode = 27, maxBitDepth = 24, maxSamplingRateKhz = null)
        assertThat(d.bitsPerSample).isEqualTo(24)
        assertThat(d.sampleRateHz).isNull()
    }

    @Test fun `both null yields both null`() {
        val d = ArcodDeliveredQuality.of(qobuzCode = 27, maxBitDepth = null, maxSamplingRateKhz = null)
        assertThat(d.bitsPerSample).isNull()
        assertThat(d.sampleRateHz).isNull()
    }

    @Test fun `unknown code behaves like MAX with a 24 over 192 ceiling`() {
        val d = ArcodDeliveredQuality.of(qobuzCode = 0, maxBitDepth = 24, maxSamplingRateKhz = 192.0)
        assertThat(d.bitsPerSample).isEqualTo(24)
        assertThat(d.sampleRateHz).isEqualTo(192_000)
    }
}
