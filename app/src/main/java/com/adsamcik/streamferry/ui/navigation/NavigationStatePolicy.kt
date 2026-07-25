package com.adsamcik.streamferry.ui.navigation

import com.adsamcik.streamferry.domain.MediaSourceIds
import com.adsamcik.streamferry.ui.state.Route

/** Pure route policy shared by the app shell, saved-state restoration, and JVM tests. */
internal object NavigationStatePolicy {

    enum class TopLevelDestination { LIBRARY, DOWNLOADS, SETTINGS }

    data class Availability(
        val hasActivePlayback: Boolean = false,
        val hasSelectedItem: Boolean = false,
    )

    private val stableRestorableRoutes = setOf(
        Route.WELCOME,
        Route.SERVER_SETUP,
        Route.LOGIN,
        Route.GALLERY,
        Route.DOWNLOADS,
        Route.SETTINGS,
        Route.SERVERS,
        Route.DIAGNOSTICS,
        Route.ABOUT,
    )

    fun topLevelFor(route: Route): TopLevelDestination = when (route) {
        Route.DOWNLOADS -> TopLevelDestination.DOWNLOADS
        Route.SETTINGS, Route.SERVERS, Route.DIAGNOSTICS, Route.ABOUT -> TopLevelDestination.SETTINGS
        else -> TopLevelDestination.LIBRARY
    }

    /** Reopening Downloads keeps its previous safe origin instead of pointing Back at itself. */
    fun captureDownloadsOrigin(
        currentRoute: Route,
        previousOrigin: Route,
        availability: Availability,
    ): Route = if (currentRoute == Route.DOWNLOADS) {
        sanitizeDownloadsOrigin(previousOrigin, availability)
    } else {
        sanitizeDownloadsOrigin(currentRoute, availability)
    }

    fun sanitizeDownloadsOrigin(origin: Route, availability: Availability): Route = when (origin) {
        Route.MEDIA_DETAIL -> if (availability.hasSelectedItem) Route.MEDIA_DETAIL else Route.GALLERY
        Route.PLAYBACK -> if (availability.hasActivePlayback) Route.PLAYBACK else Route.GALLERY
        Route.GALLERY, Route.SETTINGS, Route.SERVERS, Route.DIAGNOSTICS, Route.ABOUT -> origin
        Route.WELCOME, Route.SERVER_SETUP, Route.LOGIN, Route.TARGET_PICKER, Route.DOWNLOADS -> Route.GALLERY
    }

    /** Contextual routes require transient state, so process restoration collapses them to Library. */
    fun restoreRoute(value: String?): Route {
        val parsed = value?.let { encoded -> runCatching { Route.valueOf(encoded) }.getOrNull() }
        return when {
            parsed in stableRestorableRoutes -> parsed!!
            parsed != null -> Route.GALLERY
            else -> Route.WELCOME
        }
    }

    /** A restored Downloads origin has no transient media state and therefore must be stable. */
    fun restoreDownloadsOrigin(value: String?): Route {
        val parsed = value?.let { encoded -> runCatching { Route.valueOf(encoded) }.getOrNull() }
        return when (parsed) {
            Route.GALLERY, Route.SETTINGS, Route.SERVERS, Route.DIAGNOSTICS, Route.ABOUT -> parsed
            else -> Route.GALLERY
        }
    }

    fun persistRoute(route: Route): String = when (route) {
        Route.MEDIA_DETAIL, Route.TARGET_PICKER, Route.PLAYBACK -> Route.GALLERY.name
        else -> route.name
    }

    fun restoreSource(value: String?): String = when (value) {
        MediaSourceIds.LOCAL -> MediaSourceIds.LOCAL
        else -> MediaSourceIds.JELLYFIN
    }
}
