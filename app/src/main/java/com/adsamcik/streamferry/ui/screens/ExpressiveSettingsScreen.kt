package com.adsamcik.streamferry.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateFloatAsState
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
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
import com.adsamcik.streamferry.R
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
 * Task-oriented settings built from stable Material 3 components. Everyday playback choices stay in
 * the main flow; device conversion and compatibility maintenance use progressive disclosure.
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
    var advancedExpanded by rememberSaveable { mutableStateOf(false) }
    var capabilitiesReset by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val advancedIconRotation by animateFloatAsState(
        targetValue = if (advancedExpanded) 180f else 0f,
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "advanced settings icon",
    )

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 36.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item(key = "appearance-heading") { SettingsHeading("Appearance") }
        item(key = "appearance-group") {
            SettingsGroup {
                SettingsThemeModeRow(
                    selected = themeMode,
                    onSelect = onThemeModeChange,
                )
            }
        }

        item(key = "playback-heading") { SettingsHeading("Playback") }
        item(key = "playback-group") {
            SettingsGroup {
                SettingsSwitchRow(
                    icon = Icons.Rounded.PlayCircle,
                    title = "Autoplay next episode",
                    supporting = "Automatically continue a Jellyfin series on the same TV.",
                    checked = autoNext,
                    onCheckedChange = {
                        autoNext = it
                        onAutoPlayNextChange(it)
                    },
                )
                SettingsDivider()
                SettingsSwitchRow(
                    icon = Icons.Rounded.FastForward,
                    title = "Auto-skip intro and recap",
                    supporting = "Use segment information from compatible Jellyfin servers when available.",
                    checked = autoSkip,
                    onCheckedChange = {
                        autoSkip = it
                        onAutoSkipSegmentsChange(it)
                    },
                )
                SettingsDivider()
                SettingsLanguageRow(
                    icon = Icons.Rounded.Translate,
                    title = "Preferred audio language",
                    supporting = "Use it when a video offers a matching track; per-show choices take priority.",
                    selectedCode = audioLanguage,
                    noneLabel = "No preference",
                    onSelect = {
                        audioLanguage = it
                        onPreferredAudioLanguageChange(it)
                    },
                )
                SettingsDivider()
                SettingsLanguageRow(
                    icon = Icons.Rounded.Subtitles,
                    title = "Preferred subtitle language",
                    supporting = "Turn on a matching track automatically; per-show choices take priority.",
                    selectedCode = subtitleLanguage,
                    noneLabel = "No preference (off)",
                    onSelect = {
                        subtitleLanguage = it
                        onPreferredSubtitleLanguageChange(it)
                    },
                )
            }
        }

        item(key = "night-volume-heading") { SettingsHeading("Volume automation") }
        item(key = "night-volume-group") {
            SettingsGroup {
                SettingsNightVolumeRow(policy = nightVolumePolicy, onSave = onNightVolumePolicyChange)
            }
        }

        item(key = "quality-heading") { SettingsHeading("Quality and reliability") }
        item(key = "quality-group") {
            SettingsGroup {
                SettingsSwitchRow(
                    icon = Icons.Rounded.HighQuality,
                    title = "Direct play first",
                    supporting = "Stream the original when the TV supports it. Turn this off to start with a server transcode.",
                    checked = preferOriginal,
                    onCheckedChange = {
                        preferOriginal = it
                        onPreferDirectPlayChange(it)
                    },
                )
                SettingsDivider()
                SettingsResolutionRow(
                    selected = maxResolution,
                    onSelect = {
                        maxResolution = it
                        onMaxVideoHeightChange(it)
                    },
                )
                SettingsDivider()
                SettingsActionRow(
                    icon = Icons.Rounded.BatteryStd,
                    title = "Background playback",
                    supporting = if (backgroundPlaybackUnrestricted) {
                        "Unrestricted on this phone. Tap to review Android's battery setting."
                    } else {
                        "Allow unrestricted battery use so playback is less likely to stall with the screen off."
                    },
                    onClick = onAllowBackgroundPlayback,
                )
            }
        }

        item(key = "advanced-toggle") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FilledTonalButton(
                    onClick = { advancedExpanded = !advancedExpanded },
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Icon(Icons.Rounded.Tune, contentDescription = null)
                    Text(
                        "Advanced playback",
                        modifier = Modifier.padding(start = 10.dp).weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Icon(
                        Icons.Rounded.ExpandMore,
                        contentDescription = if (advancedExpanded) {
                            "Collapse advanced playback"
                        } else {
                            "Expand advanced playback"
                        },
                        modifier = Modifier.graphicsLayer { rotationZ = advancedIconRotation },
                    )
                }
                AnimatedVisibility(
                    visible = advancedExpanded,
                    enter = fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()) +
                        expandVertically(MaterialTheme.motionScheme.defaultSpatialSpec()),
                    exit = fadeOut(MaterialTheme.motionScheme.defaultEffectsSpec()) +
                        shrinkVertically(MaterialTheme.motionScheme.defaultSpatialSpec()),
                ) {
                    SettingsGroup {
                        SettingsSwitchRow(
                            icon = Icons.Rounded.HighQuality,
                            title = "Transcode local videos on this device",
                            supporting = "For compatible Cast receivers and 8-bit SDR files, use phone hardware to re-encode an incompatible format.",
                            checked = transcodeLocal,
                            onCheckedChange = {
                                transcodeLocal = it
                                onTranscodeLocalChange(it)
                            },
                        )
                        SettingsDivider()
                        SettingsActionRow(
                            icon = Icons.Rounded.Restore,
                            title = if (capabilitiesReset) {
                                "Learned TV data reset ✓"
                            } else {
                                "Reset learned TV data"
                            },
                            supporting = "Forget format results, connection preferences, and saved endpoint associations.",
                            onClick = {
                                capabilitiesReset = true
                                onResetTvCapabilities()
                            },
                        )
                    }
                }
            }
        }

        item(key = "manage-heading") { SettingsHeading("Manage") }
        item(key = "manage-group") {
            SettingsGroup {
                SettingsActionRow(
                    icon = Icons.Rounded.History,
                    title = "Playback history",
                    supporting = "Find recent videos and continue from the last confirmed position.",
                    onClick = onPlaybackHistory,
                )
                SettingsDivider()
                SettingsLinkRow(
                    iconRes = R.drawable.in_app_icon_servers,
                    title = "Servers",
                    supporting = "Switch, add or forget Jellyfin servers.",
                    onClick = onServers,
                )
                SettingsDivider()
                SettingsLinkRow(
                    iconRes = R.drawable.in_app_icon_downloads,
                    title = "Downloads",
                    supporting = "Manage offline copies and download progress.",
                    onClick = onDownloads,
                )
                SettingsDivider()
                SettingsLinkRow(
                    iconRes = R.drawable.in_app_icon_diagnostics,
                    title = "Diagnostics",
                    supporting = "Troubleshoot TV, network and playback problems.",
                    onClick = onDiagnostics,
                )
                SettingsDivider()
                SettingsLinkRow(
                    iconRes = R.drawable.in_app_icon_about,
                    title = "About & open-source licenses",
                    supporting = "Version, privacy and open-source notices.",
                    onClick = onAbout,
                )
            }
        }

        item(key = "account-heading") { SettingsHeading("Account and data") }
        item(key = "logout") {
            OutlinedButton(
                onClick = onLogout,
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
            ) {
                Icon(Icons.AutoMirrored.Rounded.Logout, contentDescription = null)
                Text("Log out", modifier = Modifier.padding(start = 8.dp))
            }
        }
        item(key = "delete") {
            FilledTonalButton(
                onClick = { confirmDelete = true },
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
            ) {
                Icon(Icons.Rounded.DeleteForever, contentDescription = null)
                Text("Delete all app data", modifier = Modifier.padding(start = 8.dp))
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete all app data?") },
            text = {
                Text("This removes your server profile, saved login, and all local data. This cannot be undone.")
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
        supporting = "Optionally lower supported TV volume on this phone's local schedule.",
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
                        NightVolumeModeButton("Hard reduction", mode == NightVolumeMode.HARD, Modifier.fillMaxWidth()) {
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
    is NightVolumePolicy.Hard -> "Hard · $time · ${targetPercentText()}%"
}
@Composable
private fun SettingsHeading(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.headlineSmall,
        modifier = Modifier.padding(horizontal = 4.dp).semantics { heading() },
    )
}

@Composable
private fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(content = content)
    }
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 72.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
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
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
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
            supporting = "Cap taller sources; 4K also allows HDR passthrough to a capable TV.",
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
    var expanded by remember { mutableStateOf(false) }
    val selectedName = Languages.nameFor(selectedCode) ?: noneLabel

    Box {
        SettingsValueRow(
            icon = icon,
            title = title,
            supporting = supporting,
            selectedValue = selectedName,
            onClick = { expanded = true },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(noneLabel) },
                trailingIcon = {
                    if (selectedCode.isBlank()) Icon(Icons.Rounded.Check, contentDescription = null)
                },
                onClick = {
                    expanded = false
                    onSelect("")
                },
            )
            Languages.COMMON.forEach { language ->
                DropdownMenuItem(
                    text = { Text(language.name) },
                    trailingIcon = {
                        if (selectedCode.equals(language.code, ignoreCase = true)) {
                            Icon(Icons.Rounded.Check, contentDescription = null)
                        }
                    },
                    onClick = {
                        expanded = false
                        onSelect(language.code)
                    },
                )
            }
        }
    }
}

