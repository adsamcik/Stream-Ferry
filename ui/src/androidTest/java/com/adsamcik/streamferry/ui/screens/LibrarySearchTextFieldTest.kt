package com.adsamcik.streamferry.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import com.adsamcik.streamferry.core.volume.NightVolumePolicy
import com.adsamcik.streamferry.domain.MediaItem
import com.adsamcik.streamferry.domain.MediaSourceIds
import com.adsamcik.streamferry.ui.AppRoot
import com.adsamcik.streamferry.ui.UiController
import com.adsamcik.streamferry.ui.state.AppUiState
import com.adsamcik.streamferry.ui.state.Route
import com.adsamcik.streamferry.ui.theme.StreamFerryTheme
import java.io.File
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class LibrarySearchTextFieldTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun phoneGallerySearchKeepsTypingWhenHomeBecomesResults() {
        var state by mutableStateOf(
            AppUiState(
                route = Route.GALLERY, loggedIn = true,
                libraries = listOf(
                    MediaItem(
                        id = "movies", title = "Movies", year = null, runtimeSeconds = null,
                        overview = null, resumePositionSeconds = null, isFolder = true,
                    ),
                ),
            ),
        )
        val controller = Proxy.newProxyInstance(
            UiController::class.java.classLoader, arrayOf(UiController::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getSources" -> listOf(MediaSourceIds.REMOTE to "Jellyfin", MediaSourceIds.LOCAL to "On this device")
                "onSearchQueryChanged" -> { state = state.copy(searchQuery = args!![0] as String); null }
                "hashCode" -> 0
                "equals" -> false
                "toString" -> "Gallery search device test"
                else -> null
            }
        } as UiController
        composeRule.setContent {
            StreamFerryTheme(dynamicColor = false) {
                AppRoot(state, controller, {}, "test", NightVolumePolicy.Off, {})
            }
        }
        composeRule.onNodeWithContentDescription("Search library").performClick()
        val editor = composeRule.onNodeWithTag("library-search-field").assertIsFocused()
        val openedPosition = editor.fetchSemanticsNode().boundsInRoot.topLeft
        captureScreen("search-open.png")
        editor.performTextInput("m")
        editor.assertIsFocused()
        assertEquals(openedPosition, editor.fetchSemanticsNode().boundsInRoot.topLeft)
        editor.performTextInput("atrix")
        composeRule.runOnIdle { assertEquals("matrix", state.searchQuery) }
        captureScreen("search-typed.png")
    }

    private fun captureScreen(name: String) {
        composeRule.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(instrumentation.targetContext.filesDir, name).outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }

    @Test
    fun compactSearchFocusesAcceptsImeSearchAndCloses() {
        var value by mutableStateOf("")
        var submitted = false
        var closed = false
        composeRule.setContent {
            StreamFerryTheme(dynamicColor = false) {
                LibrarySearchTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = "Search library",
                    compact = true,
                    onClear = { value = "" },
                    onClose = { closed = true },
                    onImeSearch = { submitted = true },
                )
            }
        }

        val searchField = composeRule.onNodeWithTag("library-search-field").assertIsFocused()
        searchField.performTextInput("matrix")
        searchField.performImeAction()
        composeRule.runOnIdle {
            assertEquals("matrix", value)
            assertTrue(submitted)
        }

        composeRule.onNodeWithContentDescription("Close search").performClick()
        composeRule.runOnIdle { assertTrue(closed) }
    }
}
