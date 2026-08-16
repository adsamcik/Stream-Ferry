package com.adsamcik.streamferry.ui.screens

import android.content.res.Configuration
import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Forward30
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Hd
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Replay30
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import coil.compose.AsyncImage
import com.adsamcik.streamferry.core.adaptive.QualityMenu
import com.adsamcik.streamferry.core.chapter.chapterIndexForPosition
import com.adsamcik.streamferry.core.stream.Protocol
import com.adsamcik.streamferry.core.stream.TargetCapabilities
import com.adsamcik.streamferry.core.stream.TvOutputMenu
import com.adsamcik.streamferry.domain.DiscoveredTarget
import com.adsamcik.streamferry.domain.MediaChapter
import com.adsamcik.streamferry.physical.PhysicalTv
import com.adsamcik.streamferry.playback.PlaybackAttemptDescriptor
import com.adsamcik.streamferry.playback.PlaybackControlPolicy
import com.adsamcik.streamferry.playback.PlaybackPhase
import com.adsamcik.streamferry.playback.PlaybackTimecode
import com.adsamcik.streamferry.source.api.ArtworkRef
import com.adsamcik.streamferry.ui.MainViewModel
import com.adsamcik.streamferry.ui.components.ExpressiveLoadingIndicator
import com.adsamcik.streamferry.ui.state.AppUiState
import com.adsamcik.streamferry.ui.state.JellyfinItemAvailability
import com.adsamcik.streamferry.ui.state.PlaybackUiState
import com.adsamcik.streamferry.ui.state.displayedIsPlaying
import com.adsamcik.streamferry.ui.state.displayedPositionSeconds
import com.adsamcik.streamferry.ui.state.displayedVolume
import com.adsamcik.streamferry.ui.theme.StreamFerryTheme
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

private const val TARGET_AUTO_REFRESH_SECONDS = 15

@Composable
fun TargetPickerScreen(state: AppUiState, viewModel: MainViewModel, onRescan: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestRescan by rememberUpdatedState(onRescan)
    val latestScanning by rememberUpdatedState(state.isScanningTargets)
    var secondsUntilRefresh by remember { mutableIntStateOf(TARGET_AUTO_REFRESH_SECONDS) }
    var unlinkCandidate by remember { mutableStateOf<PhysicalTv?>(null) }
    val playbackTargetId = state.playbackStartingTargetId

    // Refresh only while this picker is actually in the foreground. The ViewModel guards against
    // overlapping work and stops discovery when navigation leaves this route.
    LaunchedEffect(lifecycleOwner, state.localNetworkPermissionGranted) {
        if (!state.localNetworkPermissionGranted) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            secondsUntilRefresh = TARGET_AUTO_REFRESH_SECONDS
            if (!latestScanning) latestRescan()
            while (true) {
                delay(1_000)
                secondsUntilRefresh = (secondsUntilRefresh - 1).coerceAtLeast(0)
                if (secondsUntilRefresh == 0) {
                    if (!latestScanning) latestRescan()
                    secondsUntilRefresh = TARGET_AUTO_REFRESH_SECONDS
                }
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 4.dp),
        ) {
            item(key = "target-tv-heading") {
                TargetPickerHeader(
                    scanning = state.isScanningTargets,
                    playbackStarting = playbackTargetId != null,
                    permissionGranted = state.localNetworkPermissionGranted,
                    secondsUntilRefresh = secondsUntilRefresh,
                    onRefresh = {
                        secondsUntilRefresh = TARGET_AUTO_REFRESH_SECONDS
                        onRescan()
                    },
                )
            }

            if (!state.localNetworkPermissionGranted) {
                item(key = "target-permission") {
                    LocalNetworkAccessCard(
                        onOpenSettings = {
                            runCatching { context.startActivity(viewModel.localNetworkPermissionSettingsIntent()) }
                        },
                    )
                }
            }

            state.previousPhysicalTvName?.let { previousName ->
                item(key = "target-previous-tv") {
                    Text(
                        "Previously used: $previousName. If it isn't available, choose another TV; your resume point is safe.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            }
            if (state.localNetworkPermissionGranted) {
                item(key = "target-tv-list") {
                    PhysicalTvGroup(
                        targets = state.physicalTvs,
                        empty = "Make sure the TV is on and connected to this network.",
                        scanning = state.isScanningTargets,
                        playbackTargetId = playbackTargetId,
                        onSelect = viewModel::selectPhysicalTv,
                        onUnlink = { unlinkCandidate = it },
                    )
                }
            }
        }
    }

    unlinkCandidate?.let { target ->
        AlertDialog(
            onDismissRequest = { unlinkCandidate = null },
            title = { Text("Treat these as separate devices?") },
            text = {
                Text(
                    "${target.displayName} will appear as separate Cast and DLNA entries on future scans. " +
                        "You can restore automatic matching by resetting learned TV data in Settings.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        unlinkCandidate = null
                        viewModel.unlinkPhysicalTv(target)
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("Separate devices") }
            },
            dismissButton = {
                TextButton(
                    onClick = { unlinkCandidate = null },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun PhysicalTvGroup(
    targets: List<PhysicalTv>,
    empty: String,
    scanning: Boolean,
    playbackTargetId: String?,
    onSelect: (PhysicalTv) -> Unit,
    onUnlink: (PhysicalTv) -> Unit,
) {
    if (targets.isEmpty()) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(26.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                    Surface(
                        modifier = Modifier.size(48.dp),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.Tv, contentDescription = null, modifier = Modifier.size(26.dp))
                        }
                    }
                    if (scanning) CircularProgressIndicator(Modifier.fillMaxSize(), strokeWidth = 2.dp)
                }
                Text(
                    if (scanning) "Still looking…" else "No TVs found yet",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    empty,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        targets.forEach { target ->
            val playbackStarting = target.id == playbackTargetId
            val connectionSummary = target.connectionSummary()
            val targetIcon = if (target.castEndpoint != null && target.dlnaEndpoint == null) {
                Icons.Rounded.Cast
            } else {
                Icons.Rounded.Tv
            }
            Card(
                onClick = { onSelect(target) },
                enabled = playbackTargetId == null,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (playbackStarting) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                ),
                border = BorderStroke(
                    if (playbackStarting) 2.dp else 1.dp,
                    if (playbackStarting) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                ),
            ) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Surface(
                            modifier = Modifier.size(48.dp),
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(targetIcon, contentDescription = null, modifier = Modifier.size(26.dp))
                            }
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                target.displayName,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                target.connectionDetail(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (playbackStarting) {
                            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(26.dp),
                                    strokeWidth = 2.dp,
                                )
                            }
                        } else {
                            Surface(
                                modifier = Modifier.size(48.dp),
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Rounded.PlayArrow,
                                        contentDescription = "Play on ${target.displayName} via $connectionSummary",
                                    )
                                }
                            }
                        }
                    }
                    if (target.castEndpoint != null && target.dlnaEndpoint != null) {
                        TextButton(
                            onClick = { onUnlink(target) },
                            enabled = playbackTargetId == null,
                            modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp),
                        ) { Text("These are separate devices") }
                    }
                }
            }
        }
    }
}