@Composable
private fun SettingsThemeModeRow(selected: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        SettingsValueRow(
            icon = Icons.Rounded.DarkMode,
            title = "Theme",
            supporting = "Choose light, dark, or follow your device setting.",
            selectedValue = selected.label,
            onClick = { expanded = true },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ThemeMode.entries.forEach { mode ->
                DropdownMenuItem(
                    text = { Text(mode.label) },
                    trailingIcon = {
                        if (mode == selected) Icon(Icons.Rounded.Check, contentDescription = null)
                    },
                    onClick = {
                        expanded = false
                        onSelect(mode)
                    },
                )
            }
        }
    }
}

@Composable
private fun SettingsValueRow(
    icon: ImageVector,
    title: String,
    supporting: String,
    selectedValue: String,
    onClick: () -> Unit,
) {
    val effectsMotion = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    ListItem(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(supporting)
                AnimatedContent(
                    targetState = selectedValue,
                    transitionSpec = {
                        fadeIn(effectsMotion).togetherWith(fadeOut(effectsMotion))
                    },
                    label = "selected setting value",
                ) { value ->
                    Text(
                        value,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
        leadingContent = { SettingsIcon(icon) },
        trailingContent = { Icon(Icons.Rounded.ExpandMore, contentDescription = null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
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
    val effectsMotion = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    ListItem(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        supportingContent = { Text(supporting) },
        leadingContent = { SettingsIcon(icon) },
        trailingContent = {
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null)
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    ) {
        AnimatedContent(
                targetState = title,
                transitionSpec = {
                    fadeIn(effectsMotion).togetherWith(fadeOut(effectsMotion))
                },
                label = "setting action status",
            ) { value ->
                Text(value, style = MaterialTheme.typography.titleMedium)
            }
    }
}

@Composable
private fun SettingsLinkRow(
    @DrawableRes iconRes: Int,
    title: String,
    supporting: String,
    onClick: () -> Unit,
) {
    ListItem(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        supportingContent = { Text(supporting) },
        leadingContent = {
            Image(
                painter = painterResource(iconRes),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(44.dp).clip(MaterialTheme.shapes.small),
            )
        },
        trailingContent = {
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null)
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun SettingsIcon(icon: ImageVector) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
        }
    }
}
