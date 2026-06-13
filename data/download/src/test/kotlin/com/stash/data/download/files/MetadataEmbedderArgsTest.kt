package com.stash.data.download.files

import com.stash.core.common.AppVersionProvider
import com.stash.core.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MetadataEmbedderArgsTest {

    private val versionProvider = object : AppVersionProvider {
        override val versionName: String = "0.9.35"
        override val versionCode: Int = 71
    }

    @Test fun `writes ALBUMARTIST + album_artist when track has albumArtist`() {
        val args = MetadataEmbedder.buildFfmpegArgs(
            audioFile = File("/tmp/in.opus"),
            outputFile = File("/tmp/out.opus"),
            track = Track(
                id = 1, title = "T", artist = "Drake, 21 Savage",
                albumArtist = "Drake",
            ),
            albumArtFile = null,
            appVersion = versionProvider,
        )
        assertTrue("ALBUMARTIST=Drake" in args.zipMetadataValues())
        assertTrue("album_artist=Drake" in args.zipMetadataValues())
    }

    @Test fun `falls back to artist when albumArtist is blank`() {
        val args = MetadataEmbedder.buildFfmpegArgs(
            audioFile = File("/tmp/in.opus"),
            outputFile = File("/tmp/out.opus"),
            track = Track(
                id = 1, title = "T", artist = "Drake",
                albumArtist = "",
            ),
            albumArtFile = null,
            appVersion = versionProvider,
        )
        assertTrue("ALBUMARTIST=Drake" in args.zipMetadataValues())
    }

    @Test fun `writes ISRC when present`() {
        val args = MetadataEmbedder.buildFfmpegArgs(
            audioFile = File("/tmp/in.opus"),
            outputFile = File("/tmp/out.opus"),
            track = Track(id = 1, title = "T", artist = "A", isrc = "USRC17607839"),
            albumArtFile = null,
            appVersion = versionProvider,
        )
        assertTrue("ISRC=USRC17607839" in args.zipMetadataValues())
    }

    @Test fun `omits ISRC when blank or null`() {
        val args = MetadataEmbedder.buildFfmpegArgs(
            audioFile = File("/tmp/in.opus"),
            outputFile = File("/tmp/out.opus"),
            track = Track(id = 1, title = "T", artist = "A", isrc = null),
            albumArtFile = null,
            appVersion = versionProvider,
        )
        assertFalse(args.zipMetadataValues().any { it.startsWith("ISRC=") })
    }

    @Test fun `writes ENCODER with versionName`() {
        val args = MetadataEmbedder.buildFfmpegArgs(
            audioFile = File("/tmp/in.opus"),
            outputFile = File("/tmp/out.opus"),
            track = Track(id = 1, title = "T", artist = "A"),
            albumArtFile = null,
            appVersion = versionProvider,
        )
        assertTrue("ENCODER=Stash 0.9.35" in args.zipMetadataValues())
    }

    @Test fun `attaches art when albumArtFile is non-null and exists`() {
        // Uses .m4a here — the Opus/Ogg gate (see MetadataEmbedder
        // OPUS_OGG_EXTENSIONS) intentionally skips attached_pic for
        // those codecs. M4A/FLAC/MP3 keep full art treatment.
        val art = File.createTempFile("art", ".jpg").apply { writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte())) }
        val args = MetadataEmbedder.buildFfmpegArgs(
            audioFile = File("/tmp/in.m4a"),
            outputFile = File("/tmp/out.m4a"),
            track = Track(id = 1, title = "T", artist = "A"),
            albumArtFile = art,
            appVersion = versionProvider,
        )
        assertTrue(args.contains("-disposition:v:0"))
        assertTrue(args.contains("attached_pic"))
        assertTrue(args.contains(art.absolutePath))
        art.delete()
    }

    @Test fun `skips attached_pic for opus output`() {
        val art = File.createTempFile("art", ".jpg").apply { writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte())) }
        val args = MetadataEmbedder.buildFfmpegArgs(
            audioFile = File("/tmp/in.opus"),
            outputFile = File("/tmp/out.opus"),
            track = Track(id = 1, title = "T", artist = "A"),
            albumArtFile = art,
            appVersion = versionProvider,
        )
        assertFalse(args.contains("attached_pic"))
        assertFalse(args.contains(art.absolutePath))
        // Tags should still be written
        assertTrue("title=T" in args.zipMetadataValues())
        art.delete()
    }

    /**
     * Locks in the Opus cover-art strategy from rawnaldclark/Stash#95:
     * the argv MUST carry a `METADATA_BLOCK_PICTURE=` Vorbis comment and
     * MUST NOT use the `attached_pic` mux path (which fails Ogg with
     * exit 234), and MUST NOT add a second `-i artFile` input. Companion
     * test below confirms the M4A branch was not regressed.
     */
    @Test fun `opus argv embeds METADATA_BLOCK_PICTURE and skips attached_pic + second input`() {
        val art = File.createTempFile("art", ".jpg").apply {
            writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
        }
        val args = MetadataEmbedder.buildFfmpegArgs(
            audioFile = File("/tmp/in.opus"),
            outputFile = File("/tmp/out.opus"),
            track = Track(id = 1, title = "T", artist = "A"),
            albumArtFile = art,
            appVersion = versionProvider,
        )

        // Positive: METADATA_BLOCK_PICTURE Vorbis comment is present.
        assertTrue(
            "Opus argv must carry a METADATA_BLOCK_PICTURE Vorbis comment",
            args.zipMetadataValues().any { it.startsWith("METADATA_BLOCK_PICTURE=") },
        )

        // Negative: no attached_pic disposition (would fail Ogg mux exit 234).
        assertFalse(
            "Opus argv must NOT use attached_pic disposition",
            args.contains("attached_pic"),
        )
        assertFalse(
            "Opus argv must NOT use -disposition:v:0",
            args.contains("-disposition:v:0"),
        )

        // Negative: no second `-i artFile` input added.
        assertFalse(
            "Opus argv must NOT add the artFile as a second -i input",
            args.contains(art.absolutePath),
        )
        // Exactly one `-i` (the audio input).
        assertTrue(
            "Opus argv must have exactly one -i flag (audio input only)",
            args.count { it == "-i" } == 1,
        )

        art.delete()
    }

    @Test fun `m4a argv still uses attached_pic and skips METADATA_BLOCK_PICTURE`() {
        val art = File.createTempFile("art", ".jpg").apply {
            writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
        }
        val args = MetadataEmbedder.buildFfmpegArgs(
            audioFile = File("/tmp/in.m4a"),
            outputFile = File("/tmp/out.m4a"),
            track = Track(id = 1, title = "T", artist = "A"),
            albumArtFile = art,
            appVersion = versionProvider,
        )

        // M4A keeps the attached_pic stream-mapping path.
        assertTrue("M4A must still use attached_pic", args.contains("attached_pic"))
        assertTrue("M4A must still set -disposition:v:0", args.contains("-disposition:v:0"))
        assertTrue("M4A must still pass the artFile via a second -i", args.contains(art.absolutePath))

        // M4A must NOT also emit METADATA_BLOCK_PICTURE (Vorbis-only).
        assertFalse(
            "M4A must NOT emit METADATA_BLOCK_PICTURE",
            args.zipMetadataValues().any { it.startsWith("METADATA_BLOCK_PICTURE=") },
        )

        art.delete()
    }

    @Test fun `skips attached_pic for ogg output`() {
        val art = File.createTempFile("art", ".jpg").apply { writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte())) }
        val args = MetadataEmbedder.buildFfmpegArgs(
            audioFile = File("/tmp/in.ogg"),
            outputFile = File("/tmp/out.ogg"),
            track = Track(id = 1, title = "T", artist = "A"),
            albumArtFile = art,
            appVersion = versionProvider,
        )
        assertFalse(args.contains("attached_pic"))
        art.delete()
    }

    /**
     * Issue #118: when the caller pre-built a sidecar ffmetadata file
     * (because the base64 picture block exceeded
     * MAX_INLINE_PICTURE_BASE64_BYTES), argv must read the picture
     * via `-i <metafile> -map_metadata 1` and MUST NOT also inline
     * the same `METADATA_BLOCK_PICTURE=...` value — that would
     * recreate exactly the argv-overflow the fallback is preventing.
     */
    @Test fun `opus argv uses ffmetadata file when supplied and skips inline picture`() {
        val art = File.createTempFile("art", ".jpg").apply {
            writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
        }
        val metaFile = File.createTempFile("picmeta", ".ffmetadata").apply {
            writeText(";FFMETADATA1\nMETADATA_BLOCK_PICTURE=AAAA\n")
        }

        val args = MetadataEmbedder.buildFfmpegArgs(
            audioFile = File("/tmp/in.opus"),
            outputFile = File("/tmp/out.opus"),
            track = Track(id = 1, title = "T", artist = "A"),
            albumArtFile = art,
            appVersion = versionProvider,
            opusPictureMetadataFile = metaFile,
        )

        // The sidecar file is wired in as the second input...
        assertTrue(
            "ffmetadata sidecar must be passed as -i input",
            args.contains(metaFile.absolutePath),
        )
        // ...and its global tags are pulled in via -map_metadata 1.
        val mapMetaIdx = args.indexOf("-map_metadata")
        assertTrue("argv must include -map_metadata", mapMetaIdx >= 0)
        assertEquals("1", args[mapMetaIdx + 1])

        // Inline METADATA_BLOCK_PICTURE MUST be absent — duplicating it
        // would defeat the fallback.
        assertFalse(
            "Inline METADATA_BLOCK_PICTURE must be skipped when sidecar file is used",
            args.zipMetadataValues().any { it.startsWith("METADATA_BLOCK_PICTURE=") },
        )

        // Non-picture tags still go through the inline -metadata path.
        assertTrue("title=T" in args.zipMetadataValues())
        assertTrue("artist=A" in args.zipMetadataValues())

        art.delete()
        metaFile.delete()
    }

    @Test fun `opus argv inlines METADATA_BLOCK_PICTURE when no sidecar supplied`() {
        // Regression guard: opusPictureMetadataFile=null (the small-art
        // path) must NOT add any extra -i / -map_metadata flags.
        val art = File.createTempFile("art", ".jpg").apply {
            writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
        }
        val args = MetadataEmbedder.buildFfmpegArgs(
            audioFile = File("/tmp/in.opus"),
            outputFile = File("/tmp/out.opus"),
            track = Track(id = 1, title = "T", artist = "A"),
            albumArtFile = art,
            appVersion = versionProvider,
            opusPictureMetadataFile = null,
        )

        assertTrue(
            "Inline METADATA_BLOCK_PICTURE must be present on the no-sidecar path",
            args.zipMetadataValues().any { it.startsWith("METADATA_BLOCK_PICTURE=") },
        )
        assertFalse("No -map_metadata when no sidecar", args.contains("-map_metadata"))
        // Exactly one -i (audio only).
        assertTrue("Exactly one -i flag", args.count { it == "-i" } == 1)
        art.delete()
    }

    @Test fun `sanitises control characters from values`() {
        val args = MetadataEmbedder.buildFfmpegArgs(
            audioFile = File("/tmp/in.opus"),
            outputFile = File("/tmp/out.opus"),
            track = Track(id = 1, title = "Hello\u0000World", artist = "Evil\u0001\u001F"),
            albumArtFile = null,
            appVersion = versionProvider,
        )
        assertTrue("title=HelloWorld" in args.zipMetadataValues())
        assertTrue("artist=Evil" in args.zipMetadataValues())
    }

    // Pairs every `-metadata` flag with the value that follows it.
    private fun List<String>.zipMetadataValues(): List<String> {
        val result = mutableListOf<String>()
        var i = 0
        while (i < size - 1) {
            if (this[i] == "-metadata") result.add(this[i + 1])
            i++
        }
        return result
    }
}
