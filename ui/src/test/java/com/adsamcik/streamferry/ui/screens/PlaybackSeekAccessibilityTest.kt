package com.adsamcik.streamferry.ui.screens

import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import com.adsamcik.streamferry.playback.PlaybackPhase
import com.adsamcik.streamferry.ui.UiController
import com.adsamcik.streamferry.ui.state.AppUiState
import com.adsamcik.streamferry.ui.state.PlaybackUiState
import com.adsamcik.streamferry.ui.theme.StreamFerryTheme
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], qualifiers = "w393dp-h851dp-mdpi")
class PlaybackSeekAccessibilityTest {
    @get:Rule val compose = createComposeRule()

    @Test fun accessibilitySeekingUsesTheSameConnectionPolicyAsTouch() {
        val seeks = mutableListOf<Long>()
        val state = mutableStateOf(AppUiState(playback = playback("CAST", PlaybackPhase.PAUSED)))
        val controller = Proxy.newProxyInstance(UiController::class.java.classLoader, arrayOf(UiController::class.java)) { _, method, args ->
            when (method.name) {
                "seekTo" -> { seeks += args!![0] as Long; null }
                "hashCode" -> 0
                "equals" -> false
                else -> null
            }
        } as UiController
        compose.setContent { StreamFerryTheme(dynamicColor = false) { Surface { PlaybackScreen(state.value, controller) } } }
        for (protocol in listOf("CAST", "DLNA")) {
            for (phase in listOf(PlaybackPhase.PLAYING, PlaybackPhase.PAUSED)) {
                compose.runOnIdle { state.value = state.value.copy(playback = playback(protocol, phase)) }
                compose.onNodeWithContentDescription("Playback position").performScrollTo()
                    .performSemanticsAction(SemanticsActions.SetProgress) { it(0.5f) }
            }
            for (phase in listOf(PlaybackPhase.RECONNECTING, PlaybackPhase.CHANGING_PROTOCOL)) {
                compose.runOnIdle { state.value = state.value.copy(playback = playback(protocol, phase)) }
                compose.onNodeWithContentDescription("Playback position").assertIsNotEnabled()
                    .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.SetProgress))
            }
        }
        compose.runOnIdle { assertEquals(listOf(300L, 300L, 300L, 300L), seeks) }
    }

    private fun playback(protocol: String, phase: PlaybackPhase) = PlaybackUiState(
        targetName = "TV", protocol = protocol, mediaTitle = "Video", phase = phase,
        positionSeconds = 100, durationSeconds = 600,
    )
}
