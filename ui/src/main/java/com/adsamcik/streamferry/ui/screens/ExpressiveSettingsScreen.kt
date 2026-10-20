@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.adsamcik.streamferry.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.BatteryStd
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.adsamcik.streamferry.core.language.Languages
import com.adsamcik.streamferry.core.volume.NightVolumePolicy
import com.adsamcik.streamferry.ui.theme.ThemeMode
import java.time.LocalTime

private data class ResolutionChoice(val height: Int, val label: String)

private val ResolutionChoices = listOf(
    ResolutionChoice(2160, "4K (2160p)"),
    ResolutionChoice(1080, "1080p"),
    ResolutionChoice(720, "720p"),
    ResolutionChoice(480, "480p"),
)

/**
 * Task-oriented settings using the app's expressive theme, grouped controls and clear current values.
 */
@Composable
fun ExpressiveSettingsScreen(
    onLogout: () -> Unit,
    onDeleteAll: () -> Unit,
    onAbout: () -> Unit,
    onDiagnostics: () -> Unit,
    onPlaybackHistory: () -> Unit,
    onDownloads: () -> Unit,
    onServers: () -> Unit,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
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
    backgroundPlaybackUnrestricted: Boolean,
    onAllowBackgroundPlayback: () -> Unit,
    onResetTvCapabilities: () -> Unit,
    nightVolumePolicy: NightVolumePolicy,
    onNightVolumePolicyChange: (NightVolumePolicy) -> Unit,
    canSignOut: Boolean = true,
    modifier: Modifier = Modifier,
) {
    // These preferences are backed by synchronous stores rather than observable Compose state. Keep
    // local values so controls respond immediately, while input keys still accept an external refresh.
    var preferOriginal by rememberSaveable(preferDirectPlay) { mutableStateOf(preferDirectPlay) }
    var transcodeLocal by rememberSaveable(transcodeLocalOnDevice) { mutableStateOf(transcodeLocalOnDevice) }
    var maxResolution by rememberSaveable(maxVideoHeight) { mutableIntStateOf(maxVideoHeight) }
    var autoNext by rememberSaveable(autoPlayNextEpisode) { mutableStateOf(autoPlayNextEpisode) }
    var autoSkip by rememberSaveable(autoSkipSegments) { mutableStateOf(autoSkipSegments) }
    var audioLanguage by rememberSaveable(preferredAudioLanguage) { mutableStateOf(preferredAudioLanguage) }
    var subtitleLanguage by rememberSaveable(preferredSubtitleLanguage) {
        mutableStateOf(preferredSubtitleLanguage)
    }
    var capabilitiesReset by rememberSaveable { mutableStateOf(false) }
    var confirmReset by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "appearance-heading") { SettingsHeading("Appearance") }
        item(key = "appearance-group") {
            SettingsGroup { SettingsThemeModeRow(selected = themeMode, onSelect = onThemeModeChange) }
        }

        item(key = "playback-heading") { SettingsHeading("Playback") }
        item(key = "playback-group") {
            SettingsGroup {
                SettingsSwitchRow(
                    icon = Icons.Rounded.PlayCircle,
                    title = "Autoplay next episode",
                    supporting = "Continue a series on the same TV.",
                    checked = autoNext,
                    onCheckedChange = { autoNext = it; onAutoPlayNextChange(it) },
                )
                SettingsSwitchRow(
                    icon = Icons.Rounded.FastForward,
                    title = "Skip intros and recaps",
                    supporting = "When the source provides skip markers.",
                    checked = autoSkip,
                    onCheckedChange = { autoSkip = it; onAutoSkipSegmentsChange(it) },
                )
                SettingsNightVolumeRow(policy = nightVolumePolicy, onSave = onNightVolumePolicyChange)
                SettingsActionRow(
                    icon = Icons.Rounded.BatteryStd,
                    title = "Background playback",
                    supporting = if (backgroundPlaybackUnrestricted) "Battery use is unrestricted" else "Allow unrestricted battery use",
                    onClick = onAllowBackgroundPlayback,
                )
            }
        }

        item(key = "languages-heading") { SettingsHeading("Audio & subtitles") }
        item(key = "languages-group") {
            SettingsGroup {
                SettingsLanguageRow(
                    icon = Icons.Rounded.Translate,
                    title = "Audio language",
                    supporting = "Preferred track when available",
                    selectedCode = audioLanguage,
                    noneLabel = "No preference",
                    onSelect = { audioLanguage = it; onPreferredAudioLanguageChange(it) },
                )
                SettingsLanguageRow(
                    icon = Icons.Rounded.Subtitles,
                    title = "Subtitle language",
                    supporting = "Turn on a matching track when available",
                    selectedCode = subtitleLanguage,
                    noneLabel = "Off by default",
                    onSelect = { subtitleLanguage = it; onPreferredSubtitleLanguageChange(it) },
                )
            }
        }
        item(key = "languages-note") {
            SettingsNote("Choices remembered for a show take priority over these defaults.")
        }

        item(key = "quality-heading") { SettingsHeading("Video quality") }
        item(key = "quality-group") {
            SettingsGroup {
                SettingsResolutionRow(selected = maxResolution, onSelect = { maxResolution = it; onMaxVideoHeightChange(it) })
                SettingsSwitchRow(
                    icon = Icons.Rounded.HighQuality,
                    title = "Prefer original video",
                    supporting = "Try the original before converting on the server.",
                    checked = preferOriginal,
                    onCheckedChange = { preferOriginal = it; onPreferDirectPlayChange(it) },
                )
                SettingsSwitchRow(
                    icon = Icons.Rounded.Tune,
                    title = "Convert local videos on phone",
                    supporting = "Convert incompatible files when supported. Uses more battery.",
                    checked = transcodeLocal,
                    onCheckedChange = { transcodeLocal = it; onTranscodeLocalChange(it) },
                )
            }
        }
        item(key = "quality-note") {
            SettingsNote("Defaults for new playback. You can change the output while a video is playing.")
        }

        item(key = "library-heading") { SettingsHeading("Library & servers") }
        item(key = "library-group") {
            SettingsGroup {
                SettingsActionRow(Icons.Rounded.History, "Playback history", "Continue a recent video", onPlaybackHistory)
                SettingsActionRow(Icons.Rounded.Download, "Downloads", "Offline copies and download progress", onDownloads)
                SettingsActionRow(Icons.Rounded.Dns, "Jellyfin servers", "Add, switch or forget a server", onServers)
            }
        }

        item(key = "support-heading") { SettingsHeading("Help & troubleshooting") }
        item(key = "support-group") {
            SettingsGroup {
                SettingsActionRow(Icons.Rounded.BugReport, "Diagnostics", "Connection details, logs and reports", onDiagnostics)
                SettingsActionRow(
                    icon = Icons.Rounded.Restore,
                    title = "Reset TV compatibility",
                    supporting = if (capabilitiesReset) "Reset complete. TV preferences will be learned again." else "Forget learned formats and connection choices",
                    onClick = { confirmReset = true },
                )
                SettingsActionRow(Icons.Rounded.Info, "About & licenses", "App version, privacy and licenses", onAbout)
            }
        }

        item(key = "account-heading") { SettingsHeading("Account & data") }
        item(key = "account-group") {
            SettingsGroup {
                if (canSignOut) {
                    SettingsActionRow(Icons.AutoMirrored.Rounded.Logout, "Sign out", "Remove the saved login for this server", onLogout)
                }
                ListItem(
                    onClick = { confirmDelete = true },
                    leadingContent = { SettingsIcon(Icons.Rounded.DeleteForever, destructive = true) },
                    supportingContent = { Text("Remove accounts, downloads and app preferences") },
                    trailingContent = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null) },
                    shapes = ListItemDefaults.shapes(shape = MaterialTheme.shapes.extraSmall),
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                ) {
                    Text("Delete all app data", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset TV compatibility?") },
            text = { Text("Forget learned video formats and preferred TV connections. Current playback will continue.") },
            confirmButton = {
                TextButton(onClick = {
                    onResetTvCapabilities()
                    capabilitiesReset = true
                    confirmReset = false
                }) { Text("Reset") }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete all app data?") },
            text = {
                Text("Remove saved logins, downloads, playback history and app preferences. This cannot be undone.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        onDeleteAll()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            },
        )
    }
}

private enum class NightVolumeMode { OFF, GRADUAL, HARD }

@Composable
private fun SettingsNightVolumeRow(policy: NightVolumePolicy, onSave: (NightVolumePolicy) -> Unit) {
    var showDialog by remember { mutableStateOf(false) }
    SettingsValueRow(
        icon = Icons.Rounded.Tune,
        title = "Night volume",
        selectedValue = policy.summary(),
        onClick = { showDialog = true },
    )
    if (!showDialog) return

    var mode by remember(policy) { mutableStateOf(policy.toMode()) }
    var startText by remember(policy) { mutableStateOf((policy as? NightVolumePolicy.Gradual)?.start?.toString() ?: "22:00") }
    var endText by remember(policy) { mutableStateOf((policy as? NightVolumePolicy.Gradual)?.end?.toString() ?: "06:00") }
    var timeText by remember(policy) { mutableStateOf((policy as? NightVolumePolicy.Hard)?.time?.toString() ?: "22:00") }
    var targetPercent by remember(policy) { mutableStateOf(policy.targetPercentText()) }
    var error by remember { mutableStateOf<String?>(null) }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    Dialog(onDismissRequest = { showDialog = false }) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 600.dp)
                .fillMaxHeight(0.9f)
                .imePadding(),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Night volume", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Column(
                    modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Times use this phone's local time. Automatic changes only reduce volume during active playback.")
                    Column(
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth().selectableGroup(),
                    ) {
                        NightVolumeModeButton("Off", mode == NightVolumeMode.OFF, Modifier.fillMaxWidth()) {
                            mode = NightVolumeMode.OFF
                            error = null
                        }
                        NightVolumeModeButton("Gradual reduction", mode == NightVolumeMode.GRADUAL, Modifier.fillMaxWidth()) {
                            mode = NightVolumeMode.GRADUAL
                            error = null
                        }
                        NightVolumeModeButton("At a set time", mode == NightVolumeMode.HARD, Modifier.fillMaxWidth()) {
                            mode = NightVolumeMode.HARD
                            error = null
                        }
                    }
                    when (mode) {
                        NightVolumeMode.OFF -> Text("No automatic volume changes.")
                        NightVolumeMode.GRADUAL -> {
                            NightVolumeTimeField(startText, "Start time (HH:mm)", error != null) {
                                startText = it
                                error = null
                            }
                            NightVolumeTimeField(endText, "End time (HH:mm)", error != null) {
                                endText = it
                                error = null
                            }
                        }
                        NightVolumeMode.HARD -> {
                            NightVolumeTimeField(timeText, "Time (HH:mm)", error != null) {
                                timeText = it
                                error = null
                            }
                        }
                    }
                    if (mode != NightVolumeMode.OFF) {
                        OutlinedTextField(
                            value = targetPercent,
                            onValueChange = { targetPercent = it.filter(Char::isDigit).take(3); error = null },
                            label = { Text("Target volume (0–100%)") },
                            singleLine = true,
                            isError = error != null,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Number,
                                imeAction = ImeAction.Done,
                            ),
                            keyboardActions = KeyboardActions(
                                onDone = {
                                    focusManager.clearFocus()
                                    keyboard?.hide()
                                },
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = { showDialog = false },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text("Cancel") }
                    TextButton(
                        onClick = {
                            val saved = runCatching {
                                when (mode) {
                                    NightVolumeMode.OFF -> NightVolumePolicy.Off
                                    NightVolumeMode.GRADUAL -> NightVolumePolicy.Gradual(
                                        LocalTime.parse(startText),
                                        LocalTime.parse(endText),
                                        parseNightVolumeTarget(targetPercent),
                                    )
                                    NightVolumeMode.HARD -> NightVolumePolicy.Hard(
                                        LocalTime.parse(timeText),
                                        parseNightVolumeTarget(targetPercent),
                                    )
                                }
                            }.getOrElse {
                                error = "Use HH:mm times and a target from 0 to 100%."
                                null
                            }
                            if (saved != null) {
                                onSave(saved)
                                showDialog = false
                            }
                        },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text("Save") }
                }
            }
        }
    }
}

