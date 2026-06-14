package com.stash.feature.settings.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * v0.9.13 — ROUTING status block for the lossless source chain.
 *
 * Replaces the legacy "Connect to squid.wtf" CTA which falsely implied
 * lossless required a captcha to function. Reality: kenny carries
 * lossless without auth or captcha; squid is an optional second source
 * that the user can unlock via captcha. When squid is down, kenny
 * silently fills in.
 *
 * Visual is dublab-influenced: mono caps header, indented `↳` rows,
 * small status dots (filled = configured, outlined = optional). Solve
 * link inline on the squid row when no cookie is set.
 *
 * Honesty caveat: we don't have ping/health telemetry yet, so we never
 * claim "live" — we use "active" (= configured and reachable in the
 * resolver chain). v0.9.14 can add real-time health based on the
 * AggregatorRateLimiter / source-success cache.
 */
@Composable
internal fun LosslessRoutingStatus(
    squidStatus: SquidCaptchaStatus,
    onSolveCaptcha: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val mono = FontFamily.Monospace
    val (squidConfigured, squidLabel, showSolveLink) = when (squidStatus) {
        SquidCaptchaStatus.NotConfigured ->
            Triple(false, "optional", true)
        SquidCaptchaStatus.Active ->
            Triple(true, "active", false)
        SquidCaptchaStatus.Expired ->
            // Cookie present but server-rejected — keep the dot filled
            // (user did set it up) but surface "expired" + the solver
            // entry-point so they can re-verify in one tap.
            Triple(true, "expired", true)
    }
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "ROUTING",
            fontFamily = mono,
            style = MaterialTheme.typography.labelSmall.copy(
                letterSpacing = 1.2.sp,
                fontWeight = FontWeight.Bold,
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(4.dp))
        RoutingRow(
            host = "kennyy.com.br",
            configured = true,
            statusLabel = "active",
        )
        RoutingRow(
            host = "squid.wtf",
            configured = squidConfigured,
            statusLabel = squidLabel,
            actionLabel = if (showSolveLink) "solve captcha →" else null,
            onAction = if (showSolveLink) onSolveCaptcha else null,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Lossless works on any active source. Adding squid gives you a backup host.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Single row inside [LosslessRoutingStatus]: indent arrow, host name,
 * status dot + label. Optional action link (e.g. "solve captcha →") on
 * the right when the source needs user setup.
 */
@Composable
internal fun RoutingRow(
    host: String,
    configured: Boolean,
    statusLabel: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val mono = FontFamily.Monospace
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "↳",
            fontFamily = mono,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 6.dp),
        )
        Text(
            text = host,
            fontFamily = mono,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        // Status dot — filled-primary when configured, outlined-muted when not.
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(
                    if (configured) MaterialTheme.colorScheme.primary
                    else Color.Transparent,
                )
                .border(
                    width = if (configured) 0.dp else 1.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    shape = CircleShape,
                ),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = statusLabel,
            fontFamily = mono,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (actionLabel != null && onAction != null) {
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = actionLabel,
                fontFamily = mono,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable(onClick = onAction),
            )
        }
    }
}
