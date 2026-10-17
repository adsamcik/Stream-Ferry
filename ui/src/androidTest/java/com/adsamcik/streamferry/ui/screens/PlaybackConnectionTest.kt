package com.adsamcik.streamferry.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.adsamcik.streamferry.core.stream.Protocol
import com.adsamcik.streamferry.core.stream.TargetCapabilities
import com.adsamcik.streamferry.domain.DiscoveredTarget
import com.adsamcik.streamferry.physical.PhysicalTv
import com.adsamcik.streamferry.playback.PlaybackPhase
import com.adsamcik.streamferry.ui.UiController
import com.adsamcik.streamferry.ui.state.AppUiState
import com.adsamcik.streamferry.ui.state.PlaybackUiState
import com.adsamcik.streamferry.ui.theme.StreamFerryTheme
import java.io.File
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class PlaybackConnectionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun chooseEitherConnectionOnThePlaybackScreenAndDisableDuringHandoff() {
        val cast = DiscoveredTarget("cast-tv", "Living Room TV", Protocol.CAST, TargetCapabilities(Protocol.CAST), null)
        val dlna = DiscoveredTarget("dlna-tv", "Living Room TV", Protocol.DLNA, TargetCapabilities(Protocol.DLNA), null)
        val state = mutableStateOf(AppUiState(
            selectedPhysicalTv = PhysicalTv("living-room", "Living Room TV", castEndpoint = cast, dlnaEndpoint = dlna),
            selectedTarget = cast,
            playback = PlaybackUiState(
                targetName = "Living Room TV", protocol = "CAST", mediaTitle = "The Expanse · S3 E5",
                phase = PlaybackPhase.PAUSED, positionSeconds = 1325, durationSeconds = 3600,
                streamMode = "On-device transcode", outputFormat = "H.264 · 720p · on device",
                volumeSupported = true, volume = 0.7f,
            ),
        ))
        val choices = mutableListOf<Protocol>()
        val controller = Proxy.newProxyInstance(UiController::class.java.classLoader, arrayOf(UiController::class.java)) { _, method, args ->
            when (method.name) {
                "selectPlaybackProtocol" -> {
                    val protocol = args!![0] as Protocol
                    choices += protocol
                    state.value = state.value.copy(
                        selectedTarget = if (protocol == Protocol.CAST) cast else dlna,
                        playback = state.value.playback!!.copy(protocol = protocol.name),
                    )
                    null
                }
                "hashCode" -> 0
                "equals" -> false
                "toString" -> "Playback connection test controller"
                else -> null
            }
        } as UiController
        compose.setContent {
            StreamFerryTheme(dynamicColor = false) {
                Surface(Modifier.systemBarsPadding().padding(12.dp)) { PlaybackScreen(state.value, controller) }
            }
        }
        compose.onNodeWithText("TV connection").assertIsDisplayed()
        compose.onNodeWithContentDescription("TV connection: Google Cast").performClick()
        compose.onAllNodesWithText("Google Cast").onLast().performClick()
        compose.runOnIdle { assertEquals(emptyList<Protocol>(), choices) }
        compose.onNodeWithContentDescription("TV connection: Google Cast").performClick()
        compose.onNodeWithText("DLNA").assertIsDisplayed()
        screenshot("playback-connections-menu.png")
        compose.onNodeWithText("DLNA").performClick()
        compose.onNodeWithContentDescription("TV connection: DLNA").assertIsDisplayed().performClick()
        compose.onNodeWithText("Google Cast").performClick()
        compose.runOnIdle {
            assertEquals(listOf(Protocol.DLNA, Protocol.CAST), choices)
            assertEquals(1325L, state.value.playback!!.positionSeconds)
            state.value = state.value.copy(playback = state.value.playback!!.copy(
                phase = PlaybackPhase.CHANGING_PROTOCOL, reconnecting = true,
            ))
        }
        compose.onNodeWithContentDescription("TV connection: Google Cast").assertIsNotEnabled()
        compose.runOnIdle {
            state.value = state.value.copy(playback = state.value.playback!!.copy(
                phase = PlaybackPhase.PAUSED, reconnecting = false,
            ))
        }
        screenshot("playback-connections.png")
        compose.runOnIdle { state.value = state.value.copy(selectedPhysicalTv = state.value.selectedPhysicalTv!!.copy(dlnaEndpoint = null)) }
        compose.onNodeWithText("TV connection").assertDoesNotExist()
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(instrumentation.targetContext.filesDir, name).outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }
}
