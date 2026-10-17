package com.adsamcik.streamferry.ui.screens

import android.view.View
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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
                searchResults = (1..60).map {
                    MediaItem(
                        id = "matrix-$it", title = "Matrix $it", year = 1999, runtimeSeconds = 7200,
                        overview = null, resumePositionSeconds = null, isFolder = false,
                    )
                },
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
        lateinit var contentView: View
        var density = 1f
        composeRule.setContent {
            contentView = LocalView.current
            density = LocalDensity.current.density
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
        composeRule.waitUntil(10_000) {
            ViewCompat.getRootWindowInsets(contentView)?.isVisible(WindowInsetsCompat.Type.ime()) == true
        }
        composeRule.waitForIdle()
        val keyboardHeight = ViewCompat.getRootWindowInsets(contentView)!!
            .getInsets(WindowInsetsCompat.Type.ime()).bottom
        assertTrue("The keyboard must actually be open", keyboardHeight > 0)
        val grid = composeRule.onNode(hasScrollToIndexAction())
        val keyboardGridBottom = grid.fetchSemanticsNode().boundsInRoot.bottom
        val rootBottom = composeRule.onRoot().fetchSemanticsNode().boundsInRoot.bottom
        assertEquals("Videos should extend to the normal margin above the keyboard", rootBottom - keyboardHeight - 12 * density, keyboardGridBottom, 2 * density)
        captureScreen("search-typed.png")
        grid.performScrollToIndex(59)
        composeRule.onNodeWithText("Matrix 60").assertIsDisplayed()
        captureScreen("search-keyboard-end.png")
        editor.performImeAction()
        composeRule.waitUntil(10_000) {
            ViewCompat.getRootWindowInsets(contentView)?.isVisible(WindowInsetsCompat.Type.ime()) == false
        }
        assertTrue("Dismissing the keyboard should restore gallery space", grid.fetchSemanticsNode().boundsInRoot.bottom > keyboardGridBottom)
        composeRule.runOnIdle { assertEquals("matrix", state.searchQuery) }
        captureScreen("search-keyboard-closed.png")
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