@Composable
private fun NightVolumeTimeField(
    value: String,
    label: String,
    isError: Boolean,
    onValueChange: (String) -> Unit,
) {
    val focusManager = LocalFocusManager.current
    OutlinedTextField(
        value = value,
        onValueChange = { input ->
            onValueChange(input.filter { it.isDigit() || it == ':' }.take(5))
        },
        label = { Text(label) },
        singleLine = true,
        isError = isError,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Ascii,
            imeAction = ImeAction.Next,
        ),
        keyboardActions = KeyboardActions(
            onNext = { focusManager.moveFocus(FocusDirection.Down) },
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun parseNightVolumeTarget(value: String): Float =
    value.toFloat().div(100f).also { require(it in 0f..1f) }

@Composable
private fun NightVolumeModeButton(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val buttonModifier = modifier
        .heightIn(min = 48.dp)
        .semantics {
            this.selected = selected
            role = Role.RadioButton
        }
    if (selected) FilledTonalButton(onClick = onClick, modifier = buttonModifier) { Text(label) }
    else OutlinedButton(onClick = onClick, modifier = buttonModifier) { Text(label) }
}

private fun NightVolumePolicy.toMode(): NightVolumeMode = when (this) {
    NightVolumePolicy.Off -> NightVolumeMode.OFF
    is NightVolumePolicy.Gradual -> NightVolumeMode.GRADUAL
    is NightVolumePolicy.Hard -> NightVolumeMode.HARD
}

private fun NightVolumePolicy.targetPercentText(): String = when (this) {
    NightVolumePolicy.Off -> "40"
    is NightVolumePolicy.Gradual -> (targetVolume * 100).toInt().toString()
    is NightVolumePolicy.Hard -> (targetVolume * 100).toInt().toString()
}

private fun NightVolumePolicy.summary(): String = when (this) {
    NightVolumePolicy.Off -> "Off"
    is NightVolumePolicy.Gradual -> "Gradual · $start → $end · ${targetPercentText()}%"
    is NightVolumePolicy.Hard -> "At $time · ${targetPercentText()}%"
}
@Composable
private fun SettingsHeading(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 2.dp).semantics { heading() },
    )
}

