package com.stash.feature.home.banner

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * v0.9.36: "Fetching lyrics" Home banner. Surfaces the long-running
 * [com.stash.data.lyrics.worker.LyricsBackfillWorker] sweep on upgrade
 * so users know why background network activity is happening.
 *
 * State machine (see [lyricsBackfillBannerStateFor] for inputs → state):
 *  - [LyricsBackfillBannerState.Running] — worker is iterating;
 *    shows `processed/total` headline + thin progress bar.
 *  - [LyricsBackfillBannerState.Finished] — worker drained the queue;
 *    shows a completion summary for a 2-second pulse, then the
 *    [onFinishedAcknowledged] callback flips state back to IDLE and
 *    the banner vanishes.
 *  - [LyricsBackfillBannerState.Hidden] — early return; steady state.
 *
 * Visual treatment mirrors [MetadataBackfillBanner] so the two banners
 * read as siblings on the Home screen — tertiary-tinted Surface, same
 * 12dp rounded corners and 1dp border at 35% accent alpha.
 */
@Composable
fun LyricsBackfillBanner(
    state: LyricsBackfillBannerState,
    onFinishedAcknowledged: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state is LyricsBackfillBannerState.Hidden) return

    val accent = MaterialTheme.colorScheme.tertiary

    if (state is LyricsBackfillBannerState.Finished) {
        // 2-second "Done" pulse: ack-callback flips LyricsBackfillState
        // back to IDLE, which makes the snapshot Flow emit IDLE, which
        // maps to Hidden, which causes this composable to early-return.
        LaunchedEffect(state) {
            delay(2_000)
            onFinishedAcknowledged()
        }
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = accent.copy(alpha = 0.10f),
        shape = RoundedCornerShape(12.dp),

    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            when (state) {
                LyricsBackfillBannerState.Hidden -> Unit
                is LyricsBackfillBannerState.Running -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "Fetching lyrics\u2026",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "${state.processed} / ${state.total}",
                            style = MaterialTheme.typography.labelSmall,
                            color = accent,
                        )
                    }
                    Text(
                        text = "Adding synced lyrics to your library",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LinearProgressIndicator(
                        progress = {
                            // total is guaranteed > 0 by lyricsBackfillBannerStateFor
                            state.processed.toFloat() / state.total.toFloat()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 2.dp),
                        color = accent,
                        trackColor = accent.copy(alpha = 0.20f),
                    )
                }
                is LyricsBackfillBannerState.Finished -> {
                    Text(
                        text = "Lyrics fetched for ${state.total} tracks",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}
