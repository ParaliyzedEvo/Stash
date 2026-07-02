package com.stash.feature.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.stash.core.media.preview.LosslessUrlPrefetcher
import com.stash.core.media.preview.PreviewState
import com.stash.core.model.TrackItem
import com.stash.data.ytmusic.model.TrackSummary

/**
 * "Popular" shelf on the Artist Profile screen.
 *
 * Renders up to 5 of the artist's popular tracks via [PreviewDownloadRow] —
 * the exact same composable the main Search tab uses for song results. No
 * fork: a shared Compose UI test (when the androidTest source set lands)
 * asserts identity via the `"PreviewDownloadRow"` test tag.
 *
 * Task 12 wired the row callbacks into [ArtistProfileViewModel] so tapping
 * a Popular track now plays a preview / kicks a download — the same bodies
 * as their [SearchViewModel] counterparts, sharing the same singletons.
 *
 * @param tracks The popular tracks to render.
 * @param previewState Player state, used to highlight the currently-playing row.
 * @param downloadingIds Video IDs currently downloading (drives per-row spinner).
 * @param downloadedIds Video IDs already downloaded (drives per-row checkmark).
 * @param previewLoadingId Video ID whose preview URL is being resolved, or null.
 * @param onPreview Invoked with a [TrackItem] when a row's play button is tapped.
 * @param onStopPreview Invoked when the currently-playing row's stop button is tapped.
 * @param onDownload Invoked with the row's [SearchResultItem] when its download arrow is tapped.
 * @param losslessPrefetcher Warms the lossless URL cache as each row enters composition.
 */
@Composable
fun PopularTracksSection(
    tracks: List<TrackSummary>,
    previewState: PreviewState,
    downloadingIds: Set<String>,
    downloadedIds: Set<String>,
    previewLoadingId: String?,
    losslessPrefetcher: LosslessUrlPrefetcher,
    onPreview: (TrackItem) -> Unit,
    onStopPreview: () -> Unit,
    onDownload: (SearchResultItem) -> Unit,
    onPlayNext: (TrackItem) -> Unit = {},
    onAddToQueue: (TrackItem) -> Unit = {},
    onRequestAddToPlaylist: (TrackItem) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        tracks.forEach { track ->
            val item = track.toSearchResultItem()
            val trackItem = track.toTrackItem()
            // Warm the lossless URL cache as each Popular row enters composition.
            // Idempotent — LosslessUrlPrefetcher dedupes by videoId.
            LaunchedEffect(track.videoId) {
                losslessPrefetcher.warmUp(trackItem)
            }
            PreviewDownloadRow(
                item = item,
                isDownloading = track.videoId in downloadingIds,
                isDownloaded = track.videoId in downloadedIds,
                isPreviewLoading = previewLoadingId == track.videoId,
                isPreviewPlaying = previewState is PreviewState.Playing &&
                    previewState.videoId == track.videoId,
                onPreview = { onPreview(trackItem) },
                onStopPreview = onStopPreview,
                onDownload = { onDownload(item) },
                onPlayNext = { onPlayNext(trackItem) },
                onAddToQueue = { onAddToQueue(trackItem) },
                onAddToPlaylist = { onRequestAddToPlaylist(trackItem) },
            )
        }
    }
}
