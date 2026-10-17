package com.adsamcik.streamferry.ui.screens

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.text.TextRange
import com.adsamcik.streamferry.domain.MediaItem
import com.adsamcik.streamferry.domain.MediaSourceIds
import com.adsamcik.streamferry.ui.UiController
import com.adsamcik.streamferry.ui.state.AppUiState
import com.adsamcik.streamferry.ui.theme.StreamFerryTheme
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w393dp-h851dp-mdpi")
class GallerySearchEditingTest {
    @get:Rule val compose = createComposeRule()

    @Test fun phoneRootSearchKeepsItsEditorWhenResultsReplaceHome() =
        assertEditingSurvivesContentChanges(compact = true)

    @Test fun phoneFolderSearchKeepsItsEditorWhenQueryIsCleared() =
        assertEditingSurvivesContentChanges(compact = true, inFolder = true)

    @Test
    @Config(sdk = [36], qualifiers = "w900dp-h851dp-mdpi")
    fun wideRootSearchKeepsItsEditorWhenResultsReplaceHome() =
        assertEditingSurvivesContentChanges(compact = false)

    @Test fun restoredSearchStaysOpenWhenTheQueryIsCleared() =
        assertEditingSurvivesContentChanges(compact = true, initialQuery = "matrix")

    private fun assertEditingSurvivesContentChanges(
        compact: Boolean,
        inFolder: Boolean = false,
        initialQuery: String = "",
    ) {
        val folder = MediaItem(
            id = "folder", title = "Movies", year = null, runtimeSeconds = null,
            overview = null, resumePositionSeconds = null, isFolder = true,
        )
        val state = mutableStateOf(
            AppUiState(
                loggedIn = true,
                activeSourceId = if (inFolder) MediaSourceIds.LOCAL else MediaSourceIds.REMOTE,
                libraries = listOf(folder),
                folderStack = if (inFolder) listOf(folder) else emptyList(),
                searchQuery = initialQuery,
            ),
        )
        val controller = Proxy.newProxyInstance(
            UiController::class.java.classLoader, arrayOf(UiController::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getSources" -> listOf(MediaSourceIds.REMOTE to "Jellyfin", MediaSourceIds.LOCAL to "On this device")
                "onSearchQueryChanged" -> {
                    state.value = state.value.copy(searchQuery = args!![0] as String, searching = true)
                    null
                }
                "clearSearch" -> { state.value = state.value.copy(searchQuery = "", searching = false); null }
                "hashCode" -> 0
                "equals" -> false
                "toString" -> "Gallery search test"
                else -> null
            }
        } as UiController
        compose.setContent {
            StreamFerryTheme(dynamicColor = false) { GalleryScreen(state.value, controller, compact) }
        }
        if (compact && initialQuery.isEmpty()) {
            compose.onNodeWithContentDescription("Search library").performClick()
        }
        val editor = compose.onNodeWithTag("library-search-field")
        if (!compact) editor.performClick()
        editor.assertIsFocused()
        // The Material label animates the field's height; its position must remain stable.
        val openedPosition = editor.fetchSemanticsNode().boundsInRoot.topLeft

        if (initialQuery.isEmpty()) {
            editor.performTextInput("m")
            assertEquals("Typing the first character must not move the editor", openedPosition, editor.fetchSemanticsNode().boundsInRoot.topLeft)
            editor.assertIsFocused().performTextInput("atrix")
        }
        editor.assertTextContains("matrix")
        compose.runOnIdle { state.value = state.value.copy(searching = false) }
        editor.assertIsFocused().performTextInputSelection(TextRange(0, 1))
        editor.performTextInput("M")
        editor.assertTextContains("Matrix")
        compose.runOnIdle { assertEquals("Matrix", state.value.searchQuery) }

        editor.performTextClearance()
        editor.assertIsFocused().assertTextContains("")
        assertEquals("Clearing the query must keep the editor in place", openedPosition, editor.fetchSemanticsNode().boundsInRoot.topLeft)
        editor.performTextInput("next")
        editor.assertIsFocused().assertTextContains("next")
        compose.runOnIdle { assertEquals("next", state.value.searchQuery) }

        if (compact) {
            compose.onNodeWithContentDescription("Close search").performClick()
            editor.assertDoesNotExist()
            compose.runOnIdle { assertEquals("", state.value.searchQuery) }
        }
    }
}
