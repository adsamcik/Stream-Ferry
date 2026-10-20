package com.adsamcik.streamferry.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.core.view.WindowCompat
import com.adsamcik.streamferry.ui.screens.DiagnosticsControlsScreen
import com.adsamcik.streamferry.ui.theme.StreamFerryTheme
import com.adsamcik.streamferry.ui.theme.ThemeMode

/** Host Tracebox under the same appearance, expressive styling and insets as the rest of the app. */
class DiagnosticsActivity : ComponentActivity() {
    private val app get() = application as StreamFerryApplication
    private var themeMode by mutableStateOf(ThemeMode.SYSTEM)

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        themeMode = app.container.appearancePreferences.themeMode
        enableEdgeToEdge()
        setContent {
            StreamFerryTheme(themeMode = themeMode) {
                val lightBars = MaterialTheme.colorScheme.surface.luminance() > 0.5f
                SideEffect {
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = lightBars
                        isAppearanceLightNavigationBars = lightBars
                    }
                }
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text("Logging and performance") },
                            navigationIcon = {
                                IconButton(onClick = { finish() }) {
                                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                                }
                            },
                        )
                    },
                ) { padding ->
                    DiagnosticsControlsScreen(
                        handle = app.tracebox,
                        configuration = app.diagnosticsUiConfiguration,
                        modifier = Modifier.fillMaxSize().padding(padding),
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        themeMode = app.container.appearancePreferences.themeMode
    }
}
