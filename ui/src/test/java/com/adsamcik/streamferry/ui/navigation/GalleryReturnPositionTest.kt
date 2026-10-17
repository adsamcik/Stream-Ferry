package com.adsamcik.streamferry.ui.navigation

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import com.adsamcik.streamferry.core.volume.NightVolumePolicy
import com.adsamcik.streamferry.domain.MediaItem
import com.adsamcik.streamferry.domain.MediaSourceIds
import com.adsamcik.streamferry.playback.PlaybackPhase
import com.adsamcik.streamferry.ui.AppRoot
import com.adsamcik.streamferry.ui.UiController
import com.adsamcik.streamferry.ui.state.AppUiState
import com.adsamcik.streamferry.ui.state.PlaybackUiState
import com.adsamcik.streamferry.ui.state.Route
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
@Config(sdk = [36], qualifiers = "w393dp-h851dp-mdpi")
class GalleryReturnPositionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun libraryGridReturnsToItsScrollAnchor() = assertReturnsToPosition(
        AppUiState(route = Route.GALLERY, loggedIn = true, libraries = movies()),
    )

    @Test fun localFolderGridReturnsToItsScrollAnchor() = assertReturnsToPosition(
        AppUiState(
            route = Route.GALLERY, activeSourceId = MediaSourceIds.LOCAL,
            folderStack = listOf(media("folder", "Downloaded videos", folder = true)), items = movies(),
        ),
    )

    @Test fun searchResultsReturnToTheirScrollAnchorAndQuery() = assertReturnsToPosition(
        AppUiState(route = Route.GALLERY, loggedIn = true, searchQuery = "Movie", searchResults = movies()),
    )

    @Test fun seasonListReturnsToItsScrollAnchor() = assertReturnsToPosition(
        AppUiState(
            route = Route.GALLERY, loggedIn = true,
            folderStack = listOf(media("series", "A long series", folder = true, type = "Series")),
            items = (1..100).map { media("season-$it", "Season $it", folder = true, type = "Season") },
        ),
    )

    private fun assertReturnsToPosition(browse: AppUiState) {
        val state = mutableStateOf(browse)
        val controller = Proxy.newProxyInstance(UiController::class.java.classLoader, arrayOf(UiController::class.java)) { _, method, args ->
            when (method.name) {
                "getSources" -> listOf(MediaSourceIds.REMOTE to "Jellyfin", MediaSourceIds.LOCAL to "On this device")
                "navigate" -> { state.value = state.value.copy(route = args!![0] as Route); null }
                "hashCode" -> 0
                "equals" -> false
                "toString" -> "Gallery navigation test"
                else -> null
            }
        } as UiController
        compose.setContent {
            StreamFerryTheme(dynamicColor = false) {
                AppRoot(state.value, controller, {}, "test", NightVolumePolicy.Off, {})
            }
        }
        val gallery = compose.onNode(hasScrollToIndexAction())
        gallery.performScrollToIndex(40)
        gallery.performSemanticsAction(SemanticsActions.ScrollBy) { scroll -> scroll(0f, 37f) }
        val before = gallery.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        assertTrue("The gallery must be scrolled before leaving", before > 0f)

        compose.runOnIdle {
            state.value = state.value.copy(
                route = Route.PLAYBACK,
                playback = PlaybackUiState(
                    targetName = "Living Room TV", protocol = "CAST", mediaTitle = "Movie 040",
                    phase = PlaybackPhase.PAUSED, positionSeconds = 1325, durationSeconds = 3600,
                ),
            )
        }
        // Wait for the route transition to dispose the gallery; keeping both screens alive would
        // conceal the bug even without a saved-state owner above the transition.
        compose.onNode(hasScrollToIndexAction()).assertDoesNotExist()
        compose.onNodeWithContentDescription("Back").performClick()

        val after = compose.onNode(hasScrollToIndexAction()).fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange].value()
        assertEquals("Returning from playback must preserve the item and pixel offset", before, after, 0.00001f)
        compose.runOnIdle {
            assertEquals(Route.GALLERY, state.value.route)
            assertEquals(browse.activeSourceId, state.value.activeSourceId)
            assertEquals(browse.folderStack, state.value.folderStack)
            assertEquals(browse.searchQuery, state.value.searchQuery)
            assertEquals(1325L, state.value.playback!!.positionSeconds)
        }
    }

    private fun movies() = (0..99).map { media("movie-$it", "Movie %03d".format(it)) }

    private fun media(id: String, title: String, folder: Boolean = false, type: String? = null) = MediaItem(
        id = id, title = title, year = null, runtimeSeconds = null, overview = null,
        resumePositionSeconds = null, isFolder = folder, type = type,
    )
}
