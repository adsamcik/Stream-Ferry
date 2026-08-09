package com.adsamcik.streamferry.ui

import android.content.Intent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateFloatAsState
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.adsamcik.streamferry.app.StreamFerryApplication
import com.adsamcik.streamferry.core.volume.NightVolumePolicy
import com.adsamcik.streamferry.diagnostics.ReportExport
import com.adsamcik.streamferry.diagnostics.ReportShare
import com.adsamcik.streamferry.domain.JellyfinLibraryStatus
import com.adsamcik.streamferry.domain.MediaSourceIds
import com.adsamcik.streamferry.playback.PlaybackPhase
import com.adsamcik.streamferry.playback.PlaybackControlPolicy
import com.adsamcik.streamferry.ui.navigation.NavigationStatePolicy
import com.adsamcik.streamferry.ui.navigation.NavigationStatePolicy.TopLevelDestination
import com.adsamcik.streamferry.ui.screens.AboutScreen
import com.adsamcik.streamferry.ui.screens.DiagnosticsScreen
import com.adsamcik.streamferry.ui.screens.DownloadsScreen
import com.adsamcik.streamferry.ui.screens.ExpressiveSettingsScreen
import com.adsamcik.streamferry.ui.screens.GalleryScreen
import com.adsamcik.streamferry.ui.screens.JellyfinUnavailableHeaderNotice
import com.adsamcik.streamferry.ui.screens.LoginScreen
import com.adsamcik.streamferry.ui.screens.MediaDetailScreen
import com.adsamcik.streamferry.ui.screens.PlaybackScreen
import com.adsamcik.streamferry.ui.screens.ServerSetupScreen
import com.adsamcik.streamferry.ui.screens.ServersScreen
import com.adsamcik.streamferry.ui.screens.TargetPickerScreen
import com.adsamcik.streamferry.ui.screens.WelcomeScreen
import com.adsamcik.streamferry.ui.state.AppUiState
import com.adsamcik.streamferry.ui.state.Route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val compactNavigationBreakpoint = 600.dp
private val compactHeightBreakpoint = 480.dp
private val selfScrollingRoutes = setOf(
    Route.GALLERY,
    Route.MEDIA_DETAIL,
    Route.TARGET_PICKER,
    Route.PLAYBACK,
    Route.DOWNLOADS,
    Route.DIAGNOSTICS,
    Route.SETTINGS,
)
private val upNavigationRoutes = setOf(
    Route.SERVER_SETUP,
    Route.LOGIN,
    Route.MEDIA_DETAIL,
    Route.TARGET_PICKER,
    Route.PLAYBACK,
    Route.DOWNLOADS,
    Route.DIAGNOSTICS,
    Route.ABOUT,
    Route.SERVERS,
)

