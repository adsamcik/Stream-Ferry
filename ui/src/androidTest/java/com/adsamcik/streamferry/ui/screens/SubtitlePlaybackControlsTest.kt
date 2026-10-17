package com.adsamcik.streamferry.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.adsamcik.streamferry.domain.MediaTrack
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

class SubtitlePlaybackControlsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun audioAndCaptionsExposeSourceTracksOnBothOutputs() {
        val state = mutableStateOf(AppUiState(playback = PlaybackUiState(
            targetName = "Living Room TV", protocol = "CAST", mediaTitle = "The Expanse · S3 E5",
            phase = PlaybackPhase.PAUSED, positionSeconds = 1325, durationSeconds = 3600,
            streamMode = "On-device transcode", outputFormat = "H.264 · 720p · on device",
        )))
        val choices = mutableListOf<Int?>()
        val audioChoices = mutableListOf<Int?>()
        val controller = Proxy.newProxyInstance(UiController::class.java.classLoader, arrayOf(UiController::class.java)) { _, method, args ->
            when (method.name) {
                "selectAudioTrack" -> {
                    val index = args!![0] as Int?
                    audioChoices += index
                    state.value = state.value.copy(playback = state.value.playback!!.copy(currentAudioIndex = index))
                    null
                }
                "selectSubtitleTrack" -> {
                    val index = args!![0] as Int?
                    choices += index
                    state.value = state.value.copy(playback = state.value.playback!!.copy(currentSubtitleIndex = index))
                    null
                }
                "hashCode" -> 0
                "equals" -> false
                "toString" -> "Subtitle controls test"
                else -> null
            }
        } as UiController
        compose.setContent {
            StreamFerryTheme(dynamicColor = false) {
                Surface(Modifier.systemBarsPadding().padding(12.dp)) { PlaybackScreen(state.value, controller) }
            }
        }
        // The file entry must remain reachable even when this video has no embedded tracks.
        compose.onNodeWithContentDescription("Captions: Off").assertIsDisplayed().performClick()
        compose.onNodeWithText("Choose subtitle file…").assertIsDisplayed()
        compose.onNodeWithText("Off").assertIsSelected()
        compose.onNodeWithText("Off").performClick()
        compose.runOnIdle {
            state.value = state.value.copy(playback = state.value.playback!!.copy(
                subtitleTracks = listOf(MediaTrack(4, "eng", "English"), MediaTrack(7, "ces", "Czech")),
                audioTracks = listOf(MediaTrack(0, "eng", "English"), MediaTrack(1, "ces", "Czech")),
                currentAudioIndex = 0,
            ))
        }
        for (protocol in listOf("CAST", "DLNA")) {
            compose.runOnIdle { state.value = state.value.copy(playback = state.value.playback!!.copy(protocol = protocol)) }
            compose.onNodeWithContentDescription("Audio: English").assertIsDisplayed().performClick()
            compose.onNodeWithText("English").assertIsSelected()
            compose.onNodeWithText("Czech").performClick()
            compose.onNodeWithContentDescription("Audio: Czech").assertIsDisplayed().performClick()
            compose.onNodeWithText("Czech").assertIsSelected()
            if (protocol == "DLNA") screenshot("playback-audio.png")
            compose.onNodeWithText("English").performClick()
            compose.onNodeWithContentDescription("Captions: Off").performClick()
            compose.onNodeWithText("Czech").performClick()
            compose.onNodeWithContentDescription("Captions: Czech").assertIsDisplayed().performClick()
            compose.onNodeWithText("Czech").assertIsSelected()
            compose.onNodeWithText("Choose subtitle file…").assertIsDisplayed()
            if (protocol == "DLNA") screenshot()
            compose.onNodeWithText("Off").performClick()
        }
        compose.runOnIdle {
            assertEquals(listOf(null, 7, null, 7, null), choices)
            assertEquals(listOf(1, 0, 1, 0), audioChoices)
            assertEquals(1325L, state.value.playback!!.positionSeconds)
        }
    }

    private fun screenshot(name: String = "playback-subtitles.png") {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(instrumentation.targetContext.filesDir, name).outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }
}
