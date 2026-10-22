package com.adsamcik.streamferry.ui.screens

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import com.adsamcik.streamferry.domain.MediaItem
import com.adsamcik.streamferry.domain.MediaSourceIds
import com.adsamcik.streamferry.ui.UiController
import com.adsamcik.streamferry.ui.state.AppUiState
import com.adsamcik.streamferry.ui.theme.StreamFerryTheme
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], qualifiers = "w393dp-h851dp-mdpi")
class GalleryFolderReturnPositionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun folderGridRestoresParentAnchor() = restoresAnchor(AppUiState(
        loggedIn = true, folderStack = listOf(folder("library")), items = entries(),
    ))

    @Test fun searchResultsRestoreAnchorAndQuery() = restoresAnchor(AppUiState(
        loggedIn = true, searchQuery = "Season", searchResults = entries(),
    ))

    @Test fun seasonListRestoresParentAnchor() = restoresAnchor(AppUiState(
        loggedIn = true, folderStack = listOf(folder("series", "Series")),
        items = entries().map { it.copy(type = "Season") },
    ))

    private fun restoresAnchor(parent: AppUiState) {
        val state = mutableStateOf(parent)
        val controller = Proxy.newProxyInstance(UiController::class.java.classLoader, arrayOf(UiController::class.java)) { _, method, _ ->
            when (method.name) {
                "getSources" -> listOf(MediaSourceIds.REMOTE to "Jellyfin")
                "hashCode" -> 0
                "equals" -> false
                else -> null
            }
        } as UiController
        compose.setContent { StreamFerryTheme(dynamicColor = false) { GalleryScreen(state.value, controller) } }
        val layout = compose.onNode(hasScrollToIndexAction())
        layout.performScrollToIndex(40)
        layout.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 31f) }
        val anchor = layout.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        assertTrue(anchor > 0f)
        compose.runOnIdle { state.value = parent.copy(
            folderStack = parent.folderStack + folder("child"), searchQuery = "", searchResults = emptyList(), items = entries(),
        ) }
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(5)
        compose.runOnIdle { state.value = parent }
        val restored = compose.onNode(hasScrollToIndexAction()).fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange].value()
        assertEquals(anchor, restored, 0.01f)
        compose.runOnIdle { assertEquals(parent.searchQuery, state.value.searchQuery) }
    }

    private fun entries() = (0..99).map { folder("folder-$it") }
    private fun folder(id: String, type: String = "Folder") = MediaItem(id, id, null, null, null, null, true, type = type)
}
