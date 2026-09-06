package com.adsamcik.streamferry.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.adsamcik.streamferry.domain.MediaSourceIds
import com.adsamcik.streamferry.domain.isEpisode
import com.adsamcik.streamferry.playback.PlaylistEntry
import com.adsamcik.streamferry.playback.SavedPlaybackQueue
import com.adsamcik.streamferry.ui.UiController
import com.adsamcik.streamferry.ui.state.AppUiState
import com.adsamcik.streamferry.ui.state.Route

@Composable
fun QueuesScreen(state: AppUiState, viewModel: UiController) {
    var showSave by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<SavedPlaybackQueue?>(null) }
    var discardWorking by rememberSaveable { mutableStateOf(false) }
    var pendingLoad by rememberSaveable { mutableStateOf<String?>(null) }
    val queues = state.queues
    val contents = queues.working
    val canSwitch = state.playback == null && !state.isBusy && state.playbackStartingTargetId == null

    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Text(queues.activeName ?: "Current queue", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Queues and playback progress are kept on this device across sessions.", style = MaterialTheme.typography.bodyMedium)
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { if (state.playback != null) viewModel.navigate(Route.PLAYBACK) else viewModel.resumeQueue() },
                    enabled = contents.size > 0 && !state.isBusy,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.AutoMirrored.Rounded.PlaylistPlay, contentDescription = null)
                    Text(if (state.playback != null) "Now playing" else if (contents.current != null) "Resume on TV" else "Play on TV")
                }
                OutlinedButton(onClick = { name = ""; showSave = true }, enabled = contents.size > 0) { Text("Save as…") }
            }
        }
        if (contents.size == 0) {
            item { Text("Add videos, movies, or episodes from the library to build a queue.") }
        }
        contents.current?.let { entry ->
            item(key = "current-${entry.entryId}") {
                QueueEntryRow(
                    entry = entry, state = state, viewModel = viewModel,
                    subtitle = if (state.playback != null) "Current item" else "Resume from ${queuePosition(entry.item.resumePositionSeconds ?: 0)}",
                    onRemove = if (canSwitch) ({ viewModel.removePlaylistEntry(entry.entryId) }) else null,
                )
            }
        }
        items(contents.upNext.entries, key = { it.entryId }) { entry ->
            QueueEntryRow(entry, state, viewModel, entry.item.subtitle ?: "Up next", { viewModel.removePlaylistEntry(entry.entryId) })
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { viewModel.navigate(Route.GALLERY) }) { Text("Add from library") }
                TextButton(onClick = {
                    if (queues.activeId == null && contents.size > 0) discardWorking = true else viewModel.newQueue()
                }, enabled = canSwitch) { Text("New queue") }
            }
        }
        item {
            Text("Saved queues", style = MaterialTheme.typography.titleLarge)
            if (state.playback != null) Text("Stop playback to switch queues.", style = MaterialTheme.typography.bodySmall)
        }
        if (queues.saved.isEmpty()) {
            item { Text("Use Save as… to name a queue and keep its progress for later.") }
        }
        items(queues.saved, key = { "saved-${it.id}" }) { saved ->
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(saved.name, fontWeight = FontWeight.SemiBold)
                        Text(if (saved.contents.size == 0) "Finished" else "${saved.contents.size} items remaining", style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = {
                        if (queues.activeId == null && contents.size > 0) pendingLoad = saved.id else viewModel.loadQueue(saved.id)
                    }, enabled = canSwitch && saved.id != queues.activeId) {
                        Text(if (saved.id == queues.activeId) "Open" else "Open queue")
                    }
                    IconButton(onClick = { pendingDelete = saved }) {
                        Icon(Icons.Rounded.DeleteOutline, contentDescription = "Delete ${saved.name}")
                    }
                }
            }
        }
    }
    if (showSave) AlertDialog(
        onDismissRequest = { showSave = false },
        title = { Text("Save queue") },
        text = { OutlinedTextField(value = name, onValueChange = { name = it.take(80) }, label = { Text("Queue name") }, singleLine = true) },
        confirmButton = { TextButton(onClick = { viewModel.saveQueue(name); showSave = false }, enabled = name.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = { showSave = false }) { Text("Cancel") } },
    )
    pendingDelete?.let { saved ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null }, title = { Text("Delete ${saved.name}?") },
            text = { Text("This removes the saved queue. Your media files and the current queue are kept.") },
            confirmButton = { TextButton(onClick = { viewModel.deleteQueue(saved.id); pendingDelete = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
        )
    }
    if (discardWorking) AlertDialog(
        onDismissRequest = { discardWorking = false }, title = { Text("Start a new queue?") },
        text = { Text("Save the current queue under a name first if you want to keep it.") },
        confirmButton = { TextButton(onClick = { viewModel.newQueue(); discardWorking = false }) { Text("New queue") } },
        dismissButton = { TextButton(onClick = { discardWorking = false }) { Text("Cancel") } },
    )
    pendingLoad?.let { id ->
        AlertDialog(
            onDismissRequest = { pendingLoad = null }, title = { Text("Open another queue?") },
            text = { Text("Save the current queue under a name first if you want to keep it.") },
            confirmButton = { TextButton(onClick = { viewModel.loadQueue(id); pendingLoad = null }) { Text("Open queue") } },
            dismissButton = { TextButton(onClick = { pendingLoad = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun QueueEntryRow(entry: PlaylistEntry, state: AppUiState, viewModel: UiController, subtitle: String, onRemove: (() -> Unit)?) {
    val title = entry.item.title
    var showWatchActions by remember(entry.entryId) { mutableStateOf(false) }
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (entry.item.sourceId == MediaSourceIds.REMOTE && !entry.item.isFolder) Box {
                IconButton(onClick = { showWatchActions = true },
                    enabled = state.canBrowseRemote && entry.item.id !in state.watchStateMutationItemIds) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = "Watch state for $title")
                }
                DropdownMenu(expanded = showWatchActions, onDismissRequest = { showWatchActions = false }) {
                    DropdownMenuItem(text = { Text(if (entry.item.played) "Mark unwatched" else "Mark watched") }, onClick = {
                        viewModel.markQueueItemWatched(entry.entryId, !entry.item.played)
                        showWatchActions = false
                    })
                    if (entry.item.isEpisode()) DropdownMenuItem(text = { Text("Reset progress") }, onClick = {
                        viewModel.resetQueueItemProgress(entry.entryId)
                        showWatchActions = false
                    })
                }
            }
            if (onRemove != null) IconButton(onClick = onRemove) {
                Icon(Icons.Rounded.Close, contentDescription = "Remove $title from queue")
            }
        }
    }
}

private fun queuePosition(seconds: Long): String = "%d:%02d".format(seconds / 60, seconds % 60)
