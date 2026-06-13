package com.stash.core.media.service

import android.net.Uri
import androidx.media3.cast.MediaItemConverter
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaMetadata as CastMediaMetadata
import com.google.android.gms.cast.MediaQueueItem
import com.google.android.gms.common.images.WebImage

/**
 * Custom [MediaItemConverter] that preserves Media3 metadata (title, artist,
 * album, artwork) when converting [MediaItem]s for the Cast receiver.
 *
 * The default [androidx.media3.cast.DefaultMediaItemConverter]:
 * - Strips [MediaMetadata] (title, artist, artwork) → Cast shows blank info
 * - Defaults to `MEDIA_TYPE_MOVIE` when MIME type is missing → wrong UI
 * - Doesn't carry `mediaId` → post-Cast transitions lose track identity
 *
 * This converter:
 * 1. Maps title/artist/album/artwork into [CastMediaMetadata.MEDIA_TYPE_MUSIC_TRACK]
 * 2. Sets the content URL and a sensible MIME type (defaulting to audio/mpeg)
 * 3. Preserves `mediaId` round-trip via [MediaInfo.setContentId]
 * 4. Attaches artwork as a [WebImage] so the Cast receiver renders cover art
 */
class StashMediaItemConverter : MediaItemConverter {

    override fun toMediaQueueItem(mediaItem: MediaItem): MediaQueueItem {
        val metadata = mediaItem.mediaMetadata
        val uri = mediaItem.localConfiguration?.uri?.toString().orEmpty()
        val mimeType = mediaItem.localConfiguration?.mimeType
            ?: guessMimeType(uri)
            ?: MimeTypes.AUDIO_MPEG

        // Build Cast-side metadata with music-track type
        val castMetadata = CastMediaMetadata(CastMediaMetadata.MEDIA_TYPE_MUSIC_TRACK).apply {
            metadata.title?.let { putString(CastMediaMetadata.KEY_TITLE, it.toString()) }
            metadata.artist?.let { putString(CastMediaMetadata.KEY_ARTIST, it.toString()) }
            metadata.albumTitle?.let { putString(CastMediaMetadata.KEY_ALBUM_TITLE, it.toString()) }

            // Attach artwork — Cast shows this on the receiver and the
            // Google Home app. Only HTTP(S) URLs work; local file:// URIs
            // can't be fetched by the Cast device.
            metadata.artworkUri?.let { artUri ->
                val artStr = artUri.toString()
                if (artStr.startsWith("http://") || artStr.startsWith("https://")) {
                    addImage(WebImage(artUri))
                }
            }
        }

        // MediaInfo.Builder constructor takes the content ID (URL).
        // Use the stream URL as content URL; mediaId is embedded in metadata
        // and recovered in toMediaItem() via the content ID.
        val contentId = mediaItem.mediaId.ifBlank { uri }
        val mediaInfo = MediaInfo.Builder(contentId)
            .setContentUrl(uri)
            .setContentType(mimeType)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setMetadata(castMetadata)
            .build()

        return MediaQueueItem.Builder(mediaInfo).build()
    }

    override fun toMediaItem(mediaQueueItem: MediaQueueItem): MediaItem {
        val info = mediaQueueItem.media ?: return MediaItem.EMPTY
        val castMetadata = info.metadata
        val uri = info.contentUrl ?: info.contentId ?: ""
        val mediaId = info.contentId ?: ""

        val metadataBuilder = MediaMetadata.Builder()
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)

        castMetadata?.let { meta ->
            meta.getString(CastMediaMetadata.KEY_TITLE)?.let {
                metadataBuilder.setTitle(it)
            }
            meta.getString(CastMediaMetadata.KEY_ARTIST)?.let {
                metadataBuilder.setArtist(it)
            }
            meta.getString(CastMediaMetadata.KEY_ALBUM_TITLE)?.let {
                metadataBuilder.setAlbumTitle(it)
            }
            if (meta.images.isNotEmpty()) {
                metadataBuilder.setArtworkUri(meta.images[0].url)
            }
        }

        return MediaItem.Builder()
            .setMediaId(mediaId)
            .setUri(Uri.parse(uri))
            .setMimeType(info.contentType ?: MimeTypes.AUDIO_MPEG)
            .setMediaMetadata(metadataBuilder.build())
            .build()
    }

    private fun guessMimeType(url: String): String? {
        val lower = url.lowercase()
        return when {
            lower.contains(".flac") -> "audio/flac"
            lower.contains(".opus") -> "audio/ogg"
            lower.contains(".ogg") -> "audio/ogg"
            lower.contains(".m4a") -> "audio/mp4"
            lower.contains(".aac") -> "audio/aac"
            lower.contains(".wav") -> "audio/wav"
            lower.contains(".mp3") -> "audio/mpeg"
            else -> null
        }
    }
}
