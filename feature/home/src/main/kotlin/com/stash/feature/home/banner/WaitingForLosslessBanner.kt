package com.stash.feature.home.banner

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/**
 * v0.9.17: "tracks waiting for lossless" Home banner. Surfaced when one
 * or more downloads are stuck in `WAITING_FOR_LOSSLESS` because the user
 * is in FLAC-only mode and the lossless source can't currently resolve.
 *
 * State machine (see [bannerStateFor] for inputs → state mapping):
 *  - [WaitingForLosslessBannerState.ExpiredCaptcha] — squid.wtf cookie
 *    is the same one that just got rejected. Action: open captcha solver.
 *  - [WaitingForLosslessBannerState.NoSourceConfigured] — no cookie set
 *    AND kennyy is down. Action: connect a lossless source.
 *  - [WaitingForLosslessBannerState.KennyyDown] — squid path looks fine,
 *    kennyy is in circuit-broken state. No user action — just informs.
 *  - [WaitingForLosslessBannerState.DefensiveRetry] — both sources look
 *    healthy but tracks are still deferred. Action: manually trigger a
 *    [com.stash.data.download.lossless.LosslessRetryWorker] sweep.
 *  - [WaitingForLosslessBannerState.Hidden] — early return; no banner.
 *
 * Visual treatment mirrors `LastFmConnectBanner` / `LosslessConnectBanner`
 * in [com.stash.feature.home.HomeScreen] — same tertiary-tint Surface,
 * same row layout (copy + action chip + dismiss X). Per-session dismiss
 * lives in `HomeViewModel._waitingBannerDismissed`; the banner doesn't
 * own its own state.
 */
@Composable
fun WaitingForLosslessBanner(
    state: WaitingForLosslessBannerState,
    onSolveCaptcha: () -> Unit,
    onConnect: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state is WaitingForLosslessBannerState.Hidden) return

    val (count, headline, body, actionLabel, onAction) = bannerCopy(
        state = state,
        onSolveCaptcha = onSolveCaptcha,
        onConnect = onConnect,
        onRetry = onRetry,
    )

    val accent = MaterialTheme.colorScheme.tertiary
    val rowClickable = if (onAction != null) {
        Modifier.clickable(onClick = onAction)
    } else {
        Modifier
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = accent.copy(alpha = 0.10f),
        shape = RoundedCornerShape(12.dp),

    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(rowClickable)
                .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = headline,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (actionLabel != null) {
                Text(
                    text = actionLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = accent,
                )
            }
            // Dismiss-this-session X. Always present so the user can hide
            // the surface even when there's no recovery action (the
            // [WaitingForLosslessBannerState.KennyyDown] case has no
            // chip but still needs an escape hatch).
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onDismiss),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Dismiss waiting-for-lossless banner ($count tracks)",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/**
 * Internal copy holder for the banner's per-state strings. Split out so
 * the composable body stays focused on layout. The action callback is
 * `null` for the inform-only [WaitingForLosslessBannerState.KennyyDown]
 * state — the row is non-tappable in that case.
 */
private data class BannerCopy(
    val count: Int,
    val headline: String,
    val body: String,
    val actionLabel: String?,
    val onAction: (() -> Unit)?,
)

private fun bannerCopy(
    state: WaitingForLosslessBannerState,
    onSolveCaptcha: () -> Unit,
    onConnect: () -> Unit,
    onRetry: () -> Unit,
): BannerCopy = when (state) {
    is WaitingForLosslessBannerState.Hidden -> BannerCopy(0, "", "", null, null)
    is WaitingForLosslessBannerState.ExpiredCaptcha -> BannerCopy(
        count = state.count,
        headline = "${state.count} ${trackWord(state.count)} waiting",
        body = "squid.wtf cookie expired. Solve the captcha to resume.",
        actionLabel = "Solve captcha →",
        onAction = onSolveCaptcha,
    )
    is WaitingForLosslessBannerState.NoSourceConfigured -> BannerCopy(
        count = state.count,
        headline = "${state.count} ${trackWord(state.count)} waiting",
        body = "Set up a lossless source to download.",
        actionLabel = "Connect →",
        onAction = onConnect,
    )
    is WaitingForLosslessBannerState.KennyyDown -> BannerCopy(
        count = state.count,
        headline = "${state.count} ${trackWord(state.count)} waiting",
        body = "kennyy.com.br temporarily down. We'll retry automatically.",
        actionLabel = null,
        onAction = null,
    )
    is WaitingForLosslessBannerState.DefensiveRetry -> BannerCopy(
        count = state.count,
        headline = "${state.count} ${trackWord(state.count)} waiting",
        body = "Tap retry to re-resolve now.",
        actionLabel = "Retry →",
        onAction = onRetry,
    )
}

private fun trackWord(count: Int): String = if (count == 1) "track" else "tracks"
