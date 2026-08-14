package com.adsamcik.streamferry.ui.state

import kotlin.math.abs

/** The control family that owns a pending intent or a single user-facing issue. */
enum class PlaybackControlKind { PLAY_PAUSE, SEEK, VOLUME, OPTIONS }

data class PendingPlayPauseIntent(
    val commandId: Long,
    val targetPlaying: Boolean,
    val rendererRevisionAtRequest: Long,
)

data class PendingSeekIntent(
    val commandId: Long,
    val targetSeconds: Long,
    val rendererRevisionAtRequest: Long,
)

data class PendingVolumeIntent(
    val commandId: Long,
    val targetLevel: Float,
    val rendererRevisionAtRequest: Long,
)

/** One concise issue replaces parallel shell, playback, and per-control error messages. */
data class PlaybackControlIssue(
    val commandId: Long,
    val kind: PlaybackControlKind,
    val message: String,
)

/**
 * Optimistic overlay on top of renderer-confirmed playback state.
 *
 * Pending values drive responsive controls, but are removed only by a newer matching renderer
 * revision, a command rejection, or a bounded confirmation timeout.
 */
data class PlaybackControlUiState(
    val playPause: PendingPlayPauseIntent? = null,
    val seek: PendingSeekIntent? = null,
    val volume: PendingVolumeIntent? = null,
    val issue: PlaybackControlIssue? = null,
) {
    val hasPending: Boolean get() = playPause != null || seek != null || volume != null
}

data class RendererPlaybackSnapshot(
    val playbackRevision: Long,
    val volumeRevision: Long,
    val isPlaying: Boolean,
    val positionSeconds: Long,
    val volume: Float,
)

/** Pure state transitions shared by the ViewModel and regression tests. */
object PlaybackControlStatePolicy {
    private const val SEEK_CONFIRMATION_TOLERANCE_SECONDS = 3L
    private const val VOLUME_CONFIRMATION_TOLERANCE = 0.015f

    fun requestPlayPause(
        current: PlaybackControlUiState,
        commandId: Long,
        targetPlaying: Boolean,
        rendererRevision: Long,
    ): PlaybackControlUiState = current.copy(
        playPause = PendingPlayPauseIntent(commandId, targetPlaying, rendererRevision),
        issue = null,
    )

    fun requestSeek(
        current: PlaybackControlUiState,
        commandId: Long,
        targetSeconds: Long,
        rendererRevision: Long,
    ): PlaybackControlUiState = current.copy(
        seek = PendingSeekIntent(commandId, targetSeconds.coerceAtLeast(0L), rendererRevision),
        issue = null,
    )

    fun requestVolume(
        current: PlaybackControlUiState,
        commandId: Long,
        targetLevel: Float,
        rendererRevision: Long,
    ): PlaybackControlUiState = current.copy(
        volume = PendingVolumeIntent(commandId, targetLevel.coerceIn(0f, 1f), rendererRevision),
        issue = null,
    )

    fun reconcile(
        current: PlaybackControlUiState,
        renderer: RendererPlaybackSnapshot,
        keepPending: Boolean,
    ): PlaybackControlUiState {
        if (!keepPending) return current.copy(playPause = null, seek = null, volume = null)
        return current.copy(
            playPause = current.playPause?.takeUnless { intent ->
                renderer.playbackRevision > intent.rendererRevisionAtRequest &&
                    renderer.isPlaying == intent.targetPlaying
            },
            seek = current.seek?.takeUnless { intent ->
                renderer.playbackRevision > intent.rendererRevisionAtRequest &&
                    abs(renderer.positionSeconds - intent.targetSeconds) <= SEEK_CONFIRMATION_TOLERANCE_SECONDS
            },
            volume = current.volume?.takeUnless { intent ->
                renderer.volumeRevision > intent.rendererRevisionAtRequest &&
                    abs(renderer.volume - intent.targetLevel) <= VOLUME_CONFIRMATION_TOLERANCE
            },
        )
    }

    fun fail(
        current: PlaybackControlUiState,
        kind: PlaybackControlKind,
        commandId: Long,
        message: String,
    ): PlaybackControlUiState {
        if (!ownsPendingCommand(current, kind, commandId) && kind != PlaybackControlKind.OPTIONS) return current
        return clear(current, kind).copy(issue = PlaybackControlIssue(commandId, kind, message))
    }

    fun clearIssue(current: PlaybackControlUiState): PlaybackControlUiState = current.copy(issue = null)

    fun pendingCommandId(current: PlaybackControlUiState, kind: PlaybackControlKind): Long? = when (kind) {
        PlaybackControlKind.PLAY_PAUSE -> current.playPause?.commandId
        PlaybackControlKind.SEEK -> current.seek?.commandId
        PlaybackControlKind.VOLUME -> current.volume?.commandId
        PlaybackControlKind.OPTIONS -> null
    }

    private fun ownsPendingCommand(current: PlaybackControlUiState, kind: PlaybackControlKind, commandId: Long): Boolean =
        pendingCommandId(current, kind) == commandId

    private fun clear(current: PlaybackControlUiState, kind: PlaybackControlKind): PlaybackControlUiState = when (kind) {
        PlaybackControlKind.PLAY_PAUSE -> current.copy(playPause = null)
        PlaybackControlKind.SEEK -> current.copy(seek = null)
        PlaybackControlKind.VOLUME -> current.copy(volume = null)
        PlaybackControlKind.OPTIONS -> current
    }
}

val PlaybackUiState.displayedIsPlaying: Boolean
    get() = controls.playPause?.targetPlaying ?: isPlaying

val PlaybackUiState.displayedPositionSeconds: Long
    get() = controls.seek?.targetSeconds ?: positionSeconds

val PlaybackUiState.displayedVolume: Float
    get() = controls.volume?.targetLevel ?: volume
