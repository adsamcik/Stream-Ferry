package com.adsamcik.streamferry.ui.screens

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.adsamcik.streamferry.playback.PlaybackPhase
import com.adsamcik.streamferry.domain.MediaTrack
import com.adsamcik.streamferry.ui.UiController
import com.adsamcik.streamferry.ui.state.AppUiState
import com.adsamcik.streamferry.ui.state.PlaybackUiState
import com.adsamcik.streamferry.ui.state.Route
import com.adsamcik.streamferry.ui.theme.StreamFerryTheme
import com.adsamcik.streamferry.ui.theme.ThemeMode
import java.lang.reflect.Proxy
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class PlaybackFailureUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun failureExplainsTheProblemAndOffersWorkingRecoveryActions() {
        val calls = mutableListOf<String>()
        val controller = controller(calls)
        val message = "The media source returned HTTP 503 and couldn't prepare this video. Check the server, then retry."
        setScreen(controller, message, retryAvailable = true)
        compose.onNodeWithText(message).assertIsDisplayed()
        screenshot("playback-failure.png")
        compose.onNodeWithText("Try again").performScrollTo().performClick()
        compose.onNodeWithText("Change TV").performClick()
        compose.onNodeWithText("Diagnostics").performScrollTo().performClick()
        compose.onNodeWithText("Close playback").performClick()
        compose.runOnIdle { assertEquals(listOf("retry", "change", "diagnostics", "refresh", "stop"), calls) }
    }

    @Test fun preflightFailureOffersLibraryInsteadOfRetryingWithoutASession() {
        val calls = mutableListOf<String>()
        setScreen(controller(calls), "Access to the video was denied. Select it again.", retryAvailable = false)
        compose.onNodeWithText("Try again").assertDoesNotExist()
        compose.onNodeWithText("Change TV").assertDoesNotExist()
        compose.onNodeWithText("Back to library").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf("stop"), calls) }
    }

    @Test fun largeTextPlaybackActionsRemainReachableInDarkTheme() {
        val calls = mutableListOf<String>()
        val controller = controller(calls)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.6f)) {
                StreamFerryTheme(themeMode = ThemeMode.DARK, dynamicColor = false) {
                    Surface(Modifier.safeDrawingPadding().padding(12.dp)) {
                        PlaybackScreen(AppUiState(playback = PlaybackUiState(
                            targetName = "Living room TV", protocol = "CAST", mediaTitle = "The Expanse · S3 E5",
                            phase = PlaybackPhase.PAUSED, positionSeconds = 1325, durationSeconds = 3600,
                            audioTracks = listOf(MediaTrack(0, "eng", "English"), MediaTrack(1, "ces", "Czech")),
                            currentAudioIndex = 0,
                        )), controller)
                    }
                }
            }
        }
        compose.onNodeWithText("Audio").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Video output").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Stop playback").performScrollTo().assertIsDisplayed()
        screenshot("playback-actions-large-dark.png")
        compose.onNodeWithText("Stop playback").performClick()
        compose.runOnIdle { assertEquals(listOf("stop"), calls) }
    }

    private fun setScreen(controller: UiController, message: String, retryAvailable: Boolean) {
        compose.setContent {
            StreamFerryTheme(themeMode = ThemeMode.LIGHT, dynamicColor = false) {
                Surface(Modifier.safeDrawingPadding().padding(12.dp)) {
                    PlaybackScreen(AppUiState(playback = PlaybackUiState(
                        targetName = "Living room TV", protocol = "DLNA", mediaTitle = "A video",
                        phase = PlaybackPhase.FAILED, isTerminal = true, errorMessage = message,
                        retryAvailable = retryAvailable,
                    )), controller)
                }
            }
        }
    }

    private fun controller(calls: MutableList<String>) = Proxy.newProxyInstance(
        UiController::class.java.classLoader, arrayOf(UiController::class.java),
    ) { _, method, args ->
        when (method.name) {
            "retryPlayback" -> { calls += "retry"; null }
            "changeTv" -> { calls += "change"; null }
            "stopPlayback" -> { calls += "stop"; null }
            "navigate" -> { if (args!![0] == Route.DIAGNOSTICS) calls += "diagnostics"; null }
            "refreshDiagnostics" -> { calls += "refresh"; null }
            "hashCode" -> 0
            "equals" -> false
            "toString" -> "Playback failure UI test"
            else -> null
        }
    } as UiController

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(instrumentation.targetContext.filesDir, name).outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