private fun PhysicalTv.connectionSummary(): String = buildList {
    if (castEndpoint != null) add("Google Cast")
    if (dlnaEndpoint != null) add("DLNA")
}.joinToString(" + ")

private fun PhysicalTv.connectionDetail(): String {
    val summary = connectionSummary()
    val selectedEndpoint = selectEndpoint()
    return if (availableEndpoints.size > 1) {
        "$summary • ${selectedEndpoint?.protocol?.pickerLabel() ?: "Preferred connection"} selected"
    } else {
        val model = selectedEndpoint?.capabilities?.modelName?.trim()?.takeIf(String::isNotEmpty)
        "$summary • ${model ?: "Available now"}"
    }
}

private fun Protocol.pickerLabel(): String = when (this) {
    Protocol.CAST -> "Google Cast"
    Protocol.DLNA -> "DLNA"
}

@Composable
private fun TargetPickerHeader(
    scanning: Boolean,
    playbackStarting: Boolean,
    permissionGranted: Boolean,
    secondsUntilRefresh: Int,
    onRefresh: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("Nearby TVs", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    when {
                        !permissionGranted -> "Allow network access to find TVs nearby."
                        scanning -> "Searching this network…"
                        else -> "Refreshes automatically in ${secondsUntilRefresh}s"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FilledTonalIconButton(
                onClick = onRefresh,
                enabled = permissionGranted && !scanning && !playbackStarting,
                modifier = Modifier.size(48.dp),
            ) {
                if (scanning) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else Icon(Icons.Rounded.Refresh, contentDescription = "Refresh nearby TVs")
            }
        }
    }
}

@Composable
private fun LocalNetworkAccessCard(onOpenSettings: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "Let Stream Ferry see your TVs",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                "Local-network access lets this phone discover and talk to screens nearby.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            OutlinedButton(onClick = onOpenSettings, shape = RoundedCornerShape(18.dp)) {
                Text("Open app settings")
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------
// Playback controls — Material 3 Expressive styling
// ---------------------------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaybackScreen(state: AppUiState, viewModel: MainViewModel) {
    val p = state.playback
    if (p == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                ExpressiveLoadingIndicator(Modifier.size(56.dp), description = "Preparing stream")
                Text("Preparing stream…", style = MaterialTheme.typography.titleMedium)
            }
        }
        return
    }
    val livePosition = rememberSmoothPosition(p)
    val positionProvider = remember(livePosition) { { livePosition.value } }
    var showOptions by rememberSaveable { mutableStateOf(false) }
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )
    LaunchedEffect(p.isTerminal) {
        if (p.isTerminal) showOptions = false
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 700.dp
        val compactHeight = maxHeight < 610.dp
        val nowPlaying = state.nowPlayingItem
        val title = nowPlaying?.title ?: p.mediaTitle
        val chapters = nowPlaying?.chapters.orEmpty()
        val showingControlIssue = p.errorMessage == null && p.controls.issue != null
        val feedbackMessage = p.errorMessage ?: p.controls.issue?.message
        val previewUrlFor: (Int) -> ArtworkRef? = { index ->
            nowPlaying?.chapters?.getOrNull(index)?.artwork
        }

        if (p.isTerminal) {
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(if (compactHeight) 8.dp else 12.dp),
            ) {
                NowPlayingHero(p, mediaTitle = title, compact = compactHeight || !wide)
                TerminalPlaybackCard(
                    message = p.errorMessage ?: "Stream Ferry ran out of safe automatic options.",
                    onRetry = viewModel::retryPlayback,
                    onChangeTv = viewModel::changeTv,
                    onStop = viewModel::stopPlayback,
                    compact = compactHeight || !wide,
                )
                PlaylistCard(state, viewModel)
                if (p.attemptHistory.isNotEmpty()) PlaybackAttemptHistory(p.attemptHistory)
            }
        } else if (wide) {
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Column(
                    modifier = Modifier.weight(0.43f).fillMaxHeight().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(if (compactHeight) 8.dp else 12.dp),
                ) {
                    NowPlayingHero(p, mediaTitle = title, compact = compactHeight)
                    feedbackMessage?.let {
                        PlaybackIssueBanner(
                            message = it,
                            onOpenOptions = if (showingControlIssue) null else ({ showOptions = true }),
                            onDismiss = if (showingControlIssue) viewModel::dismissPlaybackControlIssue else null,
                            compact = compactHeight,
                        )
                    }
                    PlaybackQuickActions(
                        p = p,
                        viewModel = viewModel,
                        hasQueuedItem = state.playlist.isNotEmpty,
                        onOpenOptions = { showOptions = true },
                        onStop = viewModel::stopPlayback,
                    )
                    PlaylistCard(state, viewModel)
                }
                Column(
                    modifier = Modifier.weight(0.57f).fillMaxHeight().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(if (compactHeight) 7.dp else 11.dp),
                ) {
                    PlaybackTimelineControls(
                        p = p,
                        positionProvider = positionProvider,
                        chapters = chapters,
                        previewUrlFor = previewUrlFor,
                        viewModel = viewModel,
                        compact = compactHeight,
                    )
                }
            }
        } else {
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(if (compactHeight) 6.dp else 10.dp),
            ) {
                NowPlayingHero(p, mediaTitle = title, compact = true)
                feedbackMessage?.let {
                    PlaybackIssueBanner(
                        message = it,
                        onOpenOptions = if (showingControlIssue) null else ({ showOptions = true }),
                        onDismiss = if (showingControlIssue) viewModel::dismissPlaybackControlIssue else null,
                        compact = true,
                    )
                }
                PlaybackTimelineControls(
                    p = p,
                    positionProvider = positionProvider,
                    chapters = chapters,
                    previewUrlFor = previewUrlFor,
                    viewModel = viewModel,
                    compact = compactHeight,
                )
                PlaybackQuickActions(
                    p = p,
                    viewModel = viewModel,
                    hasQueuedItem = state.playlist.isNotEmpty,
                    onOpenOptions = { showOptions = true },
                    onStop = viewModel::stopPlayback,
                )
                PlaylistCard(state, viewModel)
            }
        }

        if (showOptions && !p.isTerminal) {
            PlaybackOptionsSheet(
                p = p,
                viewModel = viewModel,
                sheetState = sheetState,
                onDismiss = { showOptions = false },
            )
        }
    }
}

