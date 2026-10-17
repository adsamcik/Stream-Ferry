package com.adsamcik.streamferry.playback

import com.adsamcik.streamferry.core.stream.Protocol

data class PlaybackControlAvailability(
    val canPlayPause: Boolean,
    val canSkip: Boolean,
    val canSeekTimeline: Boolean,
    val isTransitioning: Boolean,
)

/** One interaction policy for Compose, notifications, lock-screen controls, and hardware media keys. */
object PlaybackControlPolicy {
    fun evaluate(phase: PlaybackPhase, durationSeconds: Long?): PlaybackControlAvailability {
        val controllable = phase in setOf(
            PlaybackPhase.PLAYING,
            PlaybackPhase.PAUSED,
            PlaybackPhase.BUFFERING,
        )
        val transitioning = phase in setOf(
            PlaybackPhase.CONNECTING,
            PlaybackPhase.PREPARING,
            PlaybackPhase.LOADING,
            PlaybackPhase.WAITING_FOR_PLAYBACK,
            PlaybackPhase.RECONNECTING,
            PlaybackPhase.CHANGING_STREAM,
            PlaybackPhase.CHANGING_PROTOCOL,
        )
        return PlaybackControlAvailability(
            canPlayPause = controllable,
            // Relative skips remain valid without a known duration; the engine lower-bounds them and the
            // renderer/server owns the unknown upper timeline.
            canSkip = controllable,
            canSeekTimeline = controllable && durationSeconds?.let { it > 0L } == true,
            isTransitioning = transitioning,
        )
    }
}

/** Teardown decisions kept pure so a known-dead renderer can never delay local session cleanup. */
internal object PlaybackTeardownPolicy {
    // Ending the Cast session stops the receiver app itself. Waiting for a separate media STOP first
    // can strand an unresponsive receiver for the entire media-command timeout.
    fun shouldSendRendererStop(connectionLost: Boolean, protocol: Protocol?): Boolean =
        !connectionLost && protocol != Protocol.CAST
}
