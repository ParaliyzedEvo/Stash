package com.stash.data.download.lossless

import com.google.common.truth.Truth.assertThat
import com.stash.data.download.lossless.qobuz.QobuzQuality
import org.junit.Test

class LosslessUrlInspectorTest {

    private val inspector = LosslessUrlInspector()

    // Quality codes: 5=MP3_320, 6=FLAC_CD, 7=FLAC_HIRES_96, 27=FLAC_HIRES_192

    @Test
    fun `preview sample with range marker is degraded`() {
        val url = "https://cdn.example/file?fmt=27&profile=raw&range=20-30&etsp=9999999999"
        assertThat(inspector.isDegraded(url, requestedQuality = QobuzQuality.FLAC_HIRES_192)).isTrue()
    }

    @Test
    fun `range marker is degraded regardless of requested quality`() {
        val url = "https://cdn.example/file?range=0-30&etsp=9999999999"
        assertThat(inspector.isDegraded(url, requestedQuality = QobuzQuality.FLAC_CD)).isTrue()
    }

    @Test
    fun `lossy fmt while lossless requested is degraded`() {
        val url = "https://cdn.example/file?fmt=5&profile=raw&etsp=9999999999"
        assertThat(inspector.isDegraded(url, requestedQuality = QobuzQuality.FLAC_HIRES_192)).isTrue()
    }

    @Test
    fun `lossy fmt when lossy requested is NOT degraded`() {
        // User explicitly chose MP3 tier — fmt=5 is what they asked for.
        val url = "https://cdn.example/file?fmt=5&profile=raw&etsp=9999999999"
        assertThat(inspector.isDegraded(url, requestedQuality = QobuzQuality.MP3_320)).isFalse()
    }

    @Test
    fun `healthy full flac url is NOT degraded`() {
        val url = "https://cdn.example/file?fmt=27&profile=raw&etsp=9999999999"
        assertThat(inspector.isDegraded(url, requestedQuality = QobuzQuality.FLAC_HIRES_192)).isFalse()
    }

    @Test
    fun `url without fmt param and no range is NOT degraded`() {
        // Defensive: an unrecognised but range-free URL is treated as healthy.
        val url = "https://cdn.example/file?profile=raw&etsp=9999999999"
        assertThat(inspector.isDegraded(url, requestedQuality = QobuzQuality.FLAC_HIRES_192)).isFalse()
    }

    @Test
    fun `range substring inside another value does not false-positive`() {
        // "arrange=..." must not match the range= marker.
        val url = "https://cdn.example/file?arrange=20-30&fmt=27&etsp=9999999999"
        assertThat(inspector.isDegraded(url, requestedQuality = QobuzQuality.FLAC_HIRES_192)).isFalse()
    }
}
