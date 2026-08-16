package com.adsamcik.streamferry.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.adsamcik.streamferry.ui.UiController
import com.adsamcik.streamferry.ui.state.AppUiState
import com.adsamcik.streamferry.ui.state.DownloadUiItem

/**
 * Lists saved and active offline copies. This screen owns its single lazy scroll container so the app
 * shell can give it bounded height without introducing nested scrolling or infinite-height measurement.
 */
@Composable
fun DownloadsScreen(state: AppUiState, viewModel: UiController, onCast: () -> Unit) {
    var deleteCandidate by rememberSaveable { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state.downloads.isEmpty()) {
            item(key = "empty-downloads") {
                EmptyDownloadsSurface(Modifier.animateItem())
            }
        } else {
            item(key = "download-recovery") {
                Text(
                    "Interrupted downloads resume automatically when your connection and phone allow it. " +
                        "If a download permanently fails, open its movie or episode and start it again.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.animateItem().padding(bottom = 4.dp),
                )
            }
            items(state.downloads, key = { it.itemId }) { item ->
                DownloadRow(
                    item = item,
                    modifier = Modifier.animateItem(),
                    onPlay = {
                        viewModel.prepareCastDownload(item.itemId)
                        onCast()
                    },
                    onCancel = { viewModel.cancelDownload(item.itemId) },
                    onDelete = { deleteCandidate = item.itemId },
                )
            }
        }
    }

    val deleting = deleteCandidate?.let { id ->
        state.downloads.firstOrNull { it.itemId == id && (it.completed || it.failed) }
    }
    if (deleting != null) {
        val deletingFailure = deleting.failed
        AlertDialog(
            onDismissRequest = { deleteCandidate = null },
            title = { Text(if (deletingFailure) "Delete failed download?" else "Delete offline copy?") },
            text = {
                Text(
                    if (deletingFailure) {
                        "Any saved progress and retry request for ${deleting.title} will be removed. " +
                            "The original media is not changed."
                    } else {
                        "${deleting.title} will be removed from this phone. The original media is not changed."
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteDownload(deleting.itemId)
                        deleteCandidate = null
                    },
                ) {
                    Text(if (deletingFailure) "Delete download" else "Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteCandidate = null }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun EmptyDownloadsSurface(modifier: Modifier = Modifier) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                Icons.Rounded.DownloadDone,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(36.dp),
            )
            Text(
                "No offline copies",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Text(
                "Open a movie or episode and choose Download. Completed copies can be played on a TV " +
                    "even when the Jellyfin server is unavailable.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun DownloadRow(
    item: DownloadUiItem,
    modifier: Modifier = Modifier,
    onPlay: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    val progress = item.fraction?.coerceIn(0f, 1f)
    val targetContainerColor = when {
        item.failed -> MaterialTheme.colorScheme.errorContainer
        item.completed -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainerLow
    }
    val targetContentColor = when {
        item.failed -> MaterialTheme.colorScheme.onErrorContainer
        item.completed -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }
    val containerColor by animateColorAsState(
        targetValue = targetContainerColor,
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "download container color",
    )
    val contentColor by animateColorAsState(
        targetValue = targetContentColor,
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "download content color",
    )
    val animatedProgress by animateFloatAsState(
        targetValue = progress ?: 0f,
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "download progress",
    )
    val statusDescription = when {
        item.completed -> "Downloaded and available offline"
        item.failed -> "Download failed. ${item.statusText}"
        progress != null -> "Downloading ${(progress * 100).toInt()} percent"
        else -> item.statusText
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec()),
        shape = MaterialTheme.shapes.large,
        color = containerColor,
        contentColor = contentColor,
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics(mergeDescendants = true) {
                        stateDescription = statusDescription
                        if (!item.completed && !item.failed) {
                            progressBarRangeInfo = progress?.let {
                                ProgressBarRangeInfo(it, 0f..1f)
                            } ?: ProgressBarRangeInfo.Indeterminate
                        }
                    },
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Surface(
                        shape = CircleShape,
                        color = contentColor.copy(alpha = 0.12f),
                        contentColor = contentColor,
                        modifier = Modifier.size(48.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = when {
                                    item.failed -> Icons.Rounded.ErrorOutline
                                    item.completed -> Icons.Rounded.DownloadDone
                                    else -> Icons.Rounded.Download
                                },
                                contentDescription = null,
                                modifier = Modifier.size(24.dp),
                            )
                        }
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            item.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            item.statusText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = contentColor.copy(alpha = 0.78f),
                            modifier = Modifier.clearAndSetSemantics { },
                        )
                    }
                }

                if (!item.completed && !item.failed) {
                    if (progress != null) {
                        LinearProgressIndicator(
                            progress = { animatedProgress },
                            modifier = Modifier.fillMaxWidth().clearAndSetSemantics { },
                        )
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth().clearAndSetSemantics { },
                        )
                    }
                }
            }

            when {
                item.completed -> {
                    Button(
                        onClick = onPlay,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Rounded.Tv, contentDescription = null)
                        Text("Play on TV", modifier = Modifier.padding(start = 8.dp))
                    }
                    OutlinedButton(
                        onClick = onDelete,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Rounded.Delete, contentDescription = null)
                        Text("Delete offline copy", modifier = Modifier.padding(start = 8.dp))
                    }
                }

                item.failed -> {
                    OutlinedButton(
                        onClick = onDelete,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Rounded.Delete, contentDescription = null)
                        Text("Delete failed download", modifier = Modifier.padding(start = 8.dp))
                    }
                }

                else -> {
                    OutlinedButton(
                        onClick = onCancel,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Cancel download")
                    }
                }
            }
        }
    }
}