@Composable
private fun PlaybackTimelineControls(
    p: PlaybackUiState,
    positionProvider: () -> Long,
    chapters: List<MediaChapter>,
    previewUrlFor: (Int) -> ArtworkRef?,
    viewModel: MainViewModel,
    compact: Boolean,
) {
    SeekScrubber(
        p = p,
        positionProvider = positionProvider,
        chapters = chapters,
        previewUrlFor = previewUrlFor,
        onSeek = viewModel::seekTo,
    )
    p.skipSegmentLabel?.let { label ->
        SkipSegmentButton(label, onSkip = viewModel::skipSegment, compact = compact)
    }
    TransportControls(p, viewModel, compact = compact)
    if (p.volumeSupported) VolumeControl(p, viewModel, compact = true)
}

@Composable
private fun PlaybackIssueBanner(
    message: String,
    onOpenOptions: (() -> Unit)?,
    onDismiss: (() -> Unit)?,
    compact: Boolean,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(if (compact) 18.dp else 22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = if (compact) 9.dp else 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                Icons.Rounded.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                maxLines = if (compact) 1 else 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            onOpenOptions?.let { openOptions ->
                IconButton(onClick = openOptions) {
                    Icon(Icons.Rounded.Tune, contentDescription = "Playback options")
                }
            }
            onDismiss?.let { dismiss ->
                IconButton(onClick = dismiss) {
                    Icon(Icons.Rounded.Close, contentDescription = "Dismiss playback message")
                }
            }
        }
    }
}

@Composable
private fun TerminalPlaybackCard(
    message: String,
    onRetry: () -> Unit,
    onChangeTv: () -> Unit,
    onStop: () -> Unit,
    compact: Boolean,
) {
    Card(
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Assertive },
        shape = RoundedCornerShape(if (compact) 18.dp else 22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Button(
                onClick = onRetry,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Icon(Icons.Rounded.Refresh, contentDescription = null)
                Text("Retry")
            }
            OutlinedButton(
                onClick = onChangeTv,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Icon(Icons.Rounded.Tv, contentDescription = null)
                Text("Change TV")
            }
            OutlinedButton(
                onClick = onStop,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Icon(Icons.Rounded.Stop, contentDescription = null)
                Text("Stop")
            }
        }
    }
}