/**
 * Adaptive application shell. Compact windows use a navigation bar; medium and expanded windows use a
 * navigation rail. Playback remains reachable through a persistent mini-player while the user browses.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(state: AppUiState, viewModel: MainViewModel, onScanDevices: () -> Unit) {
    val back = backActionFor(state, viewModel)
    val context = LocalContext.current
    val nightVolumeSettings = remember(context.applicationContext) {
        (context.applicationContext as StreamFerryApplication).container.nightVolumeSettingsStore
    }
    var nightVolumePolicy by remember { mutableStateOf(nightVolumeSettings.load()) }
    val showUpNavigation = state.route in upNavigationRoutes

    BackHandler(enabled = back != null) { back?.invoke() }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val useNavigationRail = maxWidth >= compactNavigationBreakpoint || maxHeight < compactHeightBreakpoint
        val showTopLevelNavigation = state.route !in setOf(
            Route.WELCOME,
            Route.SERVER_SETUP,
            Route.LOGIN,
            Route.PLAYBACK,
        )
        val showMiniPlayer = state.playback != null && state.route != Route.PLAYBACK
        val galleryOwnsPhoneHeader = !useNavigationRail && state.route == Route.GALLERY
        val showJellyfinUnavailableNotice = state.activeSourceId == MediaSourceIds.JELLYFIN &&
            state.jellyfinLibraryStatus == JellyfinLibraryStatus.UNAVAILABLE
        val spatialMotion = MaterialTheme.motionScheme.defaultSpatialSpec<IntOffset>()
        val effectsMotion = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()

        Scaffold(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
            contentWindowInsets = WindowInsets.safeDrawing,
            topBar = {
                if (!galleryOwnsPhoneHeader) {
                    Column {
                        TopAppBar(
                            title = { Text(titleFor(state.route), fontWeight = FontWeight.Bold, maxLines = 1) },
                            navigationIcon = { if (showUpNavigation && back != null) IconBack(back) },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                                titleContentColor = MaterialTheme.colorScheme.onSurface,
                            ),
                        )
                        if (showJellyfinUnavailableNotice) {
                            JellyfinUnavailableHeaderNotice(
                                onRetry = viewModel::refreshGallery,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            )
                        }
                    }
                }
            },
            bottomBar = {
                if (!useNavigationRail && showTopLevelNavigation) {
                    Column {
                        if (showMiniPlayer) {
                            MiniPlayer(
                                state = state,
                                viewModel = viewModel,
                                compact = true,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            )
                        }
                        CompactNavigation(state, viewModel)
                    }
                }
            },
        ) { scaffoldPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(scaffoldPadding),
            ) {
                if (useNavigationRail && showTopLevelNavigation) {
                    WideNavigation(state, viewModel)
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                ) {
                    AnimatedContent(
                        targetState = state.route,
                        modifier = Modifier.fillMaxSize(),
                        transitionSpec = {
                            val forward = routeDepth(targetState) >= routeDepth(initialState)
                            val enterOffset: (Int) -> Int = { width ->
                                if (forward) width / 10 else -width / 10
                            }
                            val exitOffset: (Int) -> Int = { width ->
                                if (forward) -width / 14 else width / 14
                            }
                            (fadeIn(effectsMotion) + slideInHorizontally(spatialMotion, enterOffset))
                                .togetherWith(
                                    fadeOut(effectsMotion) + slideOutHorizontally(spatialMotion, exitOffset),
                                )
                        },
                        label = "route transition",
                    ) { route ->
                        ScreenContent(
                            state = state.copy(route = route),
                            viewModel = viewModel,
                            onScanDevices = onScanDevices,
                            context = context,
                            nightVolumePolicy = nightVolumePolicy,
                            onNightVolumePolicyChange = { policy ->
                                nightVolumePolicy = policy
                                nightVolumeSettings.save(policy)
                            },
                            expanded = useNavigationRail,
                            reserveMiniPlayerSpace = useNavigationRail && showMiniPlayer,
                        )
                    }
                    if (useNavigationRail && showMiniPlayer) {
                        MiniPlayer(
                            state = state,
                            viewModel = viewModel,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(horizontal = 24.dp, vertical = 16.dp)
                                .widthIn(max = 720.dp),
                        )
                    }
                }
            }
        }

        state.crashAlertCount?.let { count ->
            CrashAlertDialog(
                count = count,
                reportText = { viewModel.crashReportsForExport() },
                onDismiss = { viewModel.dismissCrashAlert() },
            )
        }
    }
}

@Composable
private fun ScreenContent(
    state: AppUiState,
    viewModel: MainViewModel,
    onScanDevices: () -> Unit,
    context: android.content.Context,
    nightVolumePolicy: NightVolumePolicy,
    onNightVolumePolicyChange: (NightVolumePolicy) -> Unit,
    expanded: Boolean,
    reserveMiniPlayerSpace: Boolean,
) {
    val maxContentWidth = when (state.route) {
        Route.GALLERY -> 1_440.dp
        Route.PLAYBACK -> 1_120.dp
        else -> 960.dp
    }
    val outerPadding = when {
        expanded -> 24.dp
        state.route == Route.GALLERY -> 12.dp
        else -> 16.dp
    }
    val topPadding = if (!expanded && state.route == Route.GALLERY) 8.dp else 12.dp
    val bottomPadding = if (reserveMiniPlayerSpace) 104.dp else 12.dp
    val shellErrorMessage = state.errorMessage?.takeUnless { message ->
        state.route == Route.PLAYBACK && message == state.playback?.errorMessage
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        val baseModifier = Modifier
            .fillMaxHeight()
            .widthIn(max = maxContentWidth)
            .padding(start = outerPadding, end = outerPadding, top = topPadding, bottom = bottomPadding)
            .imePadding()

        if (state.route in selfScrollingRoutes) {
            Column(modifier = baseModifier.fillMaxWidth()) {
                ErrorBannerHost(
                    message = shellErrorMessage,
                    onDismiss = viewModel::dismissError,
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) {
                    RouteContent(state, viewModel, onScanDevices, context, nightVolumePolicy, onNightVolumePolicyChange, compact = !expanded)
                }
            }
        } else {
            Column(
                modifier = baseModifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                ErrorBannerHost(
                    message = shellErrorMessage,
                    onDismiss = viewModel::dismissError,
                )
                RouteContent(state, viewModel, onScanDevices, context, nightVolumePolicy, onNightVolumePolicyChange, compact = !expanded)
            }
        }
    }
}

@Composable
private fun RouteContent(
    state: AppUiState,
    viewModel: MainViewModel,
    onScanDevices: () -> Unit,
    context: android.content.Context,
    nightVolumePolicy: NightVolumePolicy,
    onNightVolumePolicyChange: (NightVolumePolicy) -> Unit,
    compact: Boolean,
) {
    when (state.route) {
        Route.WELCOME -> WelcomeScreen(
            state.loggedIn,
            onContinue = { viewModel.onWelcomeContinue() },
            onLocalOnly = { viewModel.useLocalOnly() },
        )
        Route.SERVER_SETUP -> ServerSetupScreen(state, viewModel)
        Route.LOGIN -> LoginScreen(state, viewModel)
        Route.GALLERY -> GalleryScreen(state, viewModel, compact = compact)
        Route.MEDIA_DETAIL -> MediaDetailScreen(state, viewModel, onChooseTv = onScanDevices, compact = compact)
        Route.TARGET_PICKER -> TargetPickerScreen(state, viewModel, onRescan = onScanDevices)
        Route.PLAYBACK -> PlaybackScreen(state, viewModel)
        Route.DOWNLOADS -> DownloadsScreen(state, viewModel, onCast = onScanDevices)
        Route.DIAGNOSTICS -> DiagnosticsScreen(
            state = state,
            onRefresh = { viewModel.refreshDiagnostics() },
            onClearCrashes = { viewModel.clearCrashLogs() },
            crashReportText = { viewModel.crashReportsForExport() },
            diagnosticsText = { viewModel.diagnosticsForExport() },
            onToggleTvTracing = { viewModel.setTvTracingEnabled(it) },
        )
        Route.SETTINGS -> ExpressiveSettingsScreen(
            onLogout = { viewModel.logout() },
            onDeleteAll = { viewModel.deleteAllData() },
            onAbout = { viewModel.navigate(Route.ABOUT) },
            onDiagnostics = { viewModel.navigate(Route.DIAGNOSTICS); viewModel.refreshDiagnostics() },
            onDownloads = { viewModel.openDownloads() },
            onServers = { viewModel.openServers() },
            themeMode = state.themeMode,
            onThemeModeChange = { viewModel.setThemeMode(it) },
            preferDirectPlay = viewModel.preferDirectPlay,
            onPreferDirectPlayChange = { viewModel.setPreferDirectPlay(it) },
            transcodeLocalOnDevice = viewModel.transcodeLocalOnDevice,
            onTranscodeLocalChange = { viewModel.setTranscodeLocalOnDevice(it) },
            maxVideoHeight = viewModel.maxVideoHeight,
            onMaxVideoHeightChange = { viewModel.setMaxVideoHeight(it) },
            autoPlayNextEpisode = viewModel.autoPlayNextEpisode,
            onAutoPlayNextChange = { viewModel.setAutoPlayNextEpisode(it) },
            autoSkipSegments = viewModel.autoSkipSegments,
            onAutoSkipSegmentsChange = { viewModel.setAutoSkipSegments(it) },
            preferredAudioLanguage = viewModel.preferredAudioLanguage,
            onPreferredAudioLanguageChange = { viewModel.setPreferredAudioLanguage(it) },
            preferredSubtitleLanguage = viewModel.preferredSubtitleLanguage,
            onPreferredSubtitleLanguageChange = { viewModel.setPreferredSubtitleLanguage(it) },
            backgroundPlaybackUnrestricted = state.backgroundPlaybackUnrestricted,
            onAllowBackgroundPlayback = {
                runCatching { context.startActivity(viewModel.batteryOptimizationRequestIntent()) }
            },
            onResetTvCapabilities = { viewModel.resetLearnedTvCapabilities() },
            nightVolumePolicy = nightVolumePolicy,
            onNightVolumePolicyChange = onNightVolumePolicyChange,
        )
        Route.ABOUT -> AboutScreen()
        Route.SERVERS -> ServersScreen(state, viewModel)
    }
}

@Composable
private fun CompactNavigation(state: AppUiState, viewModel: MainViewModel) {
    val selected = NavigationStatePolicy.topLevelFor(state.route)
    NavigationBar {
        NavigationBarItem(
            selected = selected == TopLevelDestination.LIBRARY,
            onClick = { viewModel.navigate(Route.GALLERY) },
            icon = { Icon(Icons.Rounded.VideoLibrary, contentDescription = null) },
            label = { Text("Library") },
        )
        NavigationBarItem(
            selected = selected == TopLevelDestination.SETTINGS,
            onClick = { viewModel.navigate(Route.SETTINGS) },
            icon = { Icon(Icons.Rounded.Settings, contentDescription = null) },
            label = { Text("Settings") },
        )
    }
}

@Composable
private fun WideNavigation(state: AppUiState, viewModel: MainViewModel) {
    val selected = NavigationStatePolicy.topLevelFor(state.route)
    NavigationRail {
        NavigationRailItem(
            selected = selected == TopLevelDestination.LIBRARY,
            onClick = { viewModel.navigate(Route.GALLERY) },
            icon = { Icon(Icons.Rounded.VideoLibrary, contentDescription = null) },
            label = { Text("Library") },
            alwaysShowLabel = true,
        )
        NavigationRailItem(
            selected = selected == TopLevelDestination.SETTINGS,
            onClick = { viewModel.navigate(Route.SETTINGS) },
            icon = { Icon(Icons.Rounded.Settings, contentDescription = null) },
            label = { Text("Settings") },
            alwaysShowLabel = true,
        )
    }
}

@Composable
private fun MiniPlayer(
    state: AppUiState,
    viewModel: MainViewModel,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val playback = state.playback ?: return
    val title = state.nowPlayingItem?.title?.takeIf(String::isNotBlank)
        ?: playback.mediaTitle.takeIf(String::isNotBlank)
        ?: "Now playing"
    val queueSize = state.playlist.entries.size
    val duration = playback.durationSeconds?.takeIf { it > 0 }
    val controls = PlaybackControlPolicy.evaluate(playback.phase, duration)
    val progress = duration?.let { (playback.positionSeconds.toFloat() / it).coerceIn(0f, 1f) }
    val animatedProgress by animateFloatAsState(
        targetValue = progress ?: 0f,
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "mini player progress",
    )
    val effectsMotion = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val playbackState = when {
        playback.isTerminal -> "Playback needs attention"
        playback.phase == PlaybackPhase.COMPLETED -> if (queueSize > 0) {
            "Finished · $queueSize up next"
        } else {
            "Finished on ${playback.targetName}"
        }
        playback.reconnecting -> "Reconnecting to ${playback.targetName}"
        playback.isBuffering -> "Buffering on ${playback.targetName}"
        playback.isPlaying -> if (queueSize > 0) {
            "Playing on ${playback.targetName} · $queueSize up next"
        } else {
            "Playing on ${playback.targetName}"
        }
        else -> "Paused on ${playback.targetName}"
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = if (compact) 64.dp else 76.dp)
            .semantics {
                stateDescription = playbackState
                progress?.let { progressBarRangeInfo = ProgressBarRangeInfo(it, 0f..1f) }
            }
            .clickable(
                role = Role.Button,
                onClickLabel = "Open Now Playing",
                onClick = { viewModel.navigate(Route.PLAYBACK) },
            ),
        color = if (playback.isTerminal) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = if (playback.isTerminal) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onTertiaryContainer,
        shape = MaterialTheme.shapes.extraLarge,
        shadowElevation = 6.dp,
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = if (compact) 12.dp else 16.dp, vertical = if (compact) 6.dp else 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(Icons.Rounded.Tv, contentDescription = null)
                Column(Modifier.weight(1f)) {
                    Text(
                        title,
                        style = if (compact) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    AnimatedContent(
                        targetState = playbackState,
                        transitionSpec = { fadeIn(effectsMotion).togetherWith(fadeOut(effectsMotion)) },
                        label = "mini player state",
                    ) { status ->
                        Text(status, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (!playback.isTerminal && state.playlist.next != null) {
                    FilledIconButton(
                        onClick = viewModel::skipToNextPlaylistItem,
                        enabled = controls.canPlayPause,
                        modifier = Modifier.size(if (compact) 40.dp else 48.dp),
                    ) {
                        Icon(
                            Icons.Rounded.SkipNext,
                            contentDescription = "Play next: ${state.playlist.next?.item?.title}",
                        )
                    }
                }
                if (playback.isTerminal) {
                    FilledIconButton(
                        onClick = { viewModel.navigate(Route.PLAYBACK) },
                        modifier = Modifier.size(if (compact) 40.dp else 48.dp),
                    ) {
                        Icon(Icons.Rounded.ErrorOutline, contentDescription = "Review playback problem")
                    }
                } else {
                    FilledIconButton(
                        onClick = { viewModel.togglePlayPause() },
                        enabled = controls.canPlayPause,
                        modifier = Modifier.size(if (compact) 40.dp else 48.dp),
                    ) {
                        AnimatedContent(
                            targetState = playback.isPlaying,
                            transitionSpec = { fadeIn(effectsMotion).togetherWith(fadeOut(effectsMotion)) },
                            label = "mini player play state",
                        ) { isPlaying ->
                            Icon(
                                if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                contentDescription = if (isPlaying) "Pause" else "Play",
                            )
                        }
                    }
                }
            }
            if (progress != null && !playback.isTerminal) {
                LinearProgressIndicator(progress = { animatedProgress }, modifier = Modifier.fillMaxWidth().clearAndSetSemantics {})
            }
        }
    }
}

@Composable
private fun ErrorBannerHost(
    message: String?,
    onDismiss: () -> Unit,
) {
    val effectsMotion = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    AnimatedContent(
        targetState = message,
        transitionSpec = { fadeIn(effectsMotion).togetherWith(fadeOut(effectsMotion)) },
        label = "error banner",
    ) { currentMessage ->
        if (currentMessage != null) {
            ErrorBanner(
                message = currentMessage,
                onDismiss = onDismiss,
                modifier = Modifier.padding(bottom = 12.dp),
            )
        }
    }
}

@Composable
private fun ErrorBanner(
    message: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Assertive },
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Rounded.ErrorOutline, contentDescription = null)
            Column(Modifier.weight(1f)) {
                Text(message, style = MaterialTheme.typography.bodyMedium)
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.End),
                ) { Text("Dismiss") }
            }
        }
    }
}

@Composable
private fun CrashAlertDialog(count: Int, reportText: () -> String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var exportError by remember { mutableStateOf<String?>(null) }
    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        uri?.let { target ->
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    ReportExport.writeUtf8(reportText()) {
                        context.contentResolver.openOutputStream(target)
                    }
                }
                if (result.isSuccess) onDismiss()
                else exportError = "Couldn't save the crash report. Choose another location and try again."
            }
        }
    }
    val plural = if (count == 1) "" else "s"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Crash detected") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Stream Ferry recorded $count crash report$plural from a previous run. Secrets are stripped before export.")
                Button(
                    onClick = {
                        scope.launch {
                            val result = runCatching {
                                val text = withContext(Dispatchers.IO) { reportText() }
                                check(text.isNotBlank()) { "The crash report is empty." }
                                val shareIntent = withContext(Dispatchers.IO) {
                                    ReportShare.createIntent(context, text, "Stream Ferry crash report")
                                }
                                context.startActivity(Intent.createChooser(shareIntent, "Share crash report"))
                            }
                            if (result.isSuccess) onDismiss()
                            else exportError = "Couldn't open the crash-report share sheet. Try saving it instead."
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Share crash report") }
                OutlinedButton(
                    onClick = { saveLauncher.launch("stream-ferry-crash.txt") },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Save to a file…") }
                exportError?.let { message ->
                    Text(
                        message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Not now") } },
    )
}

private fun backActionFor(state: AppUiState, viewModel: MainViewModel): (() -> Unit)? = when (state.route) {
    Route.WELCOME -> null
    Route.SERVER_SETUP -> ({ viewModel.navigate(Route.WELCOME) })
    Route.LOGIN -> ({ viewModel.navigate(Route.SERVER_SETUP) })
    Route.GALLERY -> if (state.folderStack.isNotEmpty()) ({ viewModel.popFolder() }) else null
    Route.MEDIA_DETAIL -> ({ viewModel.navigate(Route.GALLERY) })
    Route.TARGET_PICKER -> ({
        val target = when {
            state.selectedDownloadId != null -> Route.DOWNLOADS
            state.selectedItem != null -> Route.MEDIA_DETAIL
            else -> Route.GALLERY
        }
        viewModel.navigate(target)
    })
    // Back returns to browsing while the session continues. Stop remains an explicit playback action.
    Route.PLAYBACK -> ({ viewModel.navigate(Route.GALLERY) })
    Route.DOWNLOADS -> ({
        val safeOrigin = NavigationStatePolicy.sanitizeDownloadsOrigin(
            state.downloadsBackRoute,
            NavigationStatePolicy.Availability(
                hasActivePlayback = state.playback != null,
                hasSelectedItem = state.selectedItem != null,
            ),
        )
        viewModel.navigate(safeOrigin)
    })
    Route.DIAGNOSTICS -> ({ viewModel.navigate(Route.SETTINGS) })
    Route.SETTINGS -> ({ viewModel.navigate(Route.GALLERY) })
    Route.ABOUT -> ({ viewModel.navigate(Route.SETTINGS) })
    Route.SERVERS -> ({ viewModel.navigate(Route.SETTINGS) })
}

@Composable
private fun IconBack(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
    }
}

private fun routeDepth(route: Route): Int = when (route) {
    Route.WELCOME, Route.GALLERY, Route.SETTINGS -> 0
    Route.SERVER_SETUP, Route.MEDIA_DETAIL, Route.DOWNLOADS, Route.DIAGNOSTICS, Route.SERVERS, Route.ABOUT -> 1
    Route.LOGIN, Route.TARGET_PICKER -> 2
    Route.PLAYBACK -> 3
}

private fun titleFor(route: Route): String = when (route) {
    Route.WELCOME -> "Stream Ferry"
    Route.SERVER_SETUP -> "Connect Jellyfin"
    Route.LOGIN -> "Sign in"
    Route.GALLERY -> "Library"
    Route.MEDIA_DETAIL -> "Details"
    Route.TARGET_PICKER -> "Choose a TV"
    Route.PLAYBACK -> "Now Playing"
    Route.DOWNLOADS -> "Downloads"
    Route.DIAGNOSTICS -> "Troubleshooting"
    Route.SETTINGS -> "Settings"
    Route.ABOUT -> "About and licences"
    Route.SERVERS -> "Jellyfin servers"
}
