package com.adsamcik.streamferry.core.segments

/**
 * A skippable segment type from Jellyfin's Media Segments API (10.10+, populated by the Intro Skipper
 * plugin). [label] is the user-facing skip-button text. Pure — no Android, no SDK.
 */
enum class SegmentType(val label: String) {
    INTRO("Skip intro"),
    RECAP("Skip recap"),
    PREVIEW("Skip preview"),
    OUTRO("Skip outro"),
    COMMERCIAL("Skip ad"),
    UNKNOWN("Skip");

    companion object {
        /** Map a Jellyfin `MediaSegmentType` string (case-insensitive) to a [SegmentType]. */
        fun fromApi(raw: String?): SegmentType = when (raw?.trim()?.lowercase()) {
            "intro" -> INTRO
            "recap" -> RECAP
            "preview" -> PREVIEW
            "outro" -> OUTRO
            "commercial" -> COMMERCIAL
            else -> UNKNOWN
        }
    }
}

/** A media segment with absolute [startSeconds], [endSeconds) on the item's timeline. */
data class MediaSegment(val type: SegmentType, val startSeconds: Long, val endSeconds: Long)

/**
 * Pure helper that finds the segment covering a playback position, so [com.adsamcik.streamferry.playback] can
 * offer a "Skip <segment>" action or auto-skip. Framework-free and unit-tested.
 */
object MediaSegmentTracker {

    /**
     * Index of the segment whose `[start, end)` contains [positionSeconds], or null if the position is
     * outside every segment. The first match wins if segments overlap (they shouldn't in practice).
     */
    fun activeIndex(segments: List<MediaSegment>, positionSeconds: Long): Int? =
        segments.indexOfFirst { positionSeconds >= it.startSeconds && positionSeconds < it.endSeconds }
            .takeIf { it >= 0 }

    /** The segment covering [positionSeconds], or null. */
    fun activeSegment(segments: List<MediaSegment>, positionSeconds: Long): MediaSegment? =
        activeIndex(segments, positionSeconds)?.let { segments[it] }
}
