package com.stash.data.download.lossless.amz

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test

/**
 * Parser tests for the amz.squid.wtf wire models against REAL captured
 * responses. The lenient Json must tolerate the many unmodelled fields
 * (lyrics/composer/copyright/…) that the live API returns.
 */
class AmzApiModelsTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Test fun `parses captured search response trackList`() {
        val response = json.decodeFromString<AmzSearchResponse>(SEARCH_JSON)

        assertThat(response.trackList).hasSize(1)
        val item = response.trackList[0]
        assertThat(item.asin).isEqualTo("B07NHH5X4P")
        assertThat(item.title).isEqualTo("Can't Tell Me Nothing [Explicit]")
        assertThat(item.primaryArtistName).isEqualTo("Kanye West")
        assertThat(item.albumArtistName).isEqualTo("Kanye West")
        assertThat(item.album?.image)
            .isEqualTo("https://m.media-amazon.com/images/I/710XWtyJ+8L.jpg")
    }

    @Test fun `parses captured track response metadata ignoring unknown keys`() {
        val response = json.decodeFromString<AmzTrackResponse>(TRACK_JSON)

        val meta = response.metadata
        assertThat(meta).isNotNull()
        assertThat(meta!!.asin).isEqualTo("B07K7VJXVG")
        assertThat(meta.title).isEqualTo("Ghost Town [feat. PARTYNEXTDOOR] [Explicit]")
        assertThat(meta.artist).isEqualTo("Kanye West feat. PARTYNEXTDOOR")
        assertThat(meta.album).isEqualTo("ye [Explicit]")
        assertThat(meta.albumArtist).isEqualTo("Kanye West feat. PARTYNEXTDOOR")
        assertThat(meta.isrc).isEqualTo("USUM71807761")
        assertThat(meta.coverCdn).isNotNull()
        assertThat(meta.isExplicit).isTrue()
    }

    @Test fun `defaults are safe when fields are absent`() {
        val response = json.decodeFromString<AmzSearchResponse>("""{}""")
        assertThat(response.trackList).isEmpty()

        val track = json.decodeFromString<AmzTrackResponse>("""{}""")
        assertThat(track.metadata).isNull()
    }

    companion object {
        private const val SEARCH_JSON = """
        {"trackList":[{"asin":"B07NHH5X4P","title":"Can't Tell Me Nothing [Explicit]",
        "primaryArtistName":"Kanye West","artistName":"Kanye West","albumArtistName":"Kanye West",
        "album":{"title":"","image":"https://m.media-amazon.com/images/I/710XWtyJ+8L.jpg"}}]}
        """

        private const val TRACK_JSON = """
        {"metadata":{"asin":"B07K7VJXVG","title":"Ghost Town [feat. PARTYNEXTDOOR] [Explicit]",
        "artist":"Kanye West feat. PARTYNEXTDOOR","album":"ye [Explicit]","album_asin":"B07K7WZSQZ",
        "album_artist":"Kanye West feat. PARTYNEXTDOOR",
        "cover":"https://m.media-amazon.com/images/I/714yVKHQM-L.SX1200_QL90.jpg",
        "cover_cdn":"https://m.media-amazon.com/images/I/714yVKHQM-L.SX1200_QL90.jpg",
        "cover_candidates":["https://m.media-amazon.com/images/I/714yVKHQM-L.SX1200_QL90.jpg"],
        "year":"2018","date":"2018-06-01","track_number":"6","disc_number":"1","disc_total":"1",
        "track_total":"7","genre":"Rap & Hip-Hop","composer":"someone","copyright":"label",
        "label":"GOOD Music","isrc":"USUM71807761","is_explicit":true,
        "lyrics":{"synced":"[00:00.00]intro"}}}
        """
    }
}