@Composable
private fun PlaybackQuickActions(
    p: PlaybackUiState,
    viewModel: MainViewModel,
    hasQueuedItem: Boolean,
    onOpenOptions: () -> Unit,
    onStop: () -> Unit,
) {
    val showAudio = p.audioTracks.size > 1
    val showSubtitles = p.subtitleTracks.isNotEmpty()
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showAudio) {
                val current = p.audioTracks.firstOrNull { it.index == p.currentAudioIndex }
                QuickTrackAction(
                    icon = Icons.Rounded.Audiotrack,
                    label = "Audio",
                    selectedLabel = current?.label ?: "Default",
                    options = p.audioTracks.map { it.index to it.label },
                    onSelect = viewModel::selectAudioTrack,
                    modifier = Modifier.weight(1f),
                )
            }
            if (showSubtitles) {
                val current = p.subtitleTracks.firstOrNull { it.index == p.currentSubtitleIndex }
                QuickTrackAction(
                    icon = Icons.Rounded.Subtitles,
                    label = "Captions",
                    selectedLabel = current?.label ?: "Off",
                    options = listOf<Pair<Int?, String>>(null to "Off") + p.subtitleTracks.map { it.index to it.label },
                    onSelect = viewModel::selectSubtitleTrack,
                    modifier = Modifier.weight(1f),
                )
            }
            if (hasQueuedItem) {
                QuickControlButton(
                    icon = Icons.Rounded.SkipNext,
                    label = "Next",
                    contentDescription = "Skip to the next playlist item",
                    onClick = viewModel::skipToNextPlaylistItem,
                    modifier = Modifier.weight(1f),
                )
            }
            QuickControlButton(
                icon = Icons.Rounded.Tune,
                label = "Options",
                contentDescription = "Playback options and troubleshooting",
                onClick = onOpenOptions,
                modifier = Modifier.weight(1f),
            )
            QuickControlButton(
                icon = Icons.Rounded.Stop,
                label = "Stop",
                contentDescription = "Stop playing on TV",
                onClick = onStop,
                danger = true,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun PlaylistCard(state: AppUiState, viewModel: MainViewModel) {
    val entries = state.playlist.entries
    var expanded by rememberSaveable { mutableStateOf(false) }
    val visibleEntries = if (expanded) entries else entries.take(4)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(Icons.Rounded.VideoLibrary, contentDescription = null)
                Column(Modifier.weight(1f)) {
                    Text("Playlist", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(
                        if (entries.isEmpty()) "Nothing queued yet" else "${entries.size} ${if (entries.size == 1) "item" else "items"} up next",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (entries.isNotEmpty()) {
                    TextButton(onClick = viewModel::clearPlaylist) { Text("Clear") }
                }
            }
            if (entries.isEmpty()) {
                Text(
                    "Keep browsing, then use Add to playlist on a video, episode, or movie.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                visibleEntries.forEachIndexed { index, entry ->
                    val availability = state.availabilityFor(entry.item)
                    val availabilityLabel = when (availability) {
                        JellyfinItemAvailability.DOWNLOADED -> "Downloaded · available offline"
                        JellyfinItemAvailability.UNAVAILABLE -> "Unavailable until Jellyfin reconnects"
                        JellyfinItemAvailability.AVAILABLE -> entry.item.subtitle ?: "Ready after the current item"
                    }
                    val availabilityColor = if (availability == JellyfinItemAvailability.UNAVAILABLE) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            "${index + 1}",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Text(
                                entry.item.title,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                availabilityLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = availabilityColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(onClick = { viewModel.removePlaylistEntry(entry.entryId) }) {
                            Icon(Icons.Rounded.Close, contentDescription = "Remove ${entry.item.title} from playlist")
                        }
                    }
                }
                if (entries.size > visibleEntries.size) {
                    TextButton(onClick = { expanded = true }, modifier = Modifier.align(Alignment.End)) {
                        Text("Show ${entries.size - visibleEntries.size} more")
                    }
                } else if (expanded && entries.size > 4) {
                    TextButton(onClick = { expanded = false }, modifier = Modifier.align(Alignment.End)) {
                        Text("Show less")
                    }
                }
            }
        }
    }
}

@Composable
private fun <T> QuickTrackAction(
    icon: ImageVector,
    label: String,
    selectedLabel: String,
    options: List<Pair<T, String>>,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        QuickControlButton(
            icon = icon,
            label = label,
            contentDescription = "$label: $selectedLabel",
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (value, optionLabel) ->
                DropdownMenuItem(
                    text = { Text(optionLabel) },
                    onClick = {
                        expanded = false
                        onSelect(value)
                    },
                )
            }
        }
    }
}

@Composable
private fun QuickControlButton(
    icon: ImageVector,
    label: String,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    danger: Boolean = false,
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        FilledTonalIconButton(
            onClick = onClick,
            colors = if (danger) {
                IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                )
            } else {
                IconButtonDefaults.filledTonalIconButtonColors()
            },
        ) {
            Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(22.dp))
        }
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaybackOptionsSheet(
    p: PlaybackUiState,
    viewModel: MainViewModel,
    sheetState: androidx.compose.material3.SheetState,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.88f),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "playback-options-heading") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "Playback options",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.ExtraBold,
                    )
                    Text(
                        "Use these overrides when compatibility or network quality causes trouble. Auto is best most of the time.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item(key = "playback-options-adaptive") {
                TvOutputCard(
                    p = p,
                    onSelectQuality = viewModel::selectQuality,
                    onSelectCodec = viewModel::selectPreferredCodec,
                    onSelectResolution = viewModel::selectMaxVideoHeight,
                )
            }
            if (p.attemptHistory.isNotEmpty()) {
                item(key = "playback-options-attempts") {
                    PlaybackAttemptHistory(p.attemptHistory)
                }
            }
        }
    }
}

