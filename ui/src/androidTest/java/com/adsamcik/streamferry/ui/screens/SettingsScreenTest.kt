package com.adsamcik.streamferry.ui.screens

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import com.adsamcik.streamferry.core.volume.NightVolumePolicy
import com.adsamcik.streamferry.ui.theme.StreamFerryTheme
import com.adsamcik.streamferry.ui.theme.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SettingsScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun themeIsDirectlySelectableAndConversionDoesNotRequireAnAdvancedMenu() {
        val fixture = Fixture()
        compose.setContent { fixture.Screen() }
        compose.onNodeWithText("Dark").performClick().assertIsSelected()
        compose.runOnIdle { assertEquals(ThemeMode.DARK, fixture.theme.value) }
        compose.onNodeWithText("System").performClick().assertIsSelected()
        compose.runOnIdle { assertEquals(ThemeMode.SYSTEM, fixture.theme.value) }
        compose.onNodeWithText("Convert local videos on phone").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(false, fixture.convert) }
        compose.onNodeWithText("Maximum resolution").performScrollTo().performClick()
        compose.onNodeWithText("720p").performClick()
        compose.runOnIdle { assertEquals(720, fixture.resolution) }
    }

    @Test fun languagePickerRecognizesSavedAliasesAndSearchesBeforeSelection() {
        val fixture = Fixture()
        compose.setContent { fixture.Screen() }
        compose.onNodeWithText("Audio language").performScrollTo().performClick()
        compose.onNodeWithText("Search languages").performTextInput("German")
        compose.onNodeWithText("German").assertIsSelected()
        compose.onNodeWithText("Search languages").performTextClearance()
        compose.onNodeWithText("Search languages").performTextInput("Cze")
        compose.onNodeWithText("Czech").performClick()
        compose.runOnIdle { assertEquals("ces", fixture.audio) }
        compose.onNodeWithText("Czech").assertIsDisplayed()
    }

    @Test fun compatibilityResetAndDataDeletionRequireSeparateExplicitConfirmation() {
        val fixture = Fixture()
        compose.setContent { fixture.Screen() }
        compose.onNodeWithText("Reset TV compatibility").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(0, fixture.resets) }
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertEquals(0, fixture.resets) }
        compose.onNodeWithText("Reset TV compatibility").performClick()
        compose.onNodeWithText("Reset").performClick()
        compose.runOnIdle { assertEquals(1, fixture.resets) }
        compose.onNodeWithText("Delete all app data").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(0, fixture.deletions) }
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertEquals(0, fixture.deletions) }
        compose.onNodeWithText("Delete all app data").performClick()
        compose.onNodeWithText("Delete").performClick()
        compose.runOnIdle { assertEquals(1, fixture.deletions) }
    }

    @Test fun nightVolumeRetainsTheExistingScheduleAndSaveBehavior() {
        val fixture = Fixture()
        compose.setContent { fixture.Screen() }
        compose.onNodeWithText("Night volume").performScrollTo().performClick()
        compose.onNodeWithText("At a set time").performClick()
        compose.onNodeWithText("Save").performClick()
        compose.runOnIdle {
            assertEquals(NightVolumePolicy.Hard(java.time.LocalTime.of(22, 0), 0.4f), fixture.night.value)
        }
    }

    private class Fixture {
        val theme = mutableStateOf(ThemeMode.LIGHT)
        val night = mutableStateOf<NightVolumePolicy>(NightVolumePolicy.Off)
        var convert = true
        var resolution = 1080
        var audio = "de"
        var resets = 0
        var deletions = 0

        @Composable fun Screen() {
            StreamFerryTheme(themeMode = theme.value, dynamicColor = false) {
                Surface(Modifier.fillMaxSize()) {
                    ExpressiveSettingsScreen(
                        onLogout = {}, onDeleteAll = { deletions++ }, onAbout = {}, onDiagnostics = {},
                        onPlaybackHistory = {}, onDownloads = {}, onServers = {},
                        themeMode = theme.value, onThemeModeChange = { theme.value = it },
                        preferDirectPlay = true, onPreferDirectPlayChange = {},
                        transcodeLocalOnDevice = convert, onTranscodeLocalChange = { convert = it },
                        maxVideoHeight = resolution, onMaxVideoHeightChange = { resolution = it },
                        autoPlayNextEpisode = true, onAutoPlayNextChange = {},
                        autoSkipSegments = true, onAutoSkipSegmentsChange = {},
                        preferredAudioLanguage = audio, onPreferredAudioLanguageChange = { audio = it },
                        preferredSubtitleLanguage = "", onPreferredSubtitleLanguageChange = {},
                        backgroundPlaybackUnrestricted = true, onAllowBackgroundPlayback = {},
                        onResetTvCapabilities = { resets++ },
                        nightVolumePolicy = night.value, onNightVolumePolicyChange = { night.value = it },
                        modifier = Modifier.safeDrawingPadding().padding(horizontal = 16.dp),
                    )
                }
            }
        }
    }
}
