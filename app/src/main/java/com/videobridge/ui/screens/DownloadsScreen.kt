package com.videobridge.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.videobridge.ui.MainViewModel
import com.videobridge.ui.state.AppUiState
import com.videobridge.ui.state.DownloadUiItem

/**
 * Lists offline downloads (completed + in-progress). Completed items can be cast to a TV without the
 * Jellyfin server (the proxy serves the local file); in-progress items can be cancelled.
 *
 * @param onCast triggers the local-network permission request + device scan (the shared scan action).
 */
@Composable
fun DownloadsScreen(state: AppUiState, viewModel: MainViewModel, onCast: () -> Unit) {
    Column {
        Text(
            "Downloaded for offline playback",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 12.dp),
        )
        if (state.downloads.isEmpty()) {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "No downloads yet. Open a movie or episode and tap \u201cDownload for offline\u201d.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(20.dp),
                )
            }
            return
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(state.downloads, key = { it.itemId }) { d ->
                DownloadRow(d, viewModel, onCast)
            }
        }
    }
}

@Composable
private fun DownloadRow(d: DownloadUiItem, viewModel: MainViewModel, onCast: () -> Unit) {
    val containerColor = when {
        d.completed -> MaterialTheme.colorScheme.primaryContainer
        d.failed -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val onContainerColor = when {
        d.completed -> MaterialTheme.colorScheme.onPrimaryContainer
        d.failed -> MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.onSurface
    }
    val leadingIcon = if (d.completed) Icons.Rounded.PlayArrow else Icons.Rounded.Download

    ElevatedCard(
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = containerColor),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    leadingIcon,
                    contentDescription = null,
                    tint = onContainerColor,
                    modifier = Modifier.size(26.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text(d.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = onContainerColor)
                    Text(
                        d.statusText,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (d.failed) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (!d.completed && !d.failed) {
                d.fraction?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }
                    ?: LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    d.completed -> {
                        Button(
                            onClick = { viewModel.prepareCastDownload(d.itemId); onCast() },
                            shape = RoundedCornerShape(18.dp),
                        ) {
                            Icon(Icons.Rounded.Cast, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text("  Cast")
                        }
                        OutlinedButton(
                            onClick = { viewModel.deleteDownload(d.itemId) },
                            shape = RoundedCornerShape(18.dp),
                        ) { Text("Delete") }
                    }
                    d.failed -> OutlinedButton(
                        onClick = { viewModel.deleteDownload(d.itemId) },
                        shape = RoundedCornerShape(18.dp),
                    ) { Text("Dismiss") }
                    else -> OutlinedButton(
                        onClick = { viewModel.cancelDownload(d.itemId) },
                        shape = RoundedCornerShape(18.dp),
                    ) { Text("Cancel") }
                }
            }
        }
    }
}