@Composable
private fun PlaybackAttemptHistory(attempts: List<PlaybackAttemptDescriptor>) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text("Technical attempt details", modifier = Modifier.weight(1f), textAlign = TextAlign.Start)
                Text(if (expanded) "Hide" else "Show")
            }
            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    attempts.forEachIndexed { index, attempt ->
                        Text(
                            "${index + 1}. ${attempt.toUiSummary()}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

private fun PlaybackAttemptDescriptor.toUiSummary(): String = buildList {
    protocol?.takeIf(String::isNotBlank)?.let(::add)
    route?.name?.lowercase()?.replace('_', ' ')?.let(::add)
    codec?.takeIf(String::isNotBlank)?.let(::add)
    startPositionSeconds?.let { add("from ${formatPlaybackTime(it)}") }
    failureStage?.name?.lowercase()?.replace('_', ' ')?.let { add("failed at $it") }
    reason?.takeIf(String::isNotBlank)?.let(::add)
}.joinToString(" · ").ifBlank { "Playback attempt" }

@Composable
private fun SkipSegmentButton(label: String, onSkip: () -> Unit, compact: Boolean = false) {
    // A prominent Netflix-style skip action shown while playback sits inside an intro/outro/recap segment.
    Button(
        onClick = onSkip,
        modifier = Modifier.fillMaxWidth().height(if (compact) 44.dp else 52.dp),
        shape = RoundedCornerShape(if (compact) 16.dp else 18.dp),
    ) {
        Icon(Icons.Rounded.SkipNext, contentDescription = null, modifier = Modifier.size(22.dp))
        Text("  $label", style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun NowPlayingHero(p: PlaybackUiState, mediaTitle: String?, compact: Boolean = false) {
    val statusLabel = when (p.phase) {
        PlaybackPhase.CONNECTING -> "Connecting"
        PlaybackPhase.PREPARING -> "Preparing"
        PlaybackPhase.LOADING -> "Loading"
        PlaybackPhase.WAITING_FOR_PLAYBACK -> "Starting"
        PlaybackPhase.PLAYING -> "Playing"
        PlaybackPhase.PAUSED -> "Paused"
        PlaybackPhase.BUFFERING -> "Buffering"
        PlaybackPhase.RECONNECTING -> "Reconnecting"
        PlaybackPhase.CHANGING_STREAM -> "Changing stream"
        PlaybackPhase.CHANGING_PROTOCOL -> "Trying another connection"
        PlaybackPhase.STOPPED -> "Stopped"
        PlaybackPhase.COMPLETED -> "Completed"
        PlaybackPhase.FAILED -> "Needs attention"
    }
    ElevatedCard(
        shape = RoundedCornerShape(if (compact) 24.dp else 28.dp),
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(if (compact) 13.dp else 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 16.dp),
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(if (compact) 46.dp else 56.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        if (p.protocol.equals("CAST", true)) Icons.Rounded.Cast else Icons.Rounded.Tv,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(if (compact) 24.dp else 28.dp),
                    )
                }
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    mediaTitle?.takeIf { it.isNotBlank() } ?: "Now playing",
                    style = if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = if (compact) 1 else 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        p.targetName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            if (p.phase in setOf(
                                    PlaybackPhase.CONNECTING,
                                    PlaybackPhase.PREPARING,
                                    PlaybackPhase.LOADING,
                                    PlaybackPhase.WAITING_FOR_PLAYBACK,
                                    PlaybackPhase.BUFFERING,
                                    PlaybackPhase.RECONNECTING,
                                    PlaybackPhase.CHANGING_STREAM,
                                    PlaybackPhase.CHANGING_PROTOCOL,
                                )
                            ) {
                                CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp)
                            }
                            Text(statusLabel, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                if (!compact && p.streamMode.isNotBlank()) {
                    Text(
                        p.streamMode,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.76f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}


/**
 * Seekable progress bar: drag or tap to seek (committed on release), with a floating thumbnail
 * **preview** of the scrubbed position sourced from Jellyfin chapter ("section") images, plus subtle
 * chapter tick marks. The preview is shown on THIS phone only — the TV never receives an image or URL.
 */
@Composable
private fun SeekScrubber(
    p: PlaybackUiState,
    positionProvider: () -> Long,
    chapters: List<MediaChapter>,
    previewUrlFor: (chapterIndex: Int) -> ArtworkRef?,
    onSeek: (Long) -> Unit,
) {
    val duration = p.durationSeconds?.takeIf { it > 0 }
    val controls = PlaybackControlPolicy.evaluate(p.phase, duration)
    val seekable = controls.canSeekTimeline
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    var showExactSeek by rememberSaveable { mutableStateOf(false) }
    val position = positionProvider() // one read (subscribes THIS composable to the smooth-position tick)
    val playFraction = duration?.let { (position.toFloat() / it).coerceIn(0f, 1f) } ?: 0f
    val shown = if (dragging) dragFraction else playFraction
    val scrubSeconds = duration?.let { (shown * it).toLong() } ?: 0L
    val chapterIdx = if (chapters.isEmpty()) null
        else chapterIndexForPosition(chapters.map { it.startSeconds }, scrubSeconds)

    LaunchedEffect(seekable) {
        // If recovery/reload begins while the keyboard dialog is open, dismiss its stale command instead
        // of letting IME Done queue a seek against a stream that is currently being replaced.
        if (!seekable) showExactSeek = false
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // The wave remains visually compact, while the pointer/semantics surface meets Android's
        // 48 dp minimum touch target for taps, drags, switch access, and motor accessibility.
        BoxWithConstraints(Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            val density = LocalDensity.current
            val trackWidthPx = constraints.maxWidth.toFloat()
            val tickColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)

            Box(
                Modifier
                    .fillMaxSize()
                    .semantics {
                        contentDescription = "Playback position"
                        stateDescription = if (duration != null) {
                            "${formatClock(scrubSeconds)} of ${formatClock(duration)}"
                        } else {
                            "Live stream"
                        }
                        progressBarRangeInfo = duration?.let {
                            ProgressBarRangeInfo(shown, 0f..1f)
                        } ?: ProgressBarRangeInfo.Indeterminate
                        if (duration != null) {
                            setProgress { requested ->
                                onSeek((requested.coerceIn(0f, 1f) * duration).toLong())
                                true
                            }
                        }
                    }
                    .then(
                        if (seekable && duration != null) Modifier.pointerInput(duration) {
                            detectHorizontalDragGestures(
                                onDragStart = { o -> dragging = true; dragFraction = (o.x / size.width).coerceIn(0f, 1f) },
                                onHorizontalDrag = { c, _ -> dragFraction = (c.position.x / size.width).coerceIn(0f, 1f) },
                                onDragEnd = { dragging = false; onSeek((dragFraction * duration).toLong()) },
                                onDragCancel = { dragging = false },
                            )
                        } else Modifier,
                    )
                    .then(
                        if (seekable && duration != null) Modifier.pointerInput(duration) {
                            detectTapGestures { o -> onSeek(((o.x / size.width).coerceIn(0f, 1f) * duration).toLong()) }
                        } else Modifier,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.fillMaxWidth().height(26.dp)) {
                    WaveBar(
                        fraction = shown,
                        playing = p.displayedIsPlaying && !dragging,
                        indeterminate = duration == null,
                        modifier = Modifier.fillMaxSize(),
                    )
                    if (duration != null && chapters.size > 1) {
                        Canvas(Modifier.fillMaxSize()) {
                            val tickH = size.height * 0.55f
                            val top = (size.height - tickH) / 2f
                            chapters.forEach { c ->
                                val f = (c.startSeconds.toFloat() / duration).coerceIn(0f, 1f)
                                if (f > 0f && f < 1f) {
                                    val x = size.width * f
                                    drawLine(
                                        tickColor,
                                        Offset(x, top),
                                        Offset(x, top + tickH),
                                        strokeWidth = 1.5.dp.toPx(),
                                        cap = StrokeCap.Round,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (dragging && duration != null) {
                val bubbleW = with(density) { 156.dp.toPx() }
                val bubbleH = with(density) { 142.dp.toPx() }
                val gap = with(density) { 10.dp.toPx() }
                val x = (shown * trackWidthPx - bubbleW / 2f)
                    .coerceIn(0f, (trackWidthPx - bubbleW).coerceAtLeast(0f))
                Popup(
                    alignment = Alignment.TopStart,
                    offset = IntOffset(x.roundToInt(), -(bubbleH + gap).roundToInt()),
                    properties = PopupProperties(focusable = false),
                ) {
                    SeekPreviewBubble(
                        timeLabel = formatClock(scrubSeconds),
                        chapterName = chapterIdx?.let { chapters.getOrNull(it)?.name },
                        imageUrl = chapterIdx?.let { previewUrlFor(it) },
                    )
                }
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                formatClock(if (dragging) scrubSeconds else position),
                style = MaterialTheme.typography.labelLarge,
            )
            TextButton(
                onClick = { showExactSeek = true },
                enabled = seekable,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
            ) {
                Text("Jump to…")
            }
            Text(
                duration?.let { formatClock(it) } ?: "live",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (showExactSeek && seekable && duration != null) {
        ExactSeekDialog(
            currentSeconds = position.coerceIn(0L, duration),
            durationSeconds = duration,
            onDismiss = { showExactSeek = false },
            onSeek = { exact ->
                showExactSeek = false
                onSeek(exact)
            },
        )
    }
}

@Composable
private fun ExactSeekDialog(
    currentSeconds: Long,
    durationSeconds: Long,
    onDismiss: () -> Unit,
    onSeek: (Long) -> Unit,
) {
    val initial = formatClock(currentSeconds)
    var input by remember {
        mutableStateOf(TextFieldValue(initial, selection = TextRange(0, initial.length)))
    }
    var invalid by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val dismiss = {
        keyboard?.hide()
        onDismiss()
    }
    val submit = {
        val exact = PlaybackTimecode.parse(input.text, durationSeconds)
        if (exact == null) {
            invalid = true
        } else {
            keyboard?.hide()
            onSeek(exact)
        }
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboard?.show()
    }
    AlertDialog(
        onDismissRequest = dismiss,
        modifier = Modifier.imePadding(),
        title = { Text("Jump to exact time") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { updated ->
                        if (updated.text.length <= 12 && updated.text.all { it.isDigit() || it == ':' }) {
                            input = updated
                            invalid = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                    label = { Text("Time") },
                    placeholder = { Text("MM:SS or HH:MM:SS") },
                    supportingText = {
                        Text(
                            if (invalid) "Enter a valid time from 0:00 to ${formatClock(durationSeconds)}."
                            else "Seconds, MM:SS, and HH:MM:SS are supported.",
                        )
                    },
                    isError = invalid,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = submit, enabled = input.text.isNotBlank()) { Text("Jump") }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } },
    )
}

/** Floating thumbnail preview shown above the scrubber while seeking. Fixed size for stable placement. */
@Composable
private fun SeekPreviewBubble(timeLabel: String, chapterName: String?, imageUrl: ArtworkRef?) {
    ElevatedCard(shape = RoundedCornerShape(16.dp), modifier = Modifier.width(156.dp).height(142.dp)) {
        Column(Modifier.fillMaxSize()) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth().height(88.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (imageUrl != null) {
                        AsyncImage(
                            model = imageUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Icon(
                            Icons.Rounded.Movie,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(28.dp),
                        )
                    }
                }
            }
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    timeLabel,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (!chapterName.isNullOrBlank()) {
                    Text(
                        chapterName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * A custom Material 3 Expressive-style progress bar. Its sine silhouette communicates playback without
 * continuous decorative motion; it flattens smoothly when paused and retains a clear position thumb.
 */
@Composable
private fun WaveBar(fraction: Float, playing: Boolean, indeterminate: Boolean, modifier: Modifier) {
    val activeColor = MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    // Keep a static phase to avoid perpetual decorative frame churn. Playback-state changes still
    // animate briefly through amplitudeFraction, retaining useful motion without continuous motion.
    val phase = 0f
    val amplitudeFraction by animateFloatAsState(
        targetValue = if (playing || indeterminate) 1f else 0f,
        animationSpec = tween(350),
        label = "amplitude",
    )
    Canvas(modifier) {
        val width = size.width
        val midY = size.height / 2f
        val strokePx = 5.dp.toPx()
        val maxAmplitude = (size.height / 2f) - strokePx
        val amplitude = maxAmplitude * amplitudeFraction
        val wavelength = 36.dp.toPx().coerceAtLeast(1f)
        val k = (2f * PI.toFloat()) / wavelength
        val progressX = if (indeterminate) width else (width * fraction).coerceIn(0f, width)

        val path = Path().apply {
            moveTo(0f, midY + amplitude * sin(-phase))
            var x = 0f
            val step = 2.dp.toPx().coerceAtLeast(1f)
            while (x <= progressX) {
                lineTo(x, midY + amplitude * sin(k * x - phase))
                x += step
            }
        }
        drawPath(path, activeColor, style = Stroke(width = strokePx, cap = StrokeCap.Round))

        if (progressX < width) {
            drawLine(
                trackColor,
                start = Offset(progressX, midY),
                end = Offset(width, midY),
                strokeWidth = strokePx,
                cap = StrokeCap.Round,
            )
        }
        if (!indeterminate) {
            drawCircle(activeColor, radius = strokePx * 1.7f, center = Offset(progressX, midY))
        }
    }
}

@Composable
private fun TransportControls(
    p: PlaybackUiState,
    viewModel: MainViewModel,
    compact: Boolean = false,
) {
    val duration = p.durationSeconds?.takeIf { it > 0 }
    val controls = PlaybackControlPolicy.evaluate(p.phase, duration)
    val displayedPlaying = p.displayedIsPlaying
    val awaitingTv = p.controls.playPause != null || controls.isTransitioning
    val interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val playScale by animateFloatAsState(
        targetValue = if (pressed) 0.92f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "transport-play-press",
    )
    val sideSize = if (compact) 54.dp else 64.dp
    val playSize = if (compact) 80.dp else 96.dp
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(if (compact) 16.dp else 20.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilledTonalIconButton(
            onClick = { viewModel.skipBy(-30) },
            enabled = controls.canSkip,
            modifier = Modifier.size(sideSize),
            shape = RoundedCornerShape(if (compact) 18.dp else 20.dp),
        ) {
            Icon(Icons.Rounded.Replay30, contentDescription = "Back 30 seconds", modifier = Modifier.size(if (compact) 27.dp else 30.dp))
        }
        FilledIconButton(
            onClick = { viewModel.togglePlayPause() },
            enabled = controls.canPlayPause,
            interactionSource = interactionSource,
            modifier = Modifier.size(playSize).graphicsLayer {
                scaleX = playScale
                scaleY = playScale
            },
            shape = CircleShape,
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (awaitingTv) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(if (compact) 62.dp else 74.dp),
                        strokeWidth = 3.dp,
                        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.65f),
                    )
                }
                Icon(
                    if (displayedPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = when {
                        p.controls.playPause != null && displayedPlaying -> "Play requested; tap to pause"
                        p.controls.playPause != null -> "Pause requested; tap to play"
                        displayedPlaying -> "Pause"
                        else -> "Play"
                    },
                    modifier = Modifier.size(if (compact) 40.dp else 48.dp),
                )
            }
        }
        FilledTonalIconButton(
            onClick = { viewModel.skipBy(30) },
            enabled = controls.canSkip,
            modifier = Modifier.size(sideSize),
            shape = RoundedCornerShape(if (compact) 18.dp else 20.dp),
        ) {
            Icon(Icons.Rounded.Forward30, contentDescription = "Forward 30 seconds", modifier = Modifier.size(if (compact) 27.dp else 30.dp))
        }
    }
}

@Composable
private fun VolumeControl(p: PlaybackUiState, viewModel: MainViewModel, compact: Boolean = false) {
    // Drive the device volume only when the user releases the slider — onValueChange fires ~60x/s during
    // a drag, which would flood a DLNA renderer (one blocking SOAP call each) and delay other commands.
    // Local drag state keeps the thumb responsive while dragging.
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(p.volume) }
    val shown = (if (dragging) dragValue else p.displayedVolume).coerceIn(0f, 1f)
    Card(shape = RoundedCornerShape(if (compact) 18.dp else 22.dp), modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = if (compact) 12.dp else 16.dp, vertical = if (compact) 2.dp else 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(modifier = Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                if (p.controls.volume != null && !dragging) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(32.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.65f),
                    )
                }
                Icon(
                    if (shown < 0.5f) Icons.AutoMirrored.Rounded.VolumeDown else Icons.AutoMirrored.Rounded.VolumeUp,
                    contentDescription = "TV volume ${(shown * 100).roundToInt()} percent",
                    modifier = Modifier.size(22.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Slider(
                value = shown,
                onValueChange = { dragging = true; dragValue = it },
                onValueChangeFinished = { dragging = false; viewModel.setVolume(dragValue) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun <T> PickerRow(
    icon: ImageVector,
    label: String,
    selectedLabel: String,
    options: List<Pair<T, String>>,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.width(84.dp))
        Box(Modifier.weight(1f)) {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                Text(selectedLabel, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { (value, optLabel) ->
                    DropdownMenuItem(
                        text = { Text(optLabel) },
                        onClick = { expanded = false; onSelect(value) },
                    )
                }
            }
        }
    }
}

@Composable
private fun TvOutputCard(p: PlaybackUiState, onSelectQuality: (Long?) -> Unit, onSelectCodec: (String?) -> Unit, onSelectResolution: (Int?) -> Unit) {
    Card(
        shape = RoundedCornerShape(22.dp),
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    Icons.Rounded.Tv,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Text(
                    "TV output",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            Text(
                "Override automatic playback when the TV needs a specific format or resolution.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            val formatMenu = TvOutputMenu.formatOptions(
                availableVideoCodecs = p.availableVideoCodecs,
                preferredVideoCodec = p.preferredVideoCodec,
            )
            if (formatMenu.size > 1) {
                PickerRow(
                    icon = Icons.Rounded.Memory,
                    label = "Output format",
                    selectedLabel = formatMenu.firstOrNull { it.isSelected }?.label ?: "Auto",
                    options = formatMenu.map { it.codec to it.label },
                    onSelect = onSelectCodec,
                )
            }
            p.automaticMaxVideoHeight?.let { automaticCap ->
                val resolutionMenu = TvOutputMenu.resolutionOptions(
                    automaticMaxHeightPx = automaticCap,
                    manualMaxHeightPx = p.maxVideoHeight.takeIf { p.isManualMaxVideoHeight },
                )
                PickerRow(
                    icon = Icons.Rounded.Hd,
                    label = "Output resolution",
                    selectedLabel = resolutionMenu.firstOrNull { it.isSelected }?.label ?: "Auto",
                    options = resolutionMenu.map { it.heightPx to it.label },
                    onSelect = onSelectResolution,
                )
            }
            // Manual quality picker — Auto (adaptive) plus each bitrate rung. Shown only when there's a real
            // choice (an online session with >1 rung); a pinned rung pauses adaptation until Auto is picked.
            if (p.availableBitratesBps.size > 1) {
                val menu = QualityMenu.options(
                    p.availableBitratesBps,
                    pinnedBitrateBps = if (p.isManualQuality) p.currentBitrateBps else null,
                )
                PickerRow(
                    icon = Icons.Rounded.Tune,
                    label = "Quality cap",
                    selectedLabel = menu.firstOrNull { it.isSelected }?.label ?: "Auto",
                    options = menu.map { it.bitrateBps to it.label },
                    onSelect = onSelectQuality,
                )
            }
            StatRow(Icons.Rounded.Tv, "Stream", p.streamMode.ifBlank { "—" })
            p.sourceFormat?.let { StatRow(Icons.Rounded.Movie, "Source", it) }
            p.outputFormat?.let { StatRow(Icons.Rounded.Memory, "Output", it) }
            p.videoWidth?.let { w ->
                p.videoHeight?.let { h ->
                    StatRow(Icons.Rounded.Hd, "Resolution", "${w}\u00D7$h${resolutionLabel(h)}")
                }
            }
            p.videoBitrateBps?.takeIf { it > 0 }?.let {
                StatRow(Icons.Rounded.HighQuality, "Image bitrate", "${formatMbps(it)} Mbps")
            }
            StatRow(Icons.Rounded.Speed, "Streaming at", "${formatMbps(p.currentBitrateBps)} Mbps")
            StatRow(Icons.Rounded.GraphicEq, "Measured link", "${formatMbps(p.measuredThroughputBps)} Mbps")
            if (p.adaptiveNote.isNotBlank()) {
                Text(
                    p.adaptiveNote,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }
}

@Composable
private fun StatRow(icon: ImageVector, label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

private fun formatMbps(bps: Long): String = "%.1f".format(bps / 1_000_000.0)

private fun formatPlaybackTime(seconds: Long): String {
    val safe = seconds.coerceAtLeast(0)
    val hours = safe / 3_600
    val minutes = (safe % 3_600) / 60
    val remainingSeconds = safe % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, remainingSeconds)
    else "%d:%02d".format(minutes, remainingSeconds)
}

/** A friendly tag for high resolutions ("4K"/"8K"); lower ones are already clear from the WxH dimensions. */
private fun resolutionLabel(heightPx: Int): String = when {
    heightPx >= 4320 -> " (8K)"
    heightPx >= 2160 -> " (4K)"
    else -> ""
}

/**
 * The renderer only reports a new position on each status update / DLNA poll (up to seconds apart), so the
 * raw value jumps. This snaps to the renderer's authoritative position on every update, then advances
 * locally (250 ms tick) while actively playing so the scrubber tracks the TV smoothly — corrected again on
 * the next update, and clamped to the duration. Not used while dragging (the scrubber shows the drag).
 */
@Composable
private fun rememberSmoothPosition(p: PlaybackUiState): State<Long> {
    val displayedPosition = p.displayedPositionSeconds
    val displayedPlaying = p.displayedIsPlaying
    val shown = remember { mutableLongStateOf(displayedPosition) }
    LaunchedEffect(displayedPosition, displayedPlaying, p.isBuffering, p.durationSeconds) {
        shown.longValue = displayedPosition
        if (displayedPlaying && !p.isBuffering) {
            val base = displayedPosition
            val startMs = SystemClock.elapsedRealtime()
            while (true) {
                delay(250)
                val next = base + (SystemClock.elapsedRealtime() - startMs) / 1000
                shown.longValue = p.durationSeconds?.let { next.coerceAtMost(it) } ?: next
            }
        }
    }
    return shown
}

// ---------------------------------------------------------------------------------------------------
// Design-time @Preview composables (no ViewModel required); R8 strips them from the release build.
// ---------------------------------------------------------------------------------------------------

private val samplePlayback = PlaybackUiState(
    targetName = "Living Room TV",
    protocol = "CAST",
    isPlaying = true,
    phase = PlaybackPhase.PLAYING,
    positionSeconds = 1_325,
    durationSeconds = 3_600,
    streamMode = "Direct play · H.264 / AAC",
    currentBitrateBps = 12_000_000,
    measuredThroughputBps = 38_500_000,
    adaptiveNote = "Streaming the original at full quality.",
    availableBitratesBps = listOf(1_500_000, 3_000_000, 6_000_000, 12_000_000, 20_000_000),
    availableVideoCodecs = listOf("hevc", "h264"),
    automaticMaxVideoHeight = 2160,
    maxVideoHeight = 2160,
    volume = 0.7f,
)

@Preview(name = "Playback controls", showBackground = true, heightDp = 560)
@Preview(name = "Playback controls · dark", showBackground = true, heightDp = 560, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun PlaybackControlsPreview() {
    StreamFerryTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                NowPlayingHero(samplePlayback, "The Expanse · S3 E5")
                SeekScrubber(
                    p = samplePlayback,
                    positionProvider = { samplePlayback.positionSeconds },
                    chapters = listOf(
                        MediaChapter(0, "Intro", null),
                        MediaChapter(600, "Chapter 2", null),
                        MediaChapter(1800, "Finale", null),
                    ),
                    previewUrlFor = { null },
                    onSeek = {},
                )
                TvOutputCard(samplePlayback, onSelectQuality = {}, onSelectCodec = {}, onSelectResolution = {})
            }
        }
    }
}

@Preview(name = "Device picker", showBackground = true)
@Preview(name = "Device picker · dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun PhysicalTvGroupPreview() {
    val livingRoomEndpoint = DiscoveredTarget(
        id = "1",
        displayName = "Living Room TV",
        protocol = Protocol.CAST,
        capabilities = TargetCapabilities(Protocol.CAST, modelName = "Google TV"),
        lastTestedStatus = "Ready",
    )
    val targets = listOf(
        PhysicalTv(id = "living-room", displayName = "Living Room TV", castEndpoint = livingRoomEndpoint),
        PhysicalTv(
            id = "bedroom",
            displayName = "Bedroom TV",
            dlnaEndpoint = DiscoveredTarget(
                id = "2",
                displayName = "Bedroom TV",
                protocol = Protocol.DLNA,
                capabilities = TargetCapabilities(Protocol.DLNA, modelName = "webOS TV"),
                lastTestedStatus = null,
            ),
        ),
    )
    StreamFerryTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PhysicalTvGroup(
                    targets = targets,
                    empty = "No devices found.",
                    scanning = false,
                    playbackTargetId = null,
                    onSelect = {},
                    onUnlink = {},
                )
            }
        }
    }
}
