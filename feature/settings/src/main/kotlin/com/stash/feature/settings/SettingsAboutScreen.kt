package com.stash.feature.settings

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkManager
import com.stash.core.data.sync.workers.UpdateCheckWorker
import com.stash.core.ui.components.GlassCard
import com.stash.feature.settings.components.SettingsSectionLabel
import com.stash.feature.settings.components.SettingsValueRow

@Composable
fun SettingsAboutScreen(
    onBack: () -> Unit,
    onNavigateToDiagnosticsPreview: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    SettingsScaffold(title = stringResource(R.string.settings_about_title)) {
        val context = LocalContext.current
        LaunchedEffect(Unit) {
            viewModel.refreshUpdateStatus(context)
        }

        SettingsSectionLabel(stringResource(R.string.settings_diagnostics_section))

        GlassCard {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.settings_share_latest_crash),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (uiState.hasCrashLog) {
                        stringResource(R.string.settings_attach_most_recent_crash)
                    } else {
                        stringResource(R.string.settings_no_recent_crashes)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(
                    enabled = uiState.hasCrashLog,
                    onClick = {
                        val target = viewModel.getLatestCrashLog(context)
                        if (target == null) {
                            Toast.makeText(context, "No crash log found", Toast.LENGTH_SHORT).show()
                            return@OutlinedButton
                        }
                        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(android.content.Intent.EXTRA_STREAM, androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.provider", target))
                            putExtra(android.content.Intent.EXTRA_SUBJECT, "Stash Crash Log")
                            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        val chooser = android.content.Intent.createChooser(send, null).apply {
                            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        runCatching { context.startActivity(chooser) }.onFailure {
                            Toast.makeText(context, "Failed to share crash log", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text(stringResource(R.string.settings_share_latest_crash))
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        GlassCard {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.settings_share_diagnostics_bundle),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.settings_bundle_recent_logs),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(
                    onClick = onNavigateToDiagnosticsPreview,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text(stringResource(R.string.settings_share_diagnostics))
                }
            }
        }

        // -- About section ------------------------------------------------
        SettingsSectionLabel(stringResource(R.string.settings_about_section))

        val installedVersion = remember(context) {
            runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            }.getOrNull() ?: "0.3.5-beta"
        }

        var checking by remember { mutableStateOf(false) }
        LaunchedEffect(context) {
            WorkManager.getInstance(context)
                .getWorkInfosForUniqueWorkLiveData(UpdateCheckWorker.WORK_NAME)
                .observeForever { infos ->
                    val info = infos.lastOrNull() ?: return@observeForever
                    if (!info.state.isFinished) return@observeForever
                    if (!checking) return@observeForever
                    checking = false
                    val outcome = info.outputData.getString(UpdateCheckWorker.KEY_OUTCOME)
                    val tag = info.outputData.getString(UpdateCheckWorker.KEY_VERSION_TAG)
                    val message = when (outcome) {
                        UpdateCheckWorker.OUTCOME_UP_TO_DATE -> stringResource(R.string.settings_update_on_latest)
                        UpdateCheckWorker.OUTCOME_UPDATE_AVAILABLE -> stringResource(R.string.settings_update_available)
                        else -> stringResource(R.string.settings_update_check_failed)
                    }
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                }
        }

        GlassCard {
            Column(modifier = Modifier.fillMaxWidth()) {
                SettingsValueRow(label = "Version", value = installedVersion)
                Spacer(modifier = Modifier.height(8.dp))
                SettingsValueRow(label = "Channel", value = "Release")
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(
                    onClick = {
                        checking = true
                        UpdateCheckWorker.enqueue(context, forced = true)
                        Toast.makeText(context, "Checking for updates...", Toast.LENGTH_SHORT).show()
                    },
                    enabled = !checking,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text(if (checking) stringResource(R.string.settings_checking_updates) else stringResource(R.string.settings_check_updates))
                }
            }
        }
    }
}
