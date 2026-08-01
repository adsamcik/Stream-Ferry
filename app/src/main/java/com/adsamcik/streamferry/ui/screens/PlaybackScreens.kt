package com.adsamcik.streamferry.ui.screens

import android.content.res.Configuration
import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.Cast
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.mediarouter.app.MediaRouteButton
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import coil.compose.AsyncImage
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastButtonFactory
import com.adsamcik.streamferry.core.adaptive.QualityMenu
import com.adsamcik.streamferry.core.chapter.chapterIndexForPosition
import com.adsamcik.streamferry.core.stream.Protocol
import com.adsamcik.streamferry.core.stream.TargetCapabilities
import com.adsamcik.streamferry.domain.DiscoveredTarget
import com.adsamcik.streamferry.domain.MediaChapter
import com.adsamcik.streamferry.physical.PhysicalTv
import com.adsamcik.streamferry.playback.PlaybackAttemptDescriptor
import com.adsamcik.streamferry.playback.PlaybackPhase
import com.adsamcik.streamferry.ui.MainViewModel
import com.adsamcik.streamferry.ui.components.ExpressiveLoadingIndicator
import com.adsamcik.streamferry.ui.state.AppUiState
import com.adsamcik.streamferry.ui.state.PlaybackUiState
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
            item(key = "target-discovery-hero") {
                TargetDiscoveryHero(
                    scanning = state.isScanningTargets,
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

            item(key = "target-tv-heading") {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Nearby TVs", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        "Choose the screen itself. Stream Ferry will pick the best available connection.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
            item(key = "target-tv-list") {
                PhysicalTvGroup(
                    targets = state.physicalTvs,
                    selected = state.selectedPhysicalTv,
                    empty = "No compatible TVs yet. Make sure the TV is on and connected to this network.",
                    scanning = state.isScanningTargets,
                    onSelect = viewModel::selectPhysicalTv,
                    onUnlink = viewModel::unlinkPhysicalTv,
                )
            }
        }
    }
}

@Composable
private fun PhysicalTvGroup(
    targets: List<PhysicalTv>,
    selected: PhysicalTv?,
    empty: String,
    scanning: Boolean,
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
            val isSelected = target.id == selected?.id
            Card(
                onClick = { onSelect(target) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(if (isSelected) 28.dp else 22.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
                border = BorderStroke(
                    if (isSelected) 2.dp else 1.dp,
                    if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
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
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer,
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Rounded.Tv, contentDescription = null, modifier = Modifier.size(26.dp))
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
                            val models = target.availableEndpoints.mapNotNull { it.capabilities.modelName }
                                .map(String::trim).filter(String::isNotEmpty).distinct()
                            Text(
                                models.firstOrNull() ?: "Available now",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        RadioButton(selected = isSelected, onClick = null)
                    }
                    if (target.castEndpoint != null && target.dlnaEndpoint != null) {
                        TextButton(
                            onClick = { onUnlink(target) },
                            modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp),
                        ) { Text("These are separate devices") }
                    }
                }
            }
        }
    }
}

