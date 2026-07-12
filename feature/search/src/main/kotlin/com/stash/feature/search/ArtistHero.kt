package com.stash.feature.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.stash.core.common.ArtUrlUpgrader

/**
 * Artist Profile hero card, designed to match the premium full-bleed
 * YouTube Music header style.
 *
 * Renders a full-bleed banner of the artist image (upgraded from their avatarUrl),
 * with a dark vertical gradient wash that fades the image into the app's background.
 * Overlaid at the bottom of the banner are left-aligned artist name and subscriber count labels,
 * a "Subscribed" action capsule, a "Radio" icon button, and a large circular "Play" FAB on the right.
 *
 * @param hero Name + avatar + subscribers triple. Name is required; the
 *   rest are optional and hide gracefully when null.
 * @param status Load status; currently unused visually but accepted so
 *   Task 11 can add a stale badge without the call-site changing.
 * @param onBack Invoked when the top-left back arrow is tapped (spec §5.2).
 * @param onPlayArtist Invoked when the "Play" button is tapped. Hybrid-starts
 *   playback of the artist's catalog — see [ArtistProfileViewModel.playArtist].
 * @param onStartRadio Invoked when the "Radio" button is tapped. Starts a
 *   balanced artist radio — see [ArtistProfileViewModel.startRadio].
 */
@Composable
fun ArtistHero(
    hero: HeroState,
    @Suppress("UNUSED_PARAMETER") status: ArtistProfileStatus,
    onBack: () -> Unit,
    onPlayArtist: () -> Unit,
    onStartRadio: () -> Unit,
    streamingEnabled: Boolean,
    onStreamingClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val backgroundColor = MaterialTheme.colorScheme.background

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(360.dp)
            .background(backgroundColor)
    ) {
        // 1. Full-bleed background artist cover/avatar image
        val upgradedUrl = ArtUrlUpgrader.upgrade(hero.avatarUrl)
        if (upgradedUrl != null) {
            AsyncImage(
                model = upgradedUrl,
                contentDescription = hero.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }

        // 2. Vertical gradient wash to blend cover art into text and the list background
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.45f), // dark tint at top for back icon readability
                            Color.Transparent,
                            Color.Transparent,
                            backgroundColor.copy(alpha = 0.85f),
                            backgroundColor
                        ),
                        startY = 0f,
                    )
                )
        )

        // 3. Artist info & Action buttons row at the bottom
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 20.dp)
        ) {
            Text(
                text = hero.name,
                style = MaterialTheme.typography.headlineLarge.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 32.sp
                ),
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (hero.subscribersText != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = hero.subscribersText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.7f),
                )
            }
            Spacer(Modifier.height(16.dp))

            // Play + Radio CTAs. Play matches the AlbumHero "Play" style (filled
            // primary); Radio is a tonal sibling that starts a balanced station.
            Row(horizontalArrangement = Arrangement.Center) {
                Button(
                    onClick = onPlayArtist,
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "Play",
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                Spacer(Modifier.width(10.dp))
                FilledTonalButton(
                    onClick = onStartRadio,
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Radio,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "Radio",
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            // Action row: Play FAB on the right
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End
            ) {
                // Play FAB
                Surface(
                    shape = CircleShape,
                    color = Color.White,
                    modifier = Modifier
                        .size(56.dp)
                        .clickable(onClick = onPlayArtist),
                    tonalElevation = 4.dp
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "Play Artist Catalog",
                            tint = Color.Black,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
            }
        }
    }
        // 4. Top-left back icon
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(top = 12.dp, start = 8.dp),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = Color.White,
            )
        }

        // Top-right Online/Offline chip — flip playback mode from the profile
        // (mirrors the back arrow's placement).
        if (com.stash.core.common.constants.StashConstants.STREAMING_ENGINE_ENABLED) {
            com.stash.core.ui.components.streaming.StreamingModeChip(
                streamingEnabled = streamingEnabled,
                onClick = onStreamingClick,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp),
            )
        }
    }
}
