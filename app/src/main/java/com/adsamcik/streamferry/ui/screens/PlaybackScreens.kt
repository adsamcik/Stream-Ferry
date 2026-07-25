package com.adsamcik.streamferry.ui.screens

import android.content.res.Configuration
import android.os.SystemClock
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
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
import com.adsamcik.streamferry.ui.MainViewModel
import com.adsamcik.streamferry.ui.state.AppUiState
import com.adsamcik.streamferry.ui.state.PlaybackUiState
import com.adsamcik.streamferry.ui.theme.StreamFerryTheme
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

@Composable
fun TargetPickerScreen(state: AppUiState, viewModel: MainViewModel, onRescan: () -> Unit) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = onRescan, enabled = !state.isScanningTargets) {
                Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.padding(end = 4.dp))
                Text(if (state.isScanningTargets) "Scanning…" else "Scan for devices")
            }
            if (state.isScanningTargets) {
                CircularProgressIndicator(Modifier.padding(start = 12.dp).size(22.dp))
            }
        }

        if (!state.localNetworkPermissionGranted) {
            Text(
                "Local-network access is required before a TV can fetch the phone-hosted stream.",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(
                onClick = {
                    runCatching { context.startActivity(viewModel.localNetworkPermissionSettingsIntent()) }
                },
            ) {
                Text("Open app settings")
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Cast (preferred)", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (state.castAvailable && state.localNetworkPermissionGranted) {
                FrameworkCastRouteButton(onSelect = viewModel::selectTarget)
            }
        }
        if (!state.castAvailable) {
            Text("Google Cast is unavailable on this device.")
        } else if (!state.localNetworkPermissionGranted) {
            Text("Grant local-network access to choose a Cast device.", style = MaterialTheme.typography.bodyMedium)
        } else {
            val selectedCast = state.selectedTarget?.takeIf { it.protocol == Protocol.CAST }
            if (selectedCast != null) {
                Text("Selected Cast device: ${selectedCast.displayName}", style = MaterialTheme.typography.bodyMedium)
            } else {
                Text("Use the Cast button to choose a device.", style = MaterialTheme.typography.bodyMedium)
            }
        }

        Text("DLNA / Smart TV (best effort)", style = MaterialTheme.typography.titleMedium)
        TargetGroup(state.dlnaTargets, state.selectedTarget, "No DLNA renderers found. Check Wi-Fi and local-network permission.") {
            viewModel.selectTarget(it)
        }

        Button(
            onClick = { viewModel.play() },
            // Don't block playback to an already-listed target while a (background) rescan is still
            // running — the target list and selection persist across the scan, so a previously
            // discovered TV is connectable immediately.
            enabled = state.selectedTarget != null && state.localNetworkPermissionGranted,
            modifier = Modifier.padding(top = 8.dp),
        ) {
            Text("Play to ${state.selectedTarget?.displayName ?: "selected TV"}")
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

    // This is a passive callback: active scanning is owned by the framework button while its chooser
    // is visible, rather than keeping a power-expensive scan alive for the whole playback lifetime.
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
                button.contentDescription = "Choose Cast device"
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
    onSelect: (DiscoveredTarget) -> Unit,
) {
    if (targets.isEmpty()) {
        Text(empty, style = MaterialTheme.typography.bodyMedium)
        return
    }
    targets.forEach { target ->
        Card(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
            Row(
                Modifier.fillMaxWidth().selectable(selected = target.id == selected?.id) { onSelect(target) }.padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = target.id == selected?.id, onClick = { onSelect(target) })
                Column(Modifier.padding(start = 4.dp)) {
                    Text(target.displayName, style = MaterialTheme.typography.bodyLarge)
                    target.capabilities.modelName?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------
// Playback controls — Material 3 Expressive styling
// ---------------------------------------------------------------------------------------------------

@Composable
fun PlaybackScreen(state: AppUiState, viewModel: MainViewModel) {
    val p = state.playback
    if (p == null) {
        Column(
            Modifier.fillMaxWidth().padding(top = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            CircularProgressIndicator(Modifier.size(52.dp))
            Text("Preparing stream…", style = MaterialTheme.typography.titleMedium)
        }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        // Smoothly-interpolated position so the scrubber tracks the TV between (seconds-apart) status
        // updates. Kept as a State and read via a provider inside the scrubber/transport so the 250 ms
        // tick recomposes ONLY those, not the whole screen (the cards below don't read it).
        val livePosition = rememberSmoothPosition(p)
        val positionProvider = remember(livePosition) { { livePosition.value } }
        NowPlayingHero(p, mediaTitle = state.selectedItem?.title)
        if (p.reconnecting) ReconnectingBanner()
        p.skipSegmentLabel?.let { label ->
            SkipSegmentButton(label = label, onSkip = { viewModel.skipSegment() })
        }
        SeekScrubber(
            p = p,
            positionProvider = positionProvider,
            chapters = state.selectedItem?.chapters.orEmpty(),
            previewUrlFor = { idx ->
                state.selectedItem?.let { viewModel.chapterImageUrl(it, idx, CHAPTER_PREVIEW_WIDTH_PX) }
            },
            onSeek = { viewModel.seekTo(it) },
        )
        TransportControls(p, positionProvider = positionProvider, viewModel = viewModel)
        VolumeControl(p, viewModel)
        TrackControls(p, viewModel)
        AdaptiveCard(
            p,
            onSelectQuality = { viewModel.selectQuality(it) },
            onSelectCodec = { viewModel.selectPreferredCodec(it) },
        )
        p.errorMessage?.let { msg ->
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    msg,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
        FilledTonalButton(
            onClick = { viewModel.stopPlayback() },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(18.dp),
        ) {
            Icon(Icons.Rounded.Stop, contentDescription = null, modifier = Modifier.size(22.dp))
            Text("  Stop casting", style = MaterialTheme.typography.titleMedium)
        }
        Text(
            "Tip: you can also control playback from the notification, lock screen, or your phone's volume keys.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SkipSegmentButton(label: String, onSkip: () -> Unit) {
    // A prominent Netflix-style skip action shown while playback sits inside an intro/outro/recap segment.
    Button(
        onClick = onSkip,
        modifier = Modifier.fillMaxWidth().height(52.dp),
        shape = RoundedCornerShape(18.dp),
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
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            CircularProgressIndicator(
                Modifier.size(22.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
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
private fun NowPlayingHero(p: PlaybackUiState, mediaTitle: String?) {
    ElevatedCard(
        shape = RoundedCornerShape(28.dp),
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(56.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        if (p.protocol.equals("CAST", true)) Icons.Rounded.Cast else Icons.Rounded.Tv,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    mediaTitle?.takeIf { it.isNotBlank() } ?: "Now casting",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 2,
                )
                Text(
                    "${p.targetName} · ${p.protocol}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                if (p.isBuffering) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text(
                            "Buffering…",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
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
 * A custom Material 3 Expressive-style progress bar: the played portion is a travelling sine wave that
 * flattens smoothly when paused, the remaining portion a rounded track, with a thumb at the position.
 */
@Composable
private fun WaveBar(fraction: Float, playing: Boolean, indeterminate: Boolean, modifier: Modifier) {
    val activeColor = MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val transition = rememberInfiniteTransition(label = "wave")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing)),
        label = "phase",
    )
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
private fun TransportControls(p: PlaybackUiState, positionProvider: () -> Long, viewModel: MainViewModel) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilledTonalIconButton(
            onClick = { viewModel.seekTo((positionProvider() - 30).coerceAtLeast(0)) },
            modifier = Modifier.size(64.dp),
            shape = RoundedCornerShape(20.dp),
        ) {
            Icon(Icons.Rounded.Replay30, contentDescription = "Back 30 seconds", modifier = Modifier.size(30.dp))
        }
        FilledIconButton(
            onClick = { viewModel.togglePlayPause() },
            modifier = Modifier.size(96.dp),
            shape = CircleShape,
        ) {
            Icon(
                if (p.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = if (p.isPlaying) "Pause" else "Play",
                modifier = Modifier.size(48.dp),
            )
        }
        FilledTonalIconButton(
            onClick = { viewModel.seekTo(positionProvider() + 30) },
            modifier = Modifier.size(64.dp),
            shape = RoundedCornerShape(20.dp),
        ) {
            Icon(Icons.Rounded.Forward30, contentDescription = "Forward 30 seconds", modifier = Modifier.size(30.dp))
        }
    }
}

@Composable
private fun VolumeControl(p: PlaybackUiState, viewModel: MainViewModel) {
    // Drive the device volume only when the user releases the slider — onValueChange fires ~60x/s during
    // a drag, which would flood a DLNA renderer (one blocking SOAP call each) and delay other commands.
    // Local drag state keeps the thumb + percentage responsive while dragging.
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(p.volume) }
    val shown = (if (dragging) dragValue else p.volume).coerceIn(0f, 1f)
    Card(shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
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
private fun AdaptiveCard(p: PlaybackUiState, onSelectQuality: (Long?) -> Unit, onSelectCodec: (String?) -> Unit) {
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
                    "Quality",
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
                    label = "Quality",
                    selectedLabel = menu.firstOrNull { it.isSelected }?.label ?: "Auto",
                    options = menu.map { it.bitrateBps to it.label },
                    onSelect = onSelectQuality,
                )
            }
            // Manual codec picker — Auto (best codec the TV supports) plus each codec the TV can accept.
            // Choosing one makes a server transcode use that codec.
            if (p.availableVideoCodecs.size > 1) {
                val codecOptions = listOf<Pair<String?, String>>(null to "Auto") +
                    p.availableVideoCodecs.map { it to codecLabel(it) }
                PickerRow(
                    icon = Icons.Rounded.Memory,
                    label = "Codec",
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
                AdaptiveCard(samplePlayback, onSelectQuality = {}, onSelectCodec = {})
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
