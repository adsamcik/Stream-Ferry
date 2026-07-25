package com.adsamcik.streamferry.ui.navigation

import com.adsamcik.streamferry.domain.MediaSourceIds
import com.adsamcik.streamferry.ui.navigation.NavigationStatePolicy.Availability
import com.adsamcik.streamferry.ui.navigation.NavigationStatePolicy.TopLevelDestination
import com.adsamcik.streamferry.ui.state.Route
import kotlin.test.Test
import kotlin.test.assertEquals

class NavigationStatePolicyTest {

    @Test
    fun `downloads captures stable origins without creating a self loop`() {
        assertEquals(
            Route.GALLERY,
            NavigationStatePolicy.captureDownloadsOrigin(Route.GALLERY, Route.SETTINGS, Availability()),
        )
        assertEquals(
            Route.SETTINGS,
            NavigationStatePolicy.captureDownloadsOrigin(Route.SETTINGS, Route.GALLERY, Availability()),
        )
        assertEquals(
            Route.SETTINGS,
            NavigationStatePolicy.captureDownloadsOrigin(Route.DOWNLOADS, Route.SETTINGS, Availability()),
        )
    }

    @Test
    fun `invalid setup and target origins recover to library`() {
        assertEquals(Route.GALLERY, NavigationStatePolicy.sanitizeDownloadsOrigin(Route.DOWNLOADS, Availability()))
        assertEquals(Route.GALLERY, NavigationStatePolicy.sanitizeDownloadsOrigin(Route.LOGIN, Availability()))
        assertEquals(Route.GALLERY, NavigationStatePolicy.sanitizeDownloadsOrigin(Route.TARGET_PICKER, Availability()))
    }

    @Test
    fun `contextual origins require their backing state`() {
        assertEquals(
            Route.PLAYBACK,
            NavigationStatePolicy.sanitizeDownloadsOrigin(Route.PLAYBACK, Availability(hasActivePlayback = true)),
        )
        assertEquals(
            Route.GALLERY,
            NavigationStatePolicy.sanitizeDownloadsOrigin(Route.PLAYBACK, Availability()),
        )
        assertEquals(
            Route.MEDIA_DETAIL,
            NavigationStatePolicy.sanitizeDownloadsOrigin(Route.MEDIA_DETAIL, Availability(hasSelectedItem = true)),
        )
        assertEquals(
            Route.GALLERY,
            NavigationStatePolicy.sanitizeDownloadsOrigin(Route.MEDIA_DETAIL, Availability()),
        )
    }

    @Test
    fun `process recreation restores stable routes and collapses contextual routes`() {
        assertEquals(Route.DOWNLOADS, NavigationStatePolicy.restoreRoute(Route.DOWNLOADS.name))
        assertEquals(Route.GALLERY, NavigationStatePolicy.restoreDownloadsOrigin(Route.MEDIA_DETAIL.name))
        assertEquals(Route.GALLERY, NavigationStatePolicy.restoreRoute(Route.PLAYBACK.name))
        assertEquals(Route.WELCOME, NavigationStatePolicy.restoreRoute("not-a-route"))
        assertEquals(Route.WELCOME, NavigationStatePolicy.restoreRoute(null))
    }

    @Test
    fun `persisted contextual routes recover to library`() {
        assertEquals(Route.GALLERY.name, NavigationStatePolicy.persistRoute(Route.MEDIA_DETAIL))
        assertEquals(Route.GALLERY.name, NavigationStatePolicy.persistRoute(Route.TARGET_PICKER))
        assertEquals(Route.GALLERY.name, NavigationStatePolicy.persistRoute(Route.PLAYBACK))
    }

    @Test
    fun `restored source rejects unknown values`() {
        assertEquals(MediaSourceIds.LOCAL, NavigationStatePolicy.restoreSource(MediaSourceIds.LOCAL))
        assertEquals(MediaSourceIds.JELLYFIN, NavigationStatePolicy.restoreSource("stale-source"))
    }

    @Test
    fun `top level grouping follows the durable information architecture`() {
        assertEquals(TopLevelDestination.LIBRARY, NavigationStatePolicy.topLevelFor(Route.MEDIA_DETAIL))
        assertEquals(TopLevelDestination.DOWNLOADS, NavigationStatePolicy.topLevelFor(Route.DOWNLOADS))
        assertEquals(TopLevelDestination.SETTINGS, NavigationStatePolicy.topLevelFor(Route.DIAGNOSTICS))
    }
}
