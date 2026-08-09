package com.adsamcik.streamferry.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.PrivacyTip
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.ui.text.font.FontWeight
import android.content.ClipData
import android.content.Intent
import android.content.res.Configuration
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.adsamcik.streamferry.BuildConfig
import com.adsamcik.streamferry.diagnostics.ReportExport
import com.adsamcik.streamferry.diagnostics.ReportShare
import com.adsamcik.streamferry.logging.LogEntry
import com.adsamcik.streamferry.logging.LogLevel
import com.adsamcik.streamferry.ui.state.AppUiState
import com.adsamcik.streamferry.ui.theme.StreamFerryTheme
import java.text.SimpleDateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private enum class DiagFilter(val label: String) {
    ALL("All"),
    ISSUES("Issues"),
    CONNECTIONS("Connections"),
    TRACE("Trace"),
}

private val CONNECTION_CATEGORIES = setOf("discovery", "connect", "session", "playback", "network", "jellyfin")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(
    state: AppUiState,
    onRefresh: () -> Unit,
    onClearCrashes: () -> Unit,
    crashReportText: () -> String,
    diagnosticsText: () -> String,
    onToggleTvTracing: (Boolean) -> Unit,
) {
    val d = state.diagnostics
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val clipboardScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    val saveDiagnosticsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        // Build the report + write the file off the main thread (file reads + I/O would jank the UI).
        uri?.let { target ->
            clipboardScope.launch {
                val result = withContext(Dispatchers.IO) {
                    ReportExport.writeUtf8(diagnosticsText()) {
                        context.contentResolver.openOutputStream(target)
                    }
                }
                snackbarHostState.showSnackbar(
                    message = if (result.isSuccess) "Diagnostics report saved."
                    else "Couldn't save the diagnostics report. Choose another location and try again.",
                    withDismissAction = result.isFailure,
                )
            }
        }
    }
    val saveCrashLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        uri?.let { target ->
            clipboardScope.launch {
                val result = withContext(Dispatchers.IO) {
                    ReportExport.writeUtf8(crashReportText()) {
                        context.contentResolver.openOutputStream(target)
                    }
                }
                snackbarHostState.showSnackbar(
                    message = if (result.isSuccess) "Crash logs saved."
                    else "Couldn't save the crash logs. Choose another location and try again.",
                    withDismissAction = result.isFailure,
                )
            }
        }
    }

    var selectedFilter by remember { mutableStateOf(DiagFilter.ALL) }
    var searchQuery by remember { mutableStateOf("") }
    val timeFmt = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }

    val filteredEntries = remember(d.entries, selectedFilter, searchQuery) {
        d.entries.filter { e ->
            val passesFilter = when (selectedFilter) {
                DiagFilter.ALL -> true
                DiagFilter.ISSUES -> e.level == LogLevel.WARN || e.level == LogLevel.ERROR
                DiagFilter.CONNECTIONS -> e.level == LogLevel.EVENT && e.category in CONNECTION_CATEGORIES
                DiagFilter.TRACE -> e.level == LogLevel.TRACE
            }
            val passesSearch = searchQuery.isBlank() ||
                e.category.contains(searchQuery, ignoreCase = true) ||
                e.message.contains(searchQuery, ignoreCase = true)
            passesFilter && passesSearch
        }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 80.dp),
        ) {
        item {
            OutlinedButton(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) { Text("Refresh") }
        }

        // Report card
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Report an issue", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Everything here is redacted — no tokens, URLs, or IDs leave your device.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(
                        onClick = {
                            // Read crash files + build the report off the main thread, then share on main.
                            clipboardScope.launch {
                                val result = runCatching {
                                    val text = withContext(Dispatchers.IO) { diagnosticsText() }
                                    check(text.isNotBlank()) { "The diagnostics report is empty." }
                                    val shareIntent = withContext(Dispatchers.IO) {
                                        ReportShare.createIntent(context, text, "Stream Ferry diagnostics report")
                                    }
                                    context.startActivity(Intent.createChooser(shareIntent, "Share report"))
                                }
                                if (result.isFailure) {
                                    snackbarHostState.showSnackbar(
                                        "Couldn't open the diagnostics share sheet. Try saving the report instead.",
                                        withDismissAction = true,
                                    )
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Share report") }
                    OutlinedButton(
                        onClick = { saveDiagnosticsLauncher.launch("stream-ferry-report.txt") },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Save report…") }
                }
            }
        }

        // App & device card
        item {
            SnapshotCard("App & device") {
                LabelValue("Version", d.appVersion.ifBlank { "—" })
                LabelValue("Build type", d.buildType.ifBlank { "—" })
                LabelValue("Device", d.deviceModel.ifBlank { "—" })
                LabelValue("Android", d.androidVersion.ifBlank { "—" })
                LabelValue("SDK info", d.sdkInfo.ifBlank { "—" })
            }
        }

        // Network card
        item {
            SnapshotCard("Network") {
                LabelValue("VPN active", d.vpnActive.toString())
                LabelValue("Wi-Fi connected", d.wifiConnected.toString())
                LabelValue("Wi-Fi available", d.wifiNetworkAvailable.toString())
                LabelValue("LAN IP", d.lanIpRedacted.ifBlank { "none" })
                LabelValue("Proxy", d.proxyAddressRedacted.ifBlank { "not running" })
                LabelValue("Cast", d.googlePlayServices)
            }
        }

        // Permissions card
        item {
            SnapshotCard("Permissions") {
                LabelValue("Local network", d.localNetworkPermission)
                if (d.localNetworkPermission.startsWith("not enforced")) {
                    Text(
                        "This Android version grants LAN access to apps automatically, so no prompt " +
                            "is shown and TV streaming still works.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                LabelValue("Notifications", if (d.notificationsGranted) "granted" else "denied")
            }
        }

        // Now playing — only while a session is active
        val np = d.nowPlaying
        if (np != null) {
            item {
                SnapshotCard("Now playing") {
                    LabelValue("Target", np.target)
                    LabelValue("Protocol", np.protocol)
                    LabelValue("Mode", np.mode)
                    LabelValue("Bitrate", "${np.bitrateKbps} kbps")
                    LabelValue("Throughput", "${np.throughputKbps} kbps")
                    LabelValue("Position", np.positionLabel)
                    LabelValue("Buffering", np.buffering.toString())
                }
            }
        }

        // Crashes card
        item {
            Card(modifier = Modifier.fillMaxWidth().animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec())) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Crash reports: ${d.crashCount}", style = MaterialTheme.typography.titleSmall)
                    if (d.crashCount > 0) {
                        Button(
                            onClick = {
                                clipboardScope.launch {
                                    val result = runCatching {
                                        val text = withContext(Dispatchers.IO) { crashReportText() }
                                        check(text.isNotBlank()) { "The crash report is empty." }
                                        val shareIntent = withContext(Dispatchers.IO) {
                                            ReportShare.createIntent(context, text, "Stream Ferry crash report")
                                        }
                                        context.startActivity(Intent.createChooser(shareIntent, "Share crash report"))
                                    }
                                    if (result.isFailure) {
                                        snackbarHostState.showSnackbar(
                                            "Couldn't open the crash-log share sheet. Try saving the logs instead.",
                                            withDismissAction = true,
                                        )
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Share crash logs") }
                        OutlinedButton(
                            onClick = { saveCrashLauncher.launch("stream-ferry-crash.txt") },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Save crash logs…") }
                        OutlinedButton(onClick = onClearCrashes, modifier = Modifier.fillMaxWidth()) {
                            Text("Clear crash logs")
                        }
                        d.latestCrash?.let { crash ->
                            Text("Most recent crash:", style = MaterialTheme.typography.bodySmall)
                            Text(
                                crash,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 8,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    } else {
                        Text(
                            "No crashes recorded. If the app crashes, a redacted report is saved here to share or save.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }

        // Event log section
        item {
            Text(
                "Event log (redacted)",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() },
            )
        }

        // Filter chips
        item {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (filter in DiagFilter.entries) {
                    FilterChip(
                        selected = selectedFilter == filter,
                        onClick = { selectedFilter = filter },
                        label = { Text(filter.label) },
                    )
                }
            }
        }

        // Search box
        item {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                label = { Text("Search") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(
                    onDone = {
                        focusManager.clearFocus()
                        keyboard?.hide()
                    },
                ),
            )
        }

        // Tracing toggle
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .toggleable(
                            value = d.tvTracingEnabled,
                            role = Role.Switch,
                            onValueChange = onToggleTvTracing,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Switch(checked = d.tvTracingEnabled, onCheckedChange = null)
                    Text("Detailed TV communication tracing")
                }
                Text(
                    "Records redacted Cast/DLNA traffic into this log to help trace playback problems. Off by " +
                        "default; it stays on your device unless you export it.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        // Empty state or log rows
        if (filteredEntries.isEmpty()) {
            item {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (d.entries.isEmpty()) "No log entries yet." else "No entries match the current filter.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            items(filteredEntries) { entry ->
                LogEntryRow(
                    modifier = Modifier.animateItem(),
                    entry = entry,
                    fmt = timeFmt,
                    onCopy = {
                        val ts = timeFmt.format(Date(entry.timeMillis))
                        val line = "$ts [${entry.level}] ${entry.category}: ${entry.message}"
                        clipboardScope.launch {
                            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Stream Ferry log", line)))
                        }
                    },
                )
            }
            }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
        )
    }
}

@Composable
private fun SnapshotCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth().animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec()),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            content()
        }
    }
}

@Composable
private fun LabelValue(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(128.dp),
        )
        Text(text = value, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun LogEntryRow(
    entry: LogEntry,
    fmt: SimpleDateFormat,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val timeStr = remember(entry.timeMillis) { fmt.format(Date(entry.timeMillis)) }
    val levelColor = when (entry.level) {
        LogLevel.ERROR -> MaterialTheme.colorScheme.error
        LogLevel.WARN -> MaterialTheme.colorScheme.tertiary
        LogLevel.EVENT -> MaterialTheme.colorScheme.primary
        LogLevel.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
        LogLevel.DEBUG, LogLevel.TRACE -> MaterialTheme.colorScheme.outline
    }
    val levelLabel = when (entry.level) {
        LogLevel.ERROR -> "ERR"
        LogLevel.WARN -> "WRN"
        LogLevel.EVENT -> "EVT"
        LogLevel.INFO -> "INF"
        LogLevel.DEBUG -> "DBG"
        LogLevel.TRACE -> "TRC"
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onCopy)
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(timeStr, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        Text(levelLabel, style = MaterialTheme.typography.labelSmall, color = levelColor, modifier = Modifier.width(28.dp))
        Text(entry.category, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(88.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(entry.message, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
    }
}


@Composable
fun AboutScreen() {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ElevatedCard(
            shape = MaterialTheme.shapes.extraLarge,
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            ),
        ) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Stream Ferry",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    "Version " + BuildConfig.VERSION_NAME,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    "A private bridge from your media library to the screen you already own.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }

        AboutFactCard(
            icon = Icons.Rounded.PrivacyTip,
            title = "Private by design",
            body = "No telemetry, analytics, ads, or tracking. Credentials and playback history stay on this phone.",
        )
        AboutFactCard(
            icon = Icons.Rounded.Devices,
            title = "Built for your devices",
            body = "Streams locally to compatible Cast and DLNA receivers, with explicit fallbacks when a format needs help.",
        )
        AboutFactCard(
            icon = Icons.Rounded.Code,
            title = "Open source",
            body = "Open-source notices are bundled with the app and documented in docs/LICENSES.md.",
        )
    }
}

@Composable
private fun AboutFactCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    body: String,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ) {
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, modifier = Modifier.size(26.dp))
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------
// Design-time @Preview composables (no ViewModel required); R8 strips them from the release build.
// ---------------------------------------------------------------------------------------------------

@Preview(name = "About", showBackground = true)
@Preview(name = "About · dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun AboutScreenPreview() {
    StreamFerryTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            Box(Modifier.padding(16.dp)) { AboutScreen() }
        }
    }
}


@Preview(name = "Log entries", showBackground = true)
@Preview(name = "Log entries · dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun LogEntryRowPreview() {
    val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }
    StreamFerryTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                LogEntryRow(
                    entry = LogEntry(0L, LogLevel.EVENT, "library", "Loaded 26 items under a folder"),
                    fmt = fmt,
                    onCopy = {},
                )
                LogEntryRow(
                    entry = LogEntry(0L, LogLevel.WARN, "Playback", "Couldn't start playback (Cast device unavailable)"),
                    fmt = fmt,
                    onCopy = {},
                )
            }
        }
    }
}
