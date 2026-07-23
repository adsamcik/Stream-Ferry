package com.videobridge.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * App theme honouring the system light/dark setting (§18 light/dark requirement). Uses Material You
 * **dynamic colour** (wallpaper-derived) — the personalised, expressive colour foundation — which is
 * always available at our `minSdk` 34 (well past the API 31 it was introduced in).
 */
@Composable
fun JellyfinBridgeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    // Dynamic (wallpaper-derived) Material You colour. Always available: minSdk 34 is well past API 31.
    val colorScheme = if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
