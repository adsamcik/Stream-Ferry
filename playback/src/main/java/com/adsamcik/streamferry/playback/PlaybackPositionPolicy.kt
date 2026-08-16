package com.adsamcik.streamferry.playback

/** Pure timeline decisions shared by every playback source and renderer protocol. */
object PlaybackPositionPolicy {
    /** Keep commands inside the known media timeline; unknown-duration streams are only lower-bounded. */
    fun clamp(requestedSeconds: Long, durationSeconds: Long?): Long {
        val nonNegative = requestedSeconds.coerceAtLeast(0L)
        val duration = durationSeconds?.takeIf { it > 0L } ?: return nonNegative
        return nonNegative.coerceAtMost(duration)
    }

    /** Progressive server transcodes start a new upstream at the requested time; all VOD sources seek in-renderer. */
    fun requiresServerReload(isTranscoding: Boolean, isHls: Boolean): Boolean =
        isTranscoding && !isHls

    /** A server-positioned progressive stream starts at renderer time zero; full timelines start at the media time. */
    fun rendererLoadPosition(positionSeconds: Long, requiresServerReload: Boolean): Long =
        if (requiresServerReload) 0L else positionSeconds.coerceAtLeast(0L)
}
