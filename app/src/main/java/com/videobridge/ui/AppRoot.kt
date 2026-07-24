package com.videobridge.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.videobridge.diagnostics.ReportShare
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.videobridge.ui.screens.AboutScreen
import com.videobridge.ui.screens.DiagnosticsScreen
import com.videobridge.ui.screens.DownloadsScreen
import com.videobridge.ui.screens.GalleryScreen
import com.videobridge.ui.screens.LoginScreen
import com.videobridge.ui.screens.MediaDetailScreen
import com.videobridge.ui.screens.PlaybackScreen
import com.videobridge.ui.screens.ServerSetupScreen
import com.videobridge.ui.screens.SettingsScreen
import com.videobridge.ui.screens.ServersScreen
import com.videobridge.ui.screens.TargetPickerScreen
import com.videobridge.ui.screens.WelcomeScreen
import com.videobridge.ui.state.AppUiState
import com.videobridge.ui.state.Route

/**
 * App shell. A lightweight route switch keeps navigation simple while still separating each screen.
 * All screens are accessible, use Material 3, and surface loading/empty/error/permission states (§18).
 *
 * @param onScanDevices triggers the runtime local-network permission request and then a device scan;
 *   wired by the activity (the permission launcher).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(state: AppUiState, viewModel: MainViewModel, onScanDevices: () -> Unit) {
    val back = backActionFor(state, viewModel)
    val context = LocalContext.current
    // Route the hardware back button through the same in-app navigation as the top-bar arrow, so no
    // screen is a dead-end and back never unexpectedly exits the app mid-flow.
    BackHandler(enabled = back != null) { back?.invoke() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        topBar = {
            TopAppBar(
                title = { Text(titleFor(state.route), fontWeight = FontWeight.Bold) },
                navigationIcon = { if (back != null) IconBack(back) },
                actions = {
                    if (state.route == Route.GALLERY) {
                        IconButton(onClick = { viewModel.openDownloads() }) {
                            Icon(Icons.Rounded.Download, contentDescription = "Downloads")
                        }
                        IconButton(onClick = { viewModel.navigate(Route.SETTINGS) }) {
                            Icon(Icons.Rounded.Settings, contentDescription = "Settings")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
    ) { padding ->
        // Wrap content in a vertical scroller except for screens that manage their own scrolling
        // (a grid / lazy list), so long column screens are never clipped on small displays.
        val selfScrolling = state.route in SELF_SCROLLING_ROUTES
        val contentModifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .let { if (selfScrolling) it else it.verticalScroll(rememberScrollState()) }
        Column(contentModifier) {
            state.errorMessage?.let { ErrorBanner(it) }
            when (state.route) {
                Route.WELCOME -> WelcomeScreen(state.loggedIn, onContinue = { viewModel.onWelcomeContinue() }, onLocalOnly = { viewModel.useLocalOnly() })
                Route.SERVER_SETUP -> ServerSetupScreen(state, viewModel)
                Route.LOGIN -> LoginScreen(state, viewModel)
                Route.GALLERY -> GalleryScreen(state, viewModel)
                Route.MEDIA_DETAIL -> MediaDetailScreen(state, viewModel, onChooseTv = onScanDevices)
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
                Route.SETTINGS -> SettingsScreen(
                    onLogout = { viewModel.logout() },
                    onDeleteAll = { viewModel.deleteAllData() },
                    onAbout = { viewModel.navigate(Route.ABOUT) },
                    onDiagnostics = { viewModel.navigate(Route.DIAGNOSTICS); viewModel.refreshDiagnostics() },
                    onDownloads = { viewModel.openDownloads() },
                    onServers = { viewModel.openServers() },
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
                    backgroundPlaybackUnrestricted = viewModel.isBackgroundPlaybackUnrestricted(),
                    onAllowBackgroundPlayback = {
                        runCatching { context.startActivity(viewModel.batteryOptimizationRequestIntent()) }
                    },
                    onResetTvCapabilities = { viewModel.resetLearnedTvCapabilities() },
                )
                Route.ABOUT -> AboutScreen()
                Route.SERVERS -> ServersScreen(state, viewModel)
            }
        }
    }

    // Startup prompt: if a previous run crashed, offer to export the redacted report — available from
    // any screen, including before connecting/logging in.
    state.crashAlertCount?.let { count ->
        CrashAlertDialog(
            count = count,
            reportText = { viewModel.crashReportsForExport() },
            onDismiss = { viewModel.dismissCrashAlert() },
        )
    }
}

@Composable
private fun CrashAlertDialog(count: Int, reportText: () -> String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        // Read the crash files + write the export off the main thread to keep the UI responsive.
        uri?.let { target ->
            scope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(target)?.use { os -> os.write(reportText().toByteArray()) }
                    }
                }
                onDismiss()
            }
        } ?: onDismiss()
    }
    val plural = if (count == 1) "" else "s"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Crash detected") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("The app recorded $count crash report$plural from a previous run. Export to share with the developer? (Secrets are stripped.)")
                Button(
                    onClick = {
                        scope.launch {
                            val text = withContext(Dispatchers.IO) { reportText() }
                            if (text.isNotBlank()) {
                                val shareIntent = withContext(Dispatchers.IO) {
                                    ReportShare.createIntent(context, text, "Video Bridge crash report")
                                }
                                context.startActivity(Intent.createChooser(shareIntent, "Share crash report"))
                            }
                            onDismiss()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Share crash report") }
                OutlinedButton(
                    onClick = { saveLauncher.launch("video-bridge-crash.txt") },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Save to a file…") }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Not now") } },
    )
}

/**
 * The single source of truth for "back" on each route, or null when back should fall through to the
 * system (exit). Used by both the top-bar arrow and the hardware back button.
 */
