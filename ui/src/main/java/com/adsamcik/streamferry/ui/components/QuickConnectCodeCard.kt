package com.adsamcik.streamferry.ui.components

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.os.PersistableBundle
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

/**
 * Visible, keyboard/TalkBack/switch-access compatible copy affordance for Jellyfin Quick Connect.
 * The confirmation never repeats the code and the clipboard item is marked sensitive for Android's
 * clipboard preview and expiry handling.
 */
@Composable
fun QuickConnectCodeCard(
    code: String,
    modifier: Modifier = Modifier,
    onCopied: () -> Unit = {},
) {
    val context = LocalContext.current
    var copied by rememberSaveable(code) { mutableStateOf(false) }
    val effectsMotion = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val spatialMotion = MaterialTheme.motionScheme.defaultSpatialSpec<IntSize>()

    fun copyCode() {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        val clip = ClipData.newPlainText("Quick Connect code", code)
        clip.description.extras = PersistableBundle().apply {
            putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
        }
        clipboard.setPrimaryClip(clip)
        copied = true
        onCopied()
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = "Quick Connect code",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() },
            )
            SelectionContainer {
                Text(
                    text = code,
                    style = MaterialTheme.typography.displaySmall,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.testTag("quick-connect-code"),
                )
            }
            Text(
                text = "Enter this code in Jellyfin. Stream Ferry will continue automatically after approval.",
                style = MaterialTheme.typography.bodyMedium,
            )
            FilledTonalButton(
                onClick = ::copyCode,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("quick-connect-copy"),
            ) {
                AnimatedContent(
                    targetState = copied,
                    transitionSpec = {
                        fadeIn(effectsMotion).togetherWith(fadeOut(effectsMotion))
                    },
                    label = "copy code action",
                ) { isCopied ->
                    Icon(
                        if (isCopied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                        contentDescription = null,
                    )
                }
                AnimatedContent(
                    targetState = copied,
                    transitionSpec = {
                        fadeIn(effectsMotion).togetherWith(fadeOut(effectsMotion))
                    },
                    label = "copy code label",
                ) { isCopied ->
                    Text(
                        if (isCopied) "Copied" else "Copy code",
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
            AnimatedVisibility(
                visible = copied,
                enter = fadeIn(effectsMotion) + expandVertically(spatialMotion),
                exit = fadeOut(effectsMotion) + shrinkVertically(spatialMotion),
            ) {
                Text(
                    text = "Code copied securely",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier
                        .testTag("quick-connect-copy-feedback")
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
    }
}
