package com.stash.data.download

import android.content.Context
import android.media.MediaMetadataRetriever
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.stash.core.common.AppVersionProvider
import com.stash.core.model.Track
import com.stash.data.download.files.MetadataEmbedder
import com.yausername.ffmpeg.FFmpeg
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class MetadataEmbeddingIntegrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val versionProvider = object : AppVersionProvider {
        override val versionName: String = "0.9.35-test"
        override val versionCode: Int = 71
    }
    // Task 5 removed FileOrganizer from MetadataEmbedder's constructor —
    // only context + appVersion needed for the embed pass.
    private val embedder = MetadataEmbedder(context, versionProvider)

    private lateinit var artFile: File

    @Before fun setUp() {
        // MetadataEmbedder.resolveFfmpegBinary() expects the youtubedl-android
        // ffmpeg library to have already unpacked libffmpeg.zip.so into
        // nativeLibraryDir — which only happens after FFmpeg.init runs. In
        // the production app that's done at app start inside
        // YtDlpManager.initialize; instrumented tests run in their own
        // process with no such init, so we replicate it here. (YoutubeDL.init
        // is intentionally NOT called — its Python bootstrap fails in the
        // self-instrumenting library test APK, and the embedder doesn't need it.)
        FFmpeg.getInstance().init(context)
        artFile = copyAssetToCache("sample_art.jpg")
    }

    @Test fun embedsTagsAndArtIntoOpus() = runBlocking {
        // v0.9.38 (#95): Opus art is now embedded via METADATA_BLOCK_PICTURE
        // (base64-encoded FLAC picture block in the Vorbis comment), replacing
        // the prior attached_pic path that ffmpeg rejects for Ogg streams.
        // MediaMetadataRetriever.embeddedPicture should now return non-null.
        verifyRoundTrip("sample_opus.opus")
    }

    @Test fun embedsTagsAndArtIntoM4a() = runBlocking {
        verifyRoundTrip("sample_m4a.m4a")
    }

    @Test fun embedsTagsAndArtIntoFlac() = runBlocking {
        verifyRoundTrip("sample_flac.flac")
    }

    private suspend fun verifyRoundTrip(asset: String) {
        val source = copyAssetToCache(asset)
        val track = Track(
            id = 1, title = "Test Title", artist = "Test Artist",
            albumArtist = "Test AlbumArtist", album = "Test Album",
            isrc = "USTEST0000001",
        )

        val result = embedder.embedMetadata(source, track, artFile)

        MediaMetadataRetriever().apply {
            setDataSource(result.absolutePath)
            assertEquals("Test Title", extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE))
            assertEquals("Test Artist", extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST))
            assertEquals("Test Album", extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM))
            assertEquals("Test AlbumArtist", extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST))
            val art = embeddedPicture
            assertNotNull("Expected an embedded picture for $asset", art)
            assertTrue("Embedded picture should be non-empty for $asset", art!!.isNotEmpty())
            release()
        }
    }

    private fun copyAssetToCache(name: String): File {
        val target = File(context.cacheDir, "intgtest_$name")
        context.assets.open(name).use { input ->
            target.outputStream().use { input.copyTo(it) }
        }
        return target
    }
}