private fun backActionFor(state: AppUiState, viewModel: MainViewModel): (() -> Unit)? = when (state.route) {
    Route.WELCOME -> null
    Route.SERVER_SETUP -> ({ viewModel.navigate(Route.WELCOME) })
    Route.LOGIN -> ({ viewModel.navigate(Route.SERVER_SETUP) })
    Route.GALLERY -> if (state.folderStack.isNotEmpty()) ({ viewModel.popFolder() }) else null
    Route.MEDIA_DETAIL -> ({ viewModel.navigate(Route.GALLERY) })
    Route.TARGET_PICKER -> ({ viewModel.navigate(Route.MEDIA_DETAIL) })
    Route.PLAYBACK -> ({ viewModel.stopPlayback() })
    Route.DOWNLOADS -> ({ viewModel.navigate(Route.GALLERY) })
    Route.DIAGNOSTICS -> ({ viewModel.navigate(Route.SETTINGS) })
    Route.SETTINGS -> ({ viewModel.navigate(Route.GALLERY) })
    Route.ABOUT -> ({ viewModel.navigate(Route.SETTINGS) })
    Route.SERVERS -> ({ viewModel.navigate(Route.SETTINGS) })
}

@Composable
private fun IconBack(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
    }
}

private fun titleFor(route: Route): String = when (route) {
    Route.WELCOME -> "Video Bridge"
    Route.SERVER_SETUP -> "Connect a server"
    Route.LOGIN -> "Log in"
    Route.GALLERY -> "Library"
    Route.MEDIA_DETAIL -> "Details"
    Route.TARGET_PICKER -> "Choose a TV"
    Route.PLAYBACK -> "Playing"
    Route.DOWNLOADS -> "Downloads"
    Route.DIAGNOSTICS -> "Diagnostics"
    Route.SETTINGS -> "Settings"
    Route.ABOUT -> "About & licenses"
    Route.SERVERS -> "Servers"
}

@Composable
private fun ErrorBanner(message: String) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp).semantics { contentDescription = "Error: $message" },
    ) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Text(message, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private val SELF_SCROLLING_ROUTES = setOf(Route.GALLERY, Route.DOWNLOADS, Route.DIAGNOSTICS)
