package com.adsamcik.streamferry.domain

/**
 * Small, shared transformations for a source's per-user watch state. Keeping these independent of
 * the UI makes the live gallery, offline cache, and downloaded-library snapshot agree immediately
 * after a user action, before the authoritative refresh returns.
 */
fun MediaItem.withWatchState(played: Boolean): MediaItem =
    if (played) {
        copy(
            played = true,
            playedPercentage = 100.0,
            resumePositionSeconds = null,
            // Folder counts are reconciled from the source after the mutation. A known all-played folder can
            // be reflected immediately; an unplayed count cannot be inferred locally.
            unplayedItemCount = 0,
        )
    } else {
        // A "mark unplayed" action changes only the watched flag; it is deliberately distinct
        // from ResetProgress. Preserve a legitimate server/local resume point while avoiding a contradictory
        // full progress bar until the authoritative refresh returns.
        copy(
            played = false,
            playedPercentage = playedPercentage?.takeIf { it < 100.0 } ?: 0.0,
        )
    }

/** Reset an item to an unwatched, start-from-the-beginning state. */
fun MediaItem.withProgressReset(): MediaItem = copy(
    played = false,
    playedPercentage = 0.0,
    resumePositionSeconds = null,
)

/** A reset is useful for a partially watched or completed item; untouched episodes stay uncluttered. */
fun MediaItem.hasProgressToReset(): Boolean =
    played || (resumePositionSeconds ?: 0L) > 0L || (playedPercentage ?: 0.0) > 0.0

fun MediaItem.isEpisode(): Boolean =
    type.equals("Episode", ignoreCase = true)