@Composable
private fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = Color.Transparent,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap), content = content)
    }
}

@Composable
private fun SettingsNote(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 12.dp))
}

@Composable
private fun SettingsSwitchRow(
    icon: ImageVector,
    title: String,
    supporting: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    ListItem(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = Modifier.fillMaxWidth(),
        leadingContent = { SettingsIcon(icon) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        supportingContent = { Text(supporting) },
        shapes = ListItemDefaults.shapes(shape = MaterialTheme.shapes.extraSmall),
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun SettingsResolutionRow(selected: Int, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = ResolutionChoices.firstOrNull { it.height == selected }?.label ?: "${selected}p"

    Box {
        SettingsValueRow(
            icon = Icons.Rounded.HighQuality,
            title = "Maximum resolution",
            selectedValue = selectedLabel,
            onClick = { expanded = true },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ResolutionChoices.forEach { choice ->
                DropdownMenuItem(
                    text = { Text(choice.label) },
                    trailingIcon = {
                        if (choice.height == selected) Icon(Icons.Rounded.Check, contentDescription = null)
                    },
                    onClick = {
                        expanded = false
                        onSelect(choice.height)
                    },
                )
            }
        }
    }
}

@Composable
private fun SettingsLanguageRow(
    icon: ImageVector,
    title: String,
    supporting: String,
    selectedCode: String,
    noneLabel: String,
    onSelect: (String) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    SettingsValueRow(
        icon = icon,
        title = title,
        selectedValue = Languages.nameFor(selectedCode) ?: noneLabel,
        onClick = { expanded = true },
    )
    if (expanded) {
        var query by rememberSaveable { mutableStateOf("") }
        val languages = remember(query) {
            Languages.COMMON.filter { it.name.contains(query.trim(), ignoreCase = true) }.sortedBy { it.name }
        }
        val selectedAliases = Languages.aliases(selectedCode)
        AlertDialog(
            onDismissRequest = { expanded = false },
            title = { Text(title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(supporting, style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("Search languages") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp).selectableGroup()) {
                        item(key = "default") {
                            LanguageChoice(noneLabel, selectedCode.isBlank()) { onSelect(""); expanded = false }
                        }
                        items(languages, key = { it.code }) { language ->
                            LanguageChoice(language.name, language.code in selectedAliases) {
                                onSelect(language.code)
                                expanded = false
                            }
                        }
                        if (languages.isEmpty()) item(key = "empty") {
                            Text("No matching languages", modifier = Modifier.padding(16.dp))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { expanded = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun LanguageChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    ListItem(
        selected = selected,
        onClick = onClick,
        trailingContent = { RadioButton(selected = selected, onClick = null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    ) { Text(label) }
}

@Composable
private fun SettingsThemeModeRow(selected: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    Column(Modifier.background(MaterialTheme.colorScheme.surfaceContainerLow).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SettingsIcon(Icons.Rounded.DarkMode)
            Text("Theme", style = MaterialTheme.typography.titleMedium)
        }
        Row(
            modifier = Modifier.fillMaxWidth().selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
        ) {
            ThemeMode.entries.forEachIndexed { index, mode ->
                ToggleButton(
                    checked = mode == selected,
                    onCheckedChange = { if (it) onSelect(mode) },
                    modifier = Modifier.weight(1f).semantics {
                        role = Role.RadioButton
                        this.selected = mode == selected
                    },
                    shapes = when (index) {
                        0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                        ThemeMode.entries.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                        else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp),
                ) { Text(if (mode == ThemeMode.SYSTEM) "System" else mode.label) }
            }
        }
    }
}

@Composable
private fun SettingsValueRow(
    icon: ImageVector,
    title: String,
    selectedValue: String,
    onClick: () -> Unit,
) {
    val effectsMotion = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    ListItem(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        supportingContent = {
                AnimatedContent(
                    targetState = selectedValue,
                    transitionSpec = {
                        fadeIn(effectsMotion).togetherWith(fadeOut(effectsMotion))
                    },
                    label = "selected setting value",
                ) { value ->
                    Text(
                        value,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
        },
        leadingContent = { SettingsIcon(icon) },
        trailingContent = { Icon(Icons.Rounded.ExpandMore, contentDescription = null) },
        shapes = ListItemDefaults.shapes(shape = MaterialTheme.shapes.extraSmall),
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun SettingsActionRow(
    icon: ImageVector,
    title: String,
    supporting: String,
    onClick: () -> Unit,
) {
    ListItem(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        supportingContent = { Text(supporting) },
        leadingContent = { SettingsIcon(icon) },
        trailingContent = {
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null)
        },
        shapes = ListItemDefaults.shapes(shape = MaterialTheme.shapes.extraSmall),
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun SettingsIcon(icon: ImageVector, destructive: Boolean = false) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = if (destructive) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        contentColor = if (destructive) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
        }
    }
}
