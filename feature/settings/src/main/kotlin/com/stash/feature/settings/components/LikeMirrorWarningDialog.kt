package com.stash.feature.settings.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/**
 * v0.9.52: explicit opt-in ack before enabling like-mirroring for a
 * service. The pref is only written when the user taps "I understand"
 * — dismissing leaves mirroring off, so no writes ever happen without
 * this ack. Copy covers: what it does (incl. symmetric un-like), the
 * risk (private, unofficial write path), the mitigation (secondary /
 * backup account).
 */
@Composable
fun LikeMirrorWarningDialog(
    serviceName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Mirror likes to $serviceName?") },
        text = {
            Text(
                "Hearting a track in Stash will also Like it on $serviceName, " +
                    "and removing a heart will remove the Like there too. " +
                    "Only likes you add from now on are mirrored.\n\n" +
                    "This uses the same private endpoint the $serviceName web player " +
                    "uses — not an official API. It can stop working at any time and, " +
                    "rarely, unofficial access can get an account flagged. Consider " +
                    "enabling this only on a secondary or backup account.",
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("I understand") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
