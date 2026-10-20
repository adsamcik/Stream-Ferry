package com.adsamcik.streamferry.ui.screens

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.adsamcik.streamferry.ui.state.SourceRetryStatus
import com.adsamcik.streamferry.ui.state.AppUiState
import com.adsamcik.streamferry.ui.UiController
import com.adsamcik.streamferry.domain.MediaItem
import com.adsamcik.streamferry.domain.MediaSourceIds
import com.adsamcik.streamferry.domain.SourceAvailability
import com.adsamcik.streamferry.ui.theme.StreamFerryTheme
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SourceUnavailableNoticeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun retryShowsProgressPreventsDuplicateTapsAndReportsTheResult() {
        val status = mutableStateOf(SourceRetryStatus.IDLE)
        var attempts = 0
        compose.setContent {
            StreamFerryTheme {
                SourceUnavailableHeaderNotice(
                    onRetry = { attempts++; status.value = SourceRetryStatus.RETRYING },
                    retryStatus = status.value,
                )
            }
        }
        compose.onNodeWithText("Source unavailable").assertIsDisplayed()
        compose.onNodeWithText("Retry").performClick()
        compose.onNodeWithText("Reconnecting…").assertIsDisplayed()
        compose.onNodeWithText("Retry").assertIsNotEnabled().performClick()
        compose.runOnIdle { assertEquals(1, attempts); status.value = SourceRetryStatus.FAILED }
        compose.onNodeWithText("Still unavailable").assertIsDisplayed()
        compose.onNodeWithText("Retry").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(2, attempts) }
    }

    @Test fun cachedResultsRemainVisibleDuringRetryButNotDuringANewQuery() {
        val state = mutableStateOf(AppUiState(
            loggedIn = true,
            sourceAvailability = SourceAvailability.UNAVAILABLE,
            sourceRetryStatus = SourceRetryStatus.RETRYING,
            searchQuery = "film",
            searching = true,
            searchResults = listOf(MediaItem("saved", "Saved result", null, null, null, null, true)),
        ))
        val controller = Proxy.newProxyInstance(UiController::class.java.classLoader, arrayOf(UiController::class.java)) { _, method, _ ->
            when (method.name) {
                "getSources" -> listOf(MediaSourceIds.REMOTE to "Jellyfin")
                "hashCode" -> 0
                "equals" -> false
                "toString" -> "Source retry UI test"
                else -> null
            }
        } as UiController
        compose.setContent { StreamFerryTheme { GalleryScreen(state.value, controller, compact = true) } }
        compose.onNodeWithText("Reconnecting…").assertIsDisplayed()
        compose.onNodeWithText("Saved result").assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(searchQuery = "new", sourceRetryStatus = SourceRetryStatus.IDLE) }
        compose.onNodeWithText("Saved result").assertDoesNotExist()
    }
}
