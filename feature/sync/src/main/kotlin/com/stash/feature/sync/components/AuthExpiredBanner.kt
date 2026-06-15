package com.stash.feature.sync.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.stash.core.data.sync.AuthExpiryState

/**
 * Amber banner mounted at the top of the Sync tab when either Spotify or
 * YouTube auth is expired. Single-CTA copy per locked design decision 1A
 * (probe-at-sync-start + direct re-auth call to action).
 *
 * When [state.anyExpired] is false, the banner renders nothing (zero
 * height). The composable is dismissal-free — visibility is derived purely
 * from [state], so a successful re-auth flips the underlying StateFlow and
 * the banner disappears with no extra plumbing.
 */
@Composable
fun AuthExpiredBanner(
    state: AuthExpiryState,
    onReauth: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!state.anyExpired) return

    val headline: String
    val body: String
    val buttonText: String
    when {
        state.spotifyExpired && state.youtubeExpired -> {
            headline = "Sign-ins expired"
            body = "Both Spotify and YouTube need fresh sign-ins to resume sync."
            buttonText = "Re-authenticate"
        }
        state.spotifyExpired -> {
            // Only Spotify is expired \u2014 any other connected source still syncs,
            // so this is NOT a full pause. Copy reflects partial sync.
            headline = "Spotify session expired"
            body = "Re-authenticate to include your Spotify library in sync."
            buttonText = "Re-authenticate Spotify"
        }
        else -> {
            headline = "YouTube session expired"
            body = "Re-authenticate to include your YouTube library in sync."
            buttonText = "Re-authenticate YouTube"
        }
    }

    val amber = Color(0xFFFFAA00)
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(MaterialTheme.shapes.large)
            .background(amber.copy(alpha = 0.18f))
            .padding(14.dp),
    ) {
        Text(
            text = headline,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = onReauth,
            colors = ButtonDefaults.buttonColors(
                containerColor = amber,
                contentColor = Color.Black,
            ),
            shape = RoundedCornerShape(8.dp),
        ) {
            Text(buttonText, fontWeight = FontWeight.SemiBold)
        }
    }
}
