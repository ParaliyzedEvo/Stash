package com.stash.core.media.service

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.media.service.StashPlaybackService.Companion.EXTRA_TRACK_ID
import com.stash.core.media.service.StashPlaybackService.Companion.EXTRA_TRACK_IS_STREAMABLE
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The Android Auto browse tree can't be manually tested here (no AA
 * hardware, dated DHU), so these tests pin the exact logic the car sees:
 * which tracks appear/play, and what URI they carry.
 *
 * The original bug: children were gated on the BARE `is_streamable` flag,
 * which defaults to 0 meaning "not checked yet" — so every synced,
 * not-yet-downloaded row vanished ("playlist opens empty in the car") and
 * the survivors carried `filePath ?: ""` empty URIs that errored at play.
 */
@RunWith(RobolectricTestRunner::class)
class AutoBrowseTest {

    private fun track(
        id: Long = 1L,
        downloaded: Boolean = false,
        streamable: Boolean = false,
        checkedAt: Long? = null,
        filePath: String? = null,
    ) = TrackEntity(
        id = id,
        title = "Song",
        artist = "Artist",
        isDownloaded = downloaded,
        isStreamable = streamable,
        isStreamableCheckedAt = checkedAt,
        filePath = filePath,
        youtubeId = "vid$id",
        durationMs = 200_000L,
    )

    // ---- isPlayableInAuto: the truth table from Track.isUnavailableForDisplay ----

    @Test
    fun `downloaded track is playable`() {
        assertThat(track(downloaded = true, filePath = "/m/a.flac").isPlayableInAuto()).isTrue()
    }

    @Test
    fun `confirmed-streamable track is playable`() {
        assertThat(track(streamable = true, checkedAt = 123L).isPlayableInAuto()).isTrue()
    }

    @Test
    fun `never-checked synced track is playable - the empty-playlist bug`() {
        // is_streamable=0 + checked_at=null means "unknown", NOT "unplayable".
        // The bare-flag filter dropped exactly these rows.
        assertThat(track(streamable = false, checkedAt = null).isPlayableInAuto()).isTrue()
    }

    @Test
    fun `confirmed-unstreamable undownloaded track is excluded`() {
        assertThat(track(streamable = false, checkedAt = 123L).isPlayableInAuto()).isFalse()
    }

    // ---- autoPlaybackUri: never an empty URI ----

    @Test
    fun `downloaded track gets its file uri`() {
        val uri = track(downloaded = true, filePath = "/music/a.flac").autoPlaybackUri()
        assertThat(uri.toString()).isEqualTo("file:///music/a.flac")
    }

    @Test
    fun `stream track gets a stash-resolve placeholder carrying resolver inputs`() {
        val uri = track(id = 42L).autoPlaybackUri()
        assertThat(uri.scheme).isEqualTo("stash-resolve")
        assertThat(uri.lastPathSegment).isEqualTo("42")
        assertThat(uri.getQueryParameter("yt")).isEqualTo("vid42")
        assertThat(uri.getQueryParameter("t")).isEqualTo("Song")
        assertThat(uri.getQueryParameter("a")).isEqualTo("Artist")
    }

    @Test
    fun `downloaded row with a missing path falls back to the placeholder not an empty uri`() {
        val uri = track(id = 7L, downloaded = true, filePath = null).autoPlaybackUri()
        assertThat(uri.scheme).isEqualTo("stash-resolve")
    }

    // ---- toAutoMediaItem: identity extras + playable flags ----

    @Test
    fun `auto item carries track id, playable flag, and streaming marker`() {
        val item = track(id = 9L).toAutoMediaItem(mediaId = "AUTOQ_p1_9")

        assertThat(item.mediaId).isEqualTo("AUTOQ_p1_9")
        assertThat(item.mediaMetadata.isPlayable).isTrue()
        assertThat(item.mediaMetadata.isBrowsable).isFalse()
        val extras = item.mediaMetadata.extras!!
        assertThat(extras.getLong(EXTRA_TRACK_ID)).isEqualTo(9L)
        assertThat(extras.getBoolean(EXTRA_TRACK_IS_STREAMABLE)).isTrue()
        assertThat(item.localConfiguration?.uri?.scheme).isEqualTo("stash-resolve")
    }

    @Test
    fun `downloaded auto item is marked non-streaming`() {
        val item = track(downloaded = true, filePath = "/m/b.flac").toAutoMediaItem()
        assertThat(item.mediaMetadata.extras!!.getBoolean(EXTRA_TRACK_IS_STREAMABLE)).isFalse()
        assertThat(item.localConfiguration?.uri?.toString()).isEqualTo("file:///m/b.flac")
    }
}
