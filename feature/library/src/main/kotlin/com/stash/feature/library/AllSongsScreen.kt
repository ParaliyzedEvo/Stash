package com.stash.feature.library.allsongs

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stash.core.model.Track
import com.stash.core.ui.components.TrackListItem
import com.stash.core.ui.components.TrackRowFitScope
import com.stash.core.ui.components.bottomChromePadding

@Composable
fun AllSongsScreen(
    modifier: Modifier = Modifier,
    viewModel: AllSongsViewModel = hiltViewModel(),
) {
    val songs by viewModel.songs.collectAsStateWithLifecycle()
    val folders by viewModel.folders.collectAsStateWithLifecycle()
    val playingId by viewModel.playingId.collectAsStateWithLifecycle()
    val scanning by viewModel.scanning.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    var showFolders by rememberSaveable { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) viewModel.addFolder(uri)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(top = 16.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "All Songs",
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = viewModel::shuffle, enabled = songs.isNotEmpty()) {
                Icon(Icons.Filled.Shuffle, contentDescription = "Shuffle all songs", tint = MaterialTheme.colorScheme.primary)
            }
            IconButton(onClick = viewModel::rescan, enabled = !scanning && folders.isNotEmpty()) {
                Icon(Icons.Filled.Refresh, contentDescription = "Rescan folders")
            }
            IconButton(onClick = { showFolders = true }) {
                Icon(Icons.Filled.FolderOpen, contentDescription = "Scan folders")
            }
        }

        if (scanning) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp))
        }
        status?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }

        if (songs.isEmpty() && !scanning) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp)
                    .padding(bottom = bottomChromePadding(0.dp)),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = if (folders.isEmpty()) "Pick the folders to scan for music" else "No songs found in your folders",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = { showFolders = true }) { Text("Choose folders") }
            }
        } else {
            TrackRowFitScope {
                LazyColumn(contentPadding = PaddingValues(bottom = bottomChromePadding(8.dp))) {
                    itemsIndexed(songs, key = { _, t -> t.id }) { index, track ->
                        TrackListItem(
                            track = track,
                            onClick = { viewModel.play(index) },
                            isPlaying = track.id == playingId,
                        )
                    }
                }
            }
        }
    }

    if (showFolders) {
        AlertDialog(
            onDismissRequest = { showFolders = false },
            title = { Text("Scan folders") },
            text = {
                Column {
                    if (folders.isEmpty()) {
                        Text("No folders yet. Songs in the folders you add (and their subfolders) show up in All Songs.")
                    }
                    folders.forEach { folder ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = folder.displayName,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = { viewModel.removeFolder(folder) }) {
                                Icon(Icons.Filled.Close, contentDescription = "Remove ${folder.displayName}")
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { picker.launch(null) }) {
                    Icon(Icons.Filled.CreateNewFolder, contentDescription = null)
                    Text("  Add folder")
                }
            },
            dismissButton = { TextButton(onClick = { showFolders = false }) { Text("Done") } },
        )
    }
}

@Composable
private fun SongRow(track: Track, isPlaying: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (track.album.isBlank()) track.artist else "${track.artist} · ${track.album}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (track.durationMs > 0) {
            val s = track.durationMs / 1000
            Text(
                text = "%d:%02d".format(s / 60, s % 60),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
    }
}
