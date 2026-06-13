package com.stash.data.download.preview

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test

/**
 * Unit tests for [PreviewUrlExtractor.selectBestAudioUrl].
 *
 * YouTube's best free audio is Opus itag 251 (~160k). Opus sounds better
 * than AAC at equal/lower bitrate, so selection must prefer Opus over AAC
 * even when the AAC stream reports a higher raw bitrate.
 */
class PreviewFormatSelectionTest {

    private fun format(mimeType: String, bitrate: Long, url: String?): JsonObject =
        buildJsonObject {
            put("mimeType", mimeType)
            put("bitrate", bitrate)
            if (url != null) put("url", url)
        }

    private val opusMime = "audio/webm; codecs=\"opus\""
    private val aacMime = "audio/mp4; codecs=\"mp4a.40.2\""

    @Test
    fun prefers_opus_over_equal_or_higher_aac() {
        val formats = listOf(
            format(aacMime, 132000, "aac-url"),
            format(opusMime, 130000, "opus-url"),
        )
        assertThat(PreviewUrlExtractor.selectBestAudioUrl(formats)).isEqualTo("opus-url")
    }

    @Test
    fun falls_back_to_highest_aac_when_no_opus() {
        val formats = listOf(
            format(aacMime, 50000, "lo"),
            format(aacMime, 132000, "hi"),
        )
        assertThat(PreviewUrlExtractor.selectBestAudioUrl(formats)).isEqualTo("hi")
    }

    @Test
    fun picks_highest_bitrate_opus_when_multiple_opus() {
        val formats = listOf(
            format(opusMime, 70000, "mid"),
            format(opusMime, 160000, "best"),
            format(opusMime, 57000, "lo"),
        )
        assertThat(PreviewUrlExtractor.selectBestAudioUrl(formats)).isEqualTo("best")
    }

    @Test
    fun ignores_formats_without_direct_url() {
        val formats = listOf(
            format(opusMime, 160000, null),
        )
        assertThat(PreviewUrlExtractor.selectBestAudioUrl(formats)).isNull()
    }

    @Test
    fun returns_null_for_empty() {
        assertThat(PreviewUrlExtractor.selectBestAudioUrl(emptyList())).isNull()
    }
}
