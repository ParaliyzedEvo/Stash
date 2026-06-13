package com.stash.core.media.streaming

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy

/**
 * Player-wide [MediaSource.Factory] that routes **only YouTube-origin streaming
 * items** through the [StreamingMediaSourceFactory] refresh chain
 * (CacheDataSource → [RefreshingDataSource] → HTTP), and everything else —
 * downloaded/local files AND lossless (Kennyy/Squid) streams — through the
 * plain [DefaultMediaSourceFactory], exactly as before.
 *
 * **Why scope to YouTube only.** Background queue-fill seeds the timeline with
 * cheap InnerTube/iOS placeholder URLs (deep, in-order), but those are
 * PO-token-gated to ~1 MB and return HTTP 403 on full playback. The default
 * factory has no recovery — a 403 surfaces as `onPlayerError`, and the cascade
 * guard skip-storms the whole queue. Wrapping YouTube items in
 * [RefreshingDataSource] makes that 403 transparently re-resolve via yt-dlp
 * (full-range-playable) and continue at the same byte offset — no skip, no
 * Halt. Lossless and local playback are left on their proven path so this
 * change can't regress them.
 *
 * The per-item decision is delegated to [streamingTrackId]: it returns the
 * track id when the item should use the refresh chain (YouTube http(s) stream
 * with a valid id), or null otherwise. The service owns that predicate because
 * the metadata-extra keys live there.
 */
@OptIn(UnstableApi::class)
class StashMediaSourceFactory(
    context: Context,
    private val streamingFactory: StreamingMediaSourceFactory,
    private val streamingTrackId: (MediaItem) -> Long?,
) : MediaSource.Factory {

    private val localFactory = DefaultMediaSourceFactory(context)

    override fun setDrmSessionManagerProvider(
        provider: DrmSessionManagerProvider,
    ): MediaSource.Factory {
        localFactory.setDrmSessionManagerProvider(provider)
        return this
    }

    override fun setLoadErrorHandlingPolicy(
        policy: LoadErrorHandlingPolicy,
    ): MediaSource.Factory {
        localFactory.setLoadErrorHandlingPolicy(policy)
        return this
    }

    override fun getSupportedTypes(): IntArray = localFactory.supportedTypes

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val trackId = streamingTrackId(mediaItem)
        return if (trackId != null) {
            streamingFactory.create(trackId).createMediaSource(mediaItem)
        } else {
            // Guard: DefaultMediaSourceFactory throws NPE when
            // mediaItem.localConfiguration is null (no URI set). This can
            // happen during session resumption when MediaItems lose their URIs.
            // Instead of throwing or crashing, supply a dummy data URI so ExoPlayer
            // can load the timeline without crashing, and the prefetcher will
            // swap in the real streaming URI when it finishes resolving.
            val itemToUse = if (mediaItem.localConfiguration == null) {
                mediaItem.buildUpon()
                    .setUri(android.net.Uri.parse("data:audio/mp3;base64,"))
                    .build()
            } else {
                mediaItem
            }
            localFactory.createMediaSource(itemToUse)
        }
    }
}
