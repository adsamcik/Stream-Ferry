package com.adsamcik.streamferry.ui.navigation

import android.view.View
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.adsamcik.streamferry.core.volume.NightVolumePolicy
import com.adsamcik.streamferry.domain.MediaItem
import com.adsamcik.streamferry.domain.MediaSourceIds
import com.adsamcik.streamferry.ui.AppRoot
import com.adsamcik.streamferry.ui.UiController
import com.adsamcik.streamferry.ui.state.AppUiState
import com.adsamcik.streamferry.ui.state.Route
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
class GalleryKeyboardInsetsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun phoneResultsFillTheSpaceAboveTheKeyboard() = assertKeyboardSpaceIsUsed()

    @Test
    @Config(sdk = [36], qualifiers = "w900dp-h851dp-mdpi")
    fun wideResultsFillTheSpaceAboveTheKeyboard() = assertKeyboardSpaceIsUsed()

    private fun assertKeyboardSpaceIsUsed() {
        val state = AppUiState(
            route = Route.GALLERY, loggedIn = true, searchQuery = "Movie",
            searchResults = (1..60).map {
                MediaItem(
                    id = "movie-$it", title = "Movie $it", year = null, runtimeSeconds = null,
                    overview = null, resumePositionSeconds = null, isFolder = false,
                )
            },
        )
        val controller = Proxy.newProxyInstance(
            UiController::class.java.classLoader, arrayOf(UiController::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getSources" -> listOf(MediaSourceIds.REMOTE to "Jellyfin")
                "hashCode" -> 0
                "equals" -> false
                "toString" -> "Gallery keyboard test"
                else -> null
            }
        } as UiController
        lateinit var view: View
        var density = 1f
        compose.setContent {
            val localView = LocalView.current
            val localDensity = LocalDensity.current.density
            SideEffect { view = localView; density = localDensity }
            StreamFerryTheme(dynamicColor = false) {
                AppRoot(state, controller, {}, "test", NightVolumePolicy.Off, {})
            }
        }
        val rootBottom = compose.onRoot().fetchSemanticsNode().boundsInRoot.bottom
        val grid = compose.onNode(hasScrollToIndexAction())
        val originalGridBottom = grid.fetchSemanticsNode().boundsInRoot.bottom
        val keyboardHeight = (300 * density).toInt()
        fun setKeyboard(visible: Boolean) {
            compose.runOnIdle {
                ViewCompat.dispatchApplyWindowInsets(
                    view,
                    WindowInsetsCompat.Builder()
                        .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, if (visible) keyboardHeight else 0))
                        .setVisible(WindowInsetsCompat.Type.ime(), visible)
                        .build(),
                )
            }
        }
        setKeyboard(true)
        assertEquals(
            "Only the normal 12dp content margin should remain above the keyboard",
            rootBottom - keyboardHeight - 12 * density,
            grid.fetchSemanticsNode().boundsInRoot.bottom,
            density,
        )
        setKeyboard(false)
        assertEquals(
            "Closing the keyboard must restore the original gallery height",
            originalGridBottom,
            grid.fetchSemanticsNode().boundsInRoot.bottom,
            density,
        )
    }
}
