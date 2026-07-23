package com.videobridge.ui.screens

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.BatteryStd
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.vector.ImageVector
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.videobridge.diagnostics.ReportShare
import com.videobridge.core.language.Languages
import com.videobridge.logging.LogEntry
import com.videobridge.logging.LogLevel
import com.videobridge.ui.state.AppUiState
import com.videobridge.ui.theme.JellyfinBridgeTheme
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

    val saveDiagnosticsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        // Build the report + write the file off the main thread (file reads + I/O would jank the UI).
        uri?.let { target ->
            clipboardScope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(target)?.use { os -> os.write(diagnosticsText().toByteArray()) }
                    }
                }
            }
        }
    }
    val saveCrashLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        uri?.let { target ->
            clipboardScope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(target)?.use { os -> os.write(crashReportText().toByteArray()) }
                    }
                }
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

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 16.dp),
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
                                val text = withContext(Dispatchers.IO) { diagnosticsText() }
                                if (text.isNotBlank()) {
                                    val shareIntent = withContext(Dispatchers.IO) {
                                        ReportShare.createIntent(context, text, "Video Bridge diagnostics report")
                                    }
                                    context.startActivity(Intent.createChooser(shareIntent, "Share report"))
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Share report") }
                    OutlinedButton(
                        onClick = { saveDiagnosticsLauncher.launch("video-bridge-report.txt") },
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
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Crash reports: ${d.crashCount}", style = MaterialTheme.typography.titleSmall)
                    if (d.crashCount > 0) {
                        Button(
                            onClick = {
                                clipboardScope.launch {
                                    val text = withContext(Dispatchers.IO) { crashReportText() }
                                    if (text.isNotBlank()) {
                                        val shareIntent = withContext(Dispatchers.IO) {
                                            ReportShare.createIntent(context, text, "Video Bridge crash report")
                                        }
                                        context.startActivity(Intent.createChooser(shareIntent, "Share crash report"))
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Share crash logs") }
                        OutlinedButton(
                            onClick = { saveCrashLauncher.launch("video-bridge-crash.txt") },
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
            Text("Event log (redacted)", style = MaterialTheme.typography.titleMedium)
        }

        // Filter chips
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (filter in DiagFilter.values()) {
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
            )
        }

        // Tracing toggle
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Switch(checked = d.tvTracingEnabled, onCheckedChange = onToggleTvTracing)
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
                    entry = entry,
                    fmt = timeFmt,
                    onCopy = {
                        val ts = timeFmt.format(Date(entry.timeMillis))
                        val line = "$ts [${entry.level}] ${entry.category}: ${entry.message}"
                        clipboardScope.launch {
                            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Video Bridge log", line)))
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun SnapshotCard(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
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
private fun LogEntryRow(entry: LogEntry, fmt: SimpleDateFormat, onCopy: () -> Unit) {
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
        modifier = Modifier
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
fun SettingsScreen(
    onLogout: () -> Unit,
    onDeleteAll: () -> Unit,
    onAbout: () -> Unit,
    onDiagnostics: () -> Unit,
    onDownloads: () -> Unit,
    onServers: () -> Unit,
    preferDirectPlay: Boolean,
    onPreferDirectPlayChange: (Boolean) -> Unit,
    transcodeLocalOnDevice: Boolean,
    onTranscodeLocalChange: (Boolean) -> Unit,
    maxVideoHeight: Int,
    onMaxVideoHeightChange: (Int) -> Unit,
    autoPlayNextEpisode: Boolean,
    onAutoPlayNextChange: (Boolean) -> Unit,
    autoSkipSegments: Boolean,
    onAutoSkipSegmentsChange: (Boolean) -> Unit,
    preferredAudioLanguage: String,
    onPreferredAudioLanguageChange: (String) -> Unit,
    preferredSubtitleLanguage: String,
    onPreferredSubtitleLanguageChange: (String) -> Unit,
    transcodeOnlineOnDevice: Boolean,
    onTranscodeOnlineChange: (Boolean) -> Unit,
    onDeviceAllowCpu: Boolean,
    onOnDeviceAllowCpuChange: (Boolean) -> Unit,
    backgroundPlaybackUnrestricted: Boolean,
    onAllowBackgroundPlayback: () -> Unit,
    onResetTvCapabilities: () -> Unit,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    var preferDp by remember { mutableStateOf(preferDirectPlay) }
    var transcodeLocal by remember { mutableStateOf(transcodeLocalOnDevice) }
    var maxRes by remember { mutableStateOf(maxVideoHeight) }
    var autoNext by remember { mutableStateOf(autoPlayNextEpisode) }
    var autoSkip by remember { mutableStateOf(autoSkipSegments) }
    var audioLang by remember { mutableStateOf(preferredAudioLanguage) }
    var subtitleLang by remember { mutableStateOf(preferredSubtitleLanguage) }
    var transcodeOnline by remember { mutableStateOf(transcodeOnlineOnDevice) }
    var allowCpu by remember { mutableStateOf(onDeviceAllowCpu) }
    var capsReset by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "Manage your connection and app data.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SettingsToggleRow(
            icon = Icons.Rounded.HighQuality,
            label = "Prefer original quality",
            description = "Stream the original without transcoding when the TV can play it; falls back to a " +
                "server transcode automatically if the TV (Cast or DLNA) can't.",
            checked = preferDp,
            onCheckedChange = { preferDp = it; onPreferDirectPlayChange(it) },
        )
        SettingsMaxResolutionRow(
            selected = maxRes,
            onSelect = { maxRes = it; onMaxVideoHeightChange(it) },
        )
        SettingsToggleRow(
            icon = Icons.Rounded.HighQuality,
            label = "Autoplay next episode",
            description = "When an episode finishes, automatically start the next one in the series on the " +
                "same TV. On by default; applies to Jellyfin TV episodes.",
            checked = autoNext,
            onCheckedChange = { autoNext = it; onAutoPlayNextChange(it) },
        )
        SettingsToggleRow(
            icon = Icons.Rounded.HighQuality,
            label = "Auto-skip intro/recap",
            description = "Automatically skip intro, recap and outro segments when your Jellyfin server " +
                "provides them (10.10+ with the Intro Skipper plugin). A manual \u201CSkip\u201D button is " +
                "always shown too. On by default.",
            checked = autoSkip,
            onCheckedChange = { autoSkip = it; onAutoSkipSegmentsChange(it) },
        )
        SettingsLanguageRow(
            icon = Icons.Rounded.Translate,
            label = "Preferred audio language",
            description = "Auto-select this audio language when a video has it. Otherwise the server " +
                "default is used. Your choice per show is remembered and overrides this.",
            selectedCode = audioLang,
            noneLabel = "No preference",
            onSelect = { audioLang = it; onPreferredAudioLanguageChange(it) },
        )
        SettingsLanguageRow(
            icon = Icons.Rounded.Subtitles,
            label = "Preferred subtitle language",
            description = "Auto-turn on subtitles in this language when a video has them (they're burned " +
                "in). \u201CNo preference\u201D leaves subtitles off. Your choice per show overrides this.",
            selectedCode = subtitleLang,
            noneLabel = "No preference (off)",
            onSelect = { subtitleLang = it; onPreferredSubtitleLanguageChange(it) },
        )
        SettingsToggleRow(
            icon = Icons.Rounded.HighQuality,
            label = "Transcode local videos on this device",
            description = "When a local file is in a format the TV can't play, re-encode it on the phone " +
                "(hardware) instead of just sending it. On by default \u2014 directly-playable files are still sent as-is.",
            checked = transcodeLocal,
            onCheckedChange = { transcodeLocal = it; onTranscodeLocalChange(it) },
        )
        SettingsToggleRow(
            icon = Icons.Rounded.HighQuality,
            label = "Transcode online videos on this device",
            description = "Experimental. If the server can't produce a format the TV needs (or can't keep " +
                "up), transcode the Jellyfin video on the phone as a last resort, after direct play and a " +
                "server transcode. Off by default \u2014 the server normally does the transcoding.",
            checked = transcodeOnline,
            onCheckedChange = { transcodeOnline = it; onTranscodeOnlineChange(it) },
        )
        SettingsToggleRow(
            icon = Icons.Rounded.HighQuality,
            label = "Allow CPU on-device transcode",
            description = "Let on-device transcoding fall back to a software (CPU) encoder when no hardware " +
                "encoder fits. Off by default \u2014 CPU encoding is slow and may not keep up above low resolutions.",
            checked = allowCpu,
            onCheckedChange = { allowCpu = it; onOnDeviceAllowCpuChange(it) },
        )
        SettingsToggleRow(
            icon = Icons.Rounded.BatteryStd,
            label = "Allow background playback (screen-off)",
            description = "Let the app run unrestricted so casting keeps working when the screen is off. " +
                "Without it, some phones (notably Samsung) suspend the app in the background and playback " +
                "stalls on \u201Cbuffering\u201D until you wake the screen. Opens a system prompt.",
            checked = backgroundPlaybackUnrestricted,
            onCheckedChange = { onAllowBackgroundPlayback() },
        )
        SettingsRow(
            icon = Icons.Rounded.Restore,
            label = if (capsReset) "TV capabilities reset \u2713" else "Reset learned TV capabilities",
            onClick = { capsReset = true; onResetTvCapabilities() },
        )
        SettingsRow(icon = Icons.Rounded.Dns, label = "Servers", onClick = onServers)
        SettingsRow(icon = Icons.Rounded.Download, label = "Downloads", onClick = onDownloads)
        SettingsRow(icon = Icons.Rounded.BugReport, label = "Diagnostics", onClick = onDiagnostics)
        SettingsRow(icon = Icons.Rounded.Info, label = "About & open-source licenses", onClick = onAbout)
        SettingsRow(icon = Icons.AutoMirrored.Rounded.Logout, label = "Log out", onClick = onLogout)
        SettingsRow(
            icon = Icons.Rounded.DeleteForever,
            label = "Delete all app data",
            onClick = { confirmDelete = true },
            containerColor = MaterialTheme.colorScheme.errorContainer,
            onContainerColor = MaterialTheme.colorScheme.onErrorContainer,
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete all app data?") },
            text = { Text("This removes your server profile, saved login, and all local data. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDeleteAll() }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SettingsMaxResolutionRow(selected: Int, onSelect: (Int) -> Unit) {
    val options = listOf(2160 to "4K (2160p)", 1080 to "1080p", 720 to "720p", 480 to "480p")
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.first == selected }?.second ?: "4K (2160p)"
    Card(
        modifier = Modifier.fillMaxWidth().clickable { expanded = true },
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(Icons.Rounded.HighQuality, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(24.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Maximum resolution", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    "Cap the resolution streamed to the TV; taller sources are transcoded down. 4K also " +
                        "passes HDR through to a capable TV (otherwise it falls back to 1080p SDR).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box {
                OutlinedButton(onClick = { expanded = true }) { Text(selectedLabel) }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    options.forEach { (value, label) ->
                        DropdownMenuItem(text = { Text(label) }, onClick = { expanded = false; onSelect(value) })
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsLanguageRow(
    icon: ImageVector,
    label: String,
    description: String,
    selectedCode: String,
    noneLabel: String,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedName = Languages.nameFor(selectedCode) ?: noneLabel
    Card(
        modifier = Modifier.fillMaxWidth().clickable { expanded = true },
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(24.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(label, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Box {
                OutlinedButton(onClick = { expanded = true }) { Text(selectedName) }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    DropdownMenuItem(text = { Text(noneLabel) }, onClick = { expanded = false; onSelect("") })
                    Languages.COMMON.forEach { lang ->
                        DropdownMenuItem(
                            text = { Text(lang.name) },
                            onClick = { expanded = false; onSelect(lang.code) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsToggleRow(
    icon: ImageVector,
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onCheckedChange(!checked) },
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(24.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(label, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

@Composable
private fun SettingsRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    containerColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.surfaceContainer,
    onContainerColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(icon, contentDescription = null, tint = onContainerColor, modifier = Modifier.size(24.dp))
            Text(
                label,
                style = MaterialTheme.typography.titleSmall,
                color = onContainerColor,
                modifier = Modifier.weight(1f),
            )
            Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = onContainerColor,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
fun AboutScreen() {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ElevatedCard(
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Video Bridge",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Text(
                    "Open-source licenses are listed in docs/LICENSES.md and bundled at build time.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Text(
                    "No telemetry, analytics, ads, or tracking.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
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
    JellyfinBridgeTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            Box(Modifier.padding(16.dp)) { AboutScreen() }
        }
    }
}

@Preview(name = "Settings toggle", showBackground = true)
@Preview(name = "Settings toggle · dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun SettingsToggleRowPreview() {
    var checked by remember { mutableStateOf(true) }
    JellyfinBridgeTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            Box(Modifier.padding(16.dp)) {
                SettingsToggleRow(
                    icon = Icons.Rounded.HighQuality,
                    label = "Prefer original quality",
                    description = "Try the original file first and let the TV decode it; fall back to a " +
                        "server transcode only if it can't.",
                    checked = checked,
                    onCheckedChange = { checked = it },
                )
            }
        }
    }
}

@Preview(name = "Log entries", showBackground = true)
@Preview(name = "Log entries · dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun LogEntryRowPreview() {
    val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }
    JellyfinBridgeTheme {
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
