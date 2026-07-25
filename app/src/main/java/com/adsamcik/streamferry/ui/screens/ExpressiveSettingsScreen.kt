package com.adsamcik.streamferry.ui.screens

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.BatteryStd
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.HighQuality
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.adsamcik.streamferry.R
import com.adsamcik.streamferry.core.language.Languages

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
    backgroundPlaybackUnrestricted: Boolean,
    onAllowBackgroundPlayback: () -> Unit,
    onResetTvCapabilities: () -> Unit,
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

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 36.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
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
                SettingsSwitchRow(
                    icon = Icons.Rounded.BatteryStd,
                    title = "Allow background playback (screen-off)",
                    supporting = if (backgroundPlaybackUnrestricted) {
                        "Background playback is unrestricted on this phone."
                    } else {
                        "Review Android battery restrictions so playback does not stall when the screen is off."
                    },
                    checked = backgroundPlaybackUnrestricted,
                    onCheckedChange = { onAllowBackgroundPlayback() },
                )
            }
        }

        item(key = "advanced-toggle") {
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
                    if (advancedExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = if (advancedExpanded) "Collapse advanced playback" else "Expand advanced playback",
                )
            }
        }
        if (advancedExpanded) {
            item(key = "advanced-group") {
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
                            "TV capabilities reset ✓"
                        } else {
                            "Reset learned TV capabilities"
                        },
                        supporting = "Try formats again after a TV or receiver update.",
                        onClick = {
                            capabilitiesReset = true
                            onResetTvCapabilities()
                        },
                    )
                }
            }
        }

        item(key = "manage-heading") { SettingsHeading("Manage") }
        item(key = "manage-group") {
            SettingsGroup {
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
        headlineContent = { Text(title, style = MaterialTheme.typography.titleMedium) },
        supportingContent = { Text(supporting) },
        leadingContent = { SettingsIcon(icon) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            ),
    )
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
private fun SettingsValueRow(
    icon: ImageVector,
    title: String,
    supporting: String,
    selectedValue: String,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(title, style = MaterialTheme.typography.titleMedium) },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(supporting)
                Text(
                    selectedValue,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        },
        leadingContent = { SettingsIcon(icon) },
        trailingContent = { Icon(Icons.Rounded.ExpandMore, contentDescription = null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick),
    )
}

@Composable
private fun SettingsActionRow(
    icon: ImageVector,
    title: String,
    supporting: String,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(title, style = MaterialTheme.typography.titleMedium) },
        supportingContent = { Text(supporting) },
        leadingContent = { SettingsIcon(icon) },
        trailingContent = {
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null)
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick),
    )
}

@Composable
private fun SettingsLinkRow(
    @DrawableRes iconRes: Int,
    title: String,
    supporting: String,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(title, style = MaterialTheme.typography.titleMedium) },
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
        modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick),
    )
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