@Composable
private fun TargetDiscoveryHero(
    scanning: Boolean,
    permissionGranted: Boolean,
    secondsUntilRefresh: Int,
    onRefresh: () -> Unit,
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(32.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 4.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Box(modifier = Modifier.size(68.dp), contentAlignment = Alignment.Center) {
                    Surface(
                        modifier = Modifier.size(56.dp),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.tertiary,
                        contentColor = MaterialTheme.colorScheme.onTertiary,
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.Tv, contentDescription = null, modifier = Modifier.size(30.dp))
                        }
                    }
                    if (scanning) {
                        CircularProgressIndicator(
                            modifier = Modifier.fillMaxSize(),
                            strokeWidth = 3.dp,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        "Where should it play?",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                    Text(
                        if (scanning) "Looking around for nearby screens…" else "Pick a screen and the show is on",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.82f),
                    )
                }
            }

            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                contentColor = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(
                        when {
                            !permissionGranted -> "Auto-refresh starts after network access is allowed"
                            scanning -> "Auto-refresh is searching now"
                            else -> "Auto-refresh on · next search in ${secondsUntilRefresh}s"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            FilledTonalButton(
                onClick = onRefresh,
                enabled = !scanning,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(19.dp),
            ) {
                if (scanning) {
                    CircularProgressIndicator(Modifier.size(19.dp), strokeWidth = 2.dp)
                    Text("  Searching…")
                } else {
                    Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(20.dp))
                    Text("  Refresh now", fontWeight = FontWeight.Bold)
                }
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

@Composable
private fun CastTargetCard(
    castAvailable: Boolean,
    permissionGranted: Boolean,
    selected: DiscoveredTarget?,
    onSelect: (DiscoveredTarget) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected != null) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
        ),
        border = selected?.let { BorderStroke(2.dp, MaterialTheme.colorScheme.primary) },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Surface(
                modifier = Modifier.size(50.dp),
                shape = CircleShape,
                color = if (selected != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer,
                contentColor = if (selected != null) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Cast, contentDescription = null, modifier = Modifier.size(26.dp))
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("Google Cast", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    when {
                        !castAvailable -> "Google Cast isn't available on this phone"
                        !permissionGranted -> "Allow network access to choose a Cast screen"
                        selected != null -> "Selected: ${selected.displayName}"
                        else -> "Open Google's live device chooser"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                AnimatedVisibility(visible = selected != null) {
                    Text(
                        "Ready to play",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            if (castAvailable && permissionGranted) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    FrameworkCastRouteButton(onSelect = onSelect)
                    Text("Choose", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun TargetPlayDock(
    selected: DiscoveredTarget?,
    enabled: Boolean,
    onPlay: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        tonalElevation = 5.dp,
        shadowElevation = 7.dp,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Icon(Icons.Rounded.Tv, contentDescription = null, modifier = Modifier.size(19.dp))
                Text(
                    selected?.displayName ?: "Choose a TV above",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                selected?.let {
                    Text(
                        if (it.protocol == Protocol.CAST) "Cast" else "Smart TV",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Button(
                onClick = onPlay,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth().height(58.dp),
                shape = RoundedCornerShape(21.dp),
            ) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(22.dp))
                Text(
                    if (selected != null) "  Play on ${selected.displayName}" else "  Select a TV to play",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * The framework-owned Cast chooser. It provides the connected-device affordance and owns route
 * discovery/transfer semantics; the surrounding picker remains responsible for DLNA selection.
 *
 * A chosen route is mirrored into the app's existing target state, so tapping Play uses the exact
 * route the Cast framework selected rather than a stale custom-picker row.
 */

/** Safe policy baseline used only to turn the framework-selected route into the existing app target model. */
private val FRAMEWORK_CAST_BASELINE = TargetCapabilities(
    protocol = Protocol.CAST,
    supportedContainers = setOf("mp4"),
    supportedVideoCodecs = setOf("h264"),
    supportedAudioCodecs = setOf("aac", "mp3"),
    supportsHevc = false,
    supports10Bit = false,
    supportsHls = true,
    supportedExternalSubtitleFormats = setOf("vtt"),
)

@Composable
private fun FrameworkCastRouteButton(
    onSelect: (DiscoveredTarget) -> Unit,
) {
    val context = LocalContext.current
    val latestOnSelect by rememberUpdatedState(onSelect)
    val selector = remember {
        MediaRouteSelector.Builder()
            .addControlCategory(
                CastMediaControlIntent.categoryForCast(
                    CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID,
                ),
            )
            .build()
    }

    // The Cast controller owns the active scan while the picker is open. This passive callback only
    // mirrors a route selected through Google's framework chooser into the app's target state.
    DisposableEffect(context, selector) {
        val router = MediaRouter.getInstance(context)
        val callback = object : MediaRouter.Callback() {
            override fun onRouteSelected(
                router: MediaRouter,
                route: MediaRouter.RouteInfo,
                reason: Int,
            ) {
                // The framework route is authoritative. Constructing the selection from this callback
                // avoids depending on a delayed custom discovery snapshot that may already be stale.
                latestOnSelect(
                    DiscoveredTarget(
                        id = route.id,
                        displayName = route.name,
                        protocol = Protocol.CAST,
                        capabilities = FRAMEWORK_CAST_BASELINE.copy(modelName = route.description),
                        lastTestedStatus = null,
                    ),
                )
            }
        }
        router.addCallback(selector, callback)
        onDispose { router.removeCallback(callback) }
    }

    AndroidView(
        factory = { viewContext ->
            MediaRouteButton(viewContext).also { button ->
                CastButtonFactory.setUpMediaRouteButton(viewContext.applicationContext, viewContext.mainExecutor, button)
                button.contentDescription = "Choose a Google Cast TV"
            }
        },
        modifier = Modifier.size(48.dp),
    )
}

@Composable
private fun TargetGroup(
    targets: List<DiscoveredTarget>,
    selected: DiscoveredTarget?,
    empty: String,
    scanning: Boolean = false,
    onSelect: (DiscoveredTarget) -> Unit,
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
                    if (scanning) {
                        CircularProgressIndicator(Modifier.fillMaxSize(), strokeWidth = 2.dp)
                    }
                }
                Text(
                    if (scanning) "Still looking…" else "No other TVs found yet",
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
            TargetOptionCard(
                target = target,
                selected = target.id == selected?.id && target.protocol == selected.protocol,
                onClick = { onSelect(target) },
            )
        }
    }
}

@Composable
private fun TargetOptionCard(target: DiscoveredTarget, selected: Boolean, onClick: () -> Unit) {
    val interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.98f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "target-card-press",
    )
    val containerColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        animationSpec = tween(220),
        label = "target-card-color",
    )

    Card(
        onClick = onClick,
        interactionSource = interactionSource,
        modifier = Modifier.fillMaxWidth().graphicsLayer {
            scaleX = scale
            scaleY = scale
        },
        shape = RoundedCornerShape(if (selected) 28.dp else 22.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        border = BorderStroke(
            if (selected) 2.dp else 1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (selected) 3.dp else 0.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                modifier = Modifier.size(46.dp),
                shape = CircleShape,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer,
                contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Tv, contentDescription = null, modifier = Modifier.size(25.dp))
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
                    target.capabilities.modelName ?: if (target.protocol == Protocol.CAST) "Google Cast" else "Smart TV",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                target.lastTestedStatus?.let { status ->
                    Text(
                        status,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            RadioButton(selected = selected, onClick = null)
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

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 700.dp
        val compactHeight = maxHeight < 610.dp
        val title = state.selectedItem?.title
        val chapters = state.selectedItem?.chapters.orEmpty()
        val previewUrlFor: (Int) -> String? = { index ->
            state.selectedItem?.let { viewModel.chapterImageUrl(it, index, CHAPTER_PREVIEW_WIDTH_PX) }
        }

        if (wide) {
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Column(
                    modifier = Modifier.weight(0.43f).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(if (compactHeight) 8.dp else 12.dp),
                ) {
                    NowPlayingHero(p, mediaTitle = title ?: p.mediaTitle, compact = compactHeight)
                    if (p.isTerminal) {
                        TerminalPlaybackCard(
                            message = p.errorMessage ?: "Stream Ferry ran out of safe automatic options.",
                            onRetry = viewModel::retryPlayback,
                            onChangeTv = viewModel::changeTv,
                            onStop = viewModel::stopPlayback,
                            compact = compactHeight,
                        )
                    } else p.errorMessage?.let {
                        PlaybackIssueBanner(it, onOpenOptions = { showOptions = true }, compact = compactHeight)
                    }
                    Spacer(Modifier.weight(1f))
                    PlaybackQuickActions(
                        p = p,
                        viewModel = viewModel,
                        onOpenOptions = { showOptions = true },
                        onStop = viewModel::stopPlayback,
                    )
                }
                Column(
                    modifier = Modifier.weight(0.57f).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(if (compactHeight) 7.dp else 11.dp),
                ) {
                    Spacer(Modifier.weight(1f))
                    SeekScrubber(
                        p = p,
                        positionProvider = positionProvider,
                        chapters = chapters,
                        previewUrlFor = previewUrlFor,
                        onSeek = viewModel::seekTo,
                    )
                    p.skipSegmentLabel?.let { label ->
                        SkipSegmentButton(label, onSkip = viewModel::skipSegment, compact = compactHeight)
                    }
                    TransportControls(p, positionProvider, viewModel, compact = compactHeight)
                    if (p.volumeSupported) VolumeControl(p, viewModel, compact = true)
                    Spacer(Modifier.weight(1f))
                }
            }
        } else {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(if (compactHeight) 6.dp else 10.dp),
            ) {
                NowPlayingHero(p, mediaTitle = title ?: p.mediaTitle, compact = true)
                if (p.isTerminal) {
                    TerminalPlaybackCard(
                        message = p.errorMessage ?: "Stream Ferry ran out of safe automatic options.",
                        onRetry = viewModel::retryPlayback,
                        onChangeTv = viewModel::changeTv,
                        onStop = viewModel::stopPlayback,
                        compact = true,
                    )
                } else p.errorMessage?.let {
                    PlaybackIssueBanner(it, onOpenOptions = { showOptions = true }, compact = true)
                }
                Spacer(Modifier.weight(1f))
                SeekScrubber(
                    p = p,
                    positionProvider = positionProvider,
                    chapters = chapters,
                    previewUrlFor = previewUrlFor,
                    onSeek = viewModel::seekTo,
                )
                p.skipSegmentLabel?.let { label ->
                    SkipSegmentButton(label, onSkip = viewModel::skipSegment, compact = compactHeight)
                }
                TransportControls(p, positionProvider, viewModel, compact = compactHeight)
                if (p.volumeSupported) VolumeControl(p, viewModel, compact = true)
                PlaybackQuickActions(
                    p = p,
                    viewModel = viewModel,
                    onOpenOptions = { showOptions = true },
                    onStop = viewModel::stopPlayback,
                )
            }
        }

        if (showOptions) {
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
private fun PlaybackIssueBanner(message: String, onOpenOptions: () -> Unit, compact: Boolean) {
    Card(
        onClick = onOpenOptions,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(if (compact) 18.dp else 22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = if (compact) 9.dp else 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Rounded.Tune, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                maxLines = if (compact) 1 else 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                "Options",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = onRetry, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null)
                    Text("Retry")
                }
                OutlinedButton(onClick = onChangeTv, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Icon(Icons.Rounded.Tv, contentDescription = null)
                    Text("Change TV")
                }
                OutlinedButton(onClick = onStop, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(Icons.Rounded.Stop, contentDescription = "Stop")
                }
            }
        }
    }
}

@Composable
private fun PlaybackQuickActions(
    p: PlaybackUiState,
    viewModel: MainViewModel,
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
            p.errorMessage?.let { message ->
                item(key = "playback-options-error") {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ) {
                        Text(message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp))
                    }
                }
            }
            if (p.audioTracks.size > 1 || p.subtitleTracks.isNotEmpty()) {
                item(key = "playback-options-tracks") {
                    TrackControls(p, viewModel)
                }
            }
            item(key = "playback-options-adaptive") {
                AdaptiveCard(
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
private fun ReconnectingBanner() {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            CircularProgressIndicator(
                Modifier.size(22.dp).clearAndSetSemantics { },
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    "Reconnecting to the TV…",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                Text(
                    "The connection dropped — restoring playback from where it left off.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
        }
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

private const val CHAPTER_PREVIEW_WIDTH_PX = 384

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
    previewUrlFor: (chapterIndex: Int) -> String?,
    onSeek: (Long) -> Unit,
) {
    val duration = p.durationSeconds?.takeIf { it > 0 }
    val seekable = duration != null
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    val position = positionProvider() // one read (subscribes THIS composable to the smooth-position tick)
    val playFraction = duration?.let { (position.toFloat() / it).coerceIn(0f, 1f) } ?: 0f
    val shown = if (dragging) dragFraction else playFraction
    val scrubSeconds = duration?.let { (shown * it).toLong() } ?: 0L
    val chapterIdx = if (chapters.isEmpty()) null
        else chapterIndexForPosition(chapters.map { it.startSeconds }, scrubSeconds)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth().height(34.dp)) {
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
                        if (seekable) Modifier.pointerInput(duration) {
                            detectHorizontalDragGestures(
                                onDragStart = { o -> dragging = true; dragFraction = (o.x / size.width).coerceIn(0f, 1f) },
                                onHorizontalDrag = { c, _ -> dragFraction = (c.position.x / size.width).coerceIn(0f, 1f) },
                                onDragEnd = { dragging = false; onSeek((dragFraction * duration).toLong()) },
                                onDragCancel = { dragging = false },
                            )
                        } else Modifier,
                    )
                    .then(
                        if (seekable) Modifier.pointerInput(duration) {
                            detectTapGestures { o -> onSeek(((o.x / size.width).coerceIn(0f, 1f) * duration).toLong()) }
                        } else Modifier,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.fillMaxWidth().height(26.dp)) {
                    WaveBar(
                        fraction = shown,
                        playing = p.isPlaying && !dragging,
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
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                formatClock(if (dragging) scrubSeconds else position),
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                duration?.let { formatClock(it) } ?: "live",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Floating thumbnail preview shown above the scrubber while seeking. Fixed size for stable placement. */
@Composable
private fun SeekPreviewBubble(timeLabel: String, chapterName: String?, imageUrl: String?) {
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
    positionProvider: () -> Long,
    viewModel: MainViewModel,
    compact: Boolean = false,
) {
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
            onClick = { viewModel.seekTo((positionProvider() - 30).coerceAtLeast(0)) },
            modifier = Modifier.size(sideSize),
            shape = RoundedCornerShape(if (compact) 18.dp else 20.dp),
        ) {
            Icon(Icons.Rounded.Replay30, contentDescription = "Back 30 seconds", modifier = Modifier.size(if (compact) 27.dp else 30.dp))
        }
        FilledIconButton(
            onClick = { viewModel.togglePlayPause() },
            interactionSource = interactionSource,
            modifier = Modifier.size(playSize).graphicsLayer {
                scaleX = playScale
                scaleY = playScale
            },
            shape = CircleShape,
        ) {
            Icon(
                if (p.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = if (p.isPlaying) "Pause" else "Play",
                modifier = Modifier.size(if (compact) 40.dp else 48.dp),
            )
        }
        FilledTonalIconButton(
            onClick = { viewModel.seekTo(positionProvider() + 30) },
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
    // Local drag state keeps the thumb + percentage responsive while dragging.
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(p.volume) }
    val shown = (if (dragging) dragValue else p.volume).coerceIn(0f, 1f)
    Card(shape = RoundedCornerShape(if (compact) 18.dp else 22.dp), modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = if (compact) 12.dp else 16.dp, vertical = if (compact) 2.dp else 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.AutoMirrored.Rounded.VolumeDown, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Slider(
                value = shown,
                onValueChange = { dragging = true; dragValue = it },
                onValueChangeFinished = { dragging = false; viewModel.setVolume(dragValue) },
                modifier = Modifier.weight(1f),
            )
            Icon(Icons.AutoMirrored.Rounded.VolumeUp, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "${(shown * 100).toInt()}%",
                style = MaterialTheme.typography.labelLarge,
                textAlign = TextAlign.End,
                modifier = Modifier.width(44.dp),
            )
        }
    }
}

@Composable
private fun TrackControls(p: PlaybackUiState, viewModel: MainViewModel) {
    // Only for online (Jellyfin) playback that reports selectable tracks. Audio shows only when there's a
    // real choice (>1 track); subtitles always offer "Off" plus any languages the media carries.
    val showAudio = p.audioTracks.size > 1
    val showSubtitles = p.subtitleTracks.isNotEmpty()
    if (!showAudio && !showSubtitles) return
    Card(shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (showAudio) {
                val current = p.audioTracks.firstOrNull { it.index == p.currentAudioIndex }
                PickerRow(
                    icon = Icons.Rounded.Audiotrack,
                    label = "Audio",
                    selectedLabel = current?.label ?: "Default",
                    options = p.audioTracks.map { it.index to it.label },
                    onSelect = { viewModel.selectAudioTrack(it) },
                )
            }
            if (showSubtitles) {
                val current = p.subtitleTracks.firstOrNull { it.index == p.currentSubtitleIndex }
                PickerRow(
                    icon = Icons.Rounded.Subtitles,
                    label = "Subtitles",
                    selectedLabel = current?.label ?: "Off",
                    // "Off" (null) first, then each subtitle language. A chosen subtitle is burned in.
                    options = listOf<Pair<Int?, String>>(null to "Off") + p.subtitleTracks.map { it.index to it.label },
                    onSelect = { viewModel.selectSubtitleTrack(it) },
                )
            }
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
private fun AdaptiveCard(p: PlaybackUiState, onSelectQuality: (Long?) -> Unit, onSelectCodec: (String?) -> Unit, onSelectResolution: (Int?) -> Unit) {
    Card(
        shape = RoundedCornerShape(22.dp),
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    Icons.Rounded.GraphicEq,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Text(
                    "Jellyfin overrides",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
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
            // Manual codec picker — Auto (best codec the TV supports) plus each codec the TV can accept.
            p.maxVideoHeight?.let { cap ->
                val options = listOf<Pair<Int?, String>>(
                    null to "Auto (${cap}p)", 2160 to "4K (2160p)", 1080 to "1080p", 720 to "720p", 480 to "480p",
                )
                PickerRow(
                    icon = Icons.Rounded.Hd,
                    label = "Resolution cap",
                    selectedLabel = if (p.isManualMaxVideoHeight) "${cap}p" else "Auto (${cap}p)",
                    options = options,
                    onSelect = onSelectResolution,
                )
            }

            // Choosing one makes a server transcode use that codec.
            if (p.availableVideoCodecs.size > 1) {
                val codecOptions = listOf<Pair<String?, String>>(null to "Auto") +
                    p.availableVideoCodecs.map { it to codecLabel(it) }
                PickerRow(
                    icon = Icons.Rounded.Memory,
                    label = "Video format",
                    selectedLabel = p.preferredVideoCodec?.let { codecLabel(it) } ?: "Auto",
                    options = codecOptions,
                    onSelect = onSelectCodec,
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

/** Friendly label for a codec id used in the manual codec picker. */
private fun codecLabel(codec: String): String = when (codec.lowercase()) {
    "h264", "avc" -> "H.264"
    "hevc", "h265" -> "HEVC"
    "vp9" -> "VP9"
    "av1" -> "AV1"
    else -> codec.uppercase()
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
    val shown = remember { mutableLongStateOf(p.positionSeconds) }
    LaunchedEffect(p.positionSeconds, p.isPlaying, p.isBuffering, p.durationSeconds) {
        shown.longValue = p.positionSeconds
        if (p.isPlaying && !p.isBuffering) {
            val base = p.positionSeconds
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
                AdaptiveCard(samplePlayback, onSelectQuality = {}, onSelectCodec = {}, onSelectResolution = {})
            }
        }
    }
}

@Preview(name = "Device picker", showBackground = true)
@Preview(name = "Device picker · dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun TargetGroupPreview() {
    val targets = listOf(
        DiscoveredTarget(
            id = "1", displayName = "Living Room TV", protocol = Protocol.CAST,
            capabilities = TargetCapabilities(Protocol.CAST, modelName = "Chromecast"),
            lastTestedStatus = "Ready",
        ),
        DiscoveredTarget(
            id = "2", displayName = "Bedroom TV", protocol = Protocol.DLNA,
            capabilities = TargetCapabilities(Protocol.DLNA, modelName = "webOS TV"),
            lastTestedStatus = null,
        ),
    )
    StreamFerryTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TargetGroup(
                    targets = targets,
                    selected = targets.first(),
                    empty = "No devices found.",
                    onSelect = {},
                )
            }
        }
    }
}
