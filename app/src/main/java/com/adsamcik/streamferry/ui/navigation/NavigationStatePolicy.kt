package com.adsamcik.streamferry.ui.navigation

import com.adsamcik.streamferry.domain.MediaSourceIds
import com.adsamcik.streamferry.ui.state.Route

/** Pure route policy shared by the app shell, saved-state restoration, and JVM tests. */
internal object NavigationStatePolicy {

    enum class TopLevelDestination { LIBRARY, SETTINGS }

    enum class PlaybackBackBehavior { BACKGROUND, STOP }

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
        Route.HISTORY,
        Route.SETTINGS,
        Route.SERVERS,
        Route.DIAGNOSTICS,
        Route.ABOUT,
    )

    fun topLevelFor(route: Route): TopLevelDestination = when (route) {
        Route.DOWNLOADS, Route.HISTORY, Route.SETTINGS, Route.SERVERS, Route.DIAGNOSTICS, Route.ABOUT ->
            TopLevelDestination.SETTINGS
        else -> TopLevelDestination.LIBRARY
    }

    /**
     * Back normally leaves an active TV session running behind the mini-player. Once the renderer is
     * disconnected, however, there is no playback to preserve; leaving Now Playing should also dismiss
     * recovery (or its terminal error) and release the local session.
     */
    fun playbackBackBehavior(isReconnecting: Boolean, isTerminal: Boolean): PlaybackBackBehavior =
        if (isReconnecting || isTerminal) PlaybackBackBehavior.STOP else PlaybackBackBehavior.BACKGROUND

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
        Route.GALLERY, Route.HISTORY, Route.SETTINGS, Route.SERVERS, Route.DIAGNOSTICS, Route.ABOUT -> origin
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
            Route.GALLERY, Route.HISTORY, Route.SETTINGS, Route.SERVERS, Route.DIAGNOSTICS, Route.ABOUT -> parsed
            else -> Route.GALLERY
        }
    }

    fun persistRoute(route: Route): String = when (route) {
        Route.MEDIA_DETAIL, Route.TARGET_PICKER, Route.PLAYBACK -> Route.GALLERY.name
        else -> route.name
    }

    fun restoreSource(value: String?): String = when (value) {
        MediaSourceIds.LOCAL -> MediaSourceIds.LOCAL
        else -> MediaSourceIds.REMOTE
    }
}

/** Immutable identity of the currently browsed source/folder. */
internal data class GalleryBrowseTarget(
    val sourceId: String,
    val folderId: String?,
)

/** A monotonically newer gallery request supersedes every earlier request. */
internal data class GalleryLoadRequest(
    val generation: Long,
    val target: GalleryBrowseTarget,
)

/**
 * Keeps asynchronous gallery responses tied to the source and folder that started them. It is deliberately
 * UI-framework-free so a late Jellyfin response cannot replace a newer browse target or reset its scroll.
 */
internal class GalleryLoadRequestGate {
    private var nextGeneration = 0L
    private var current: GalleryLoadRequest? = null

    fun begin(target: GalleryBrowseTarget): GalleryLoadRequest = GalleryLoadRequest(
        generation = ++nextGeneration,
        target = target,
    ).also { current = it }

    fun invalidate() {
        current = null
        nextGeneration += 1
    }

    fun isCurrent(request: GalleryLoadRequest, currentTarget: GalleryBrowseTarget): Boolean =
        current == request && request.target == currentTarget
}
