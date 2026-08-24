package com.adsamcik.streamferry.ui.state

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaybackControlStatePolicyTest {
    @Test fun `play intent is immediate but waits for a newer matching TV revision`() {
        val pending = PlaybackControlStatePolicy.requestPlayPause(
            current = PlaybackControlUiState(),
            commandId = 7L,
            targetPlaying = false,
            rendererRevision = 10L,
        )

        assertFalse(PlaybackUiState("TV", "CAST", isPlaying = true, controls = pending).displayedIsPlaying)
        assertNotNull(PlaybackControlStatePolicy.reconcile(pending, renderer(playbackRevision = 10L, playing = false), true).playPause)
        assertNotNull(PlaybackControlStatePolicy.reconcile(pending, renderer(playbackRevision = 11L, playing = true), true).playPause)
        assertNull(PlaybackControlStatePolicy.reconcile(pending, renderer(playbackRevision = 11L, playing = false), true).playPause)
    }

    @Test fun `seek and volume confirmation use independent renderer revisions`() {
        val pendingSeek = PlaybackControlStatePolicy.requestSeek(
            PlaybackControlUiState(),
            commandId = 1L,
            targetSeconds = 120L,
            rendererRevision = 4L,
        )
        val both = PlaybackControlStatePolicy.requestVolume(
            pendingSeek,
            commandId = 2L,
            targetLevel = 0.4f,
            rendererRevision = 8L,
        )

        val onlyVolumeConfirmed = PlaybackControlStatePolicy.reconcile(
            both,
            renderer(playbackRevision = 4L, volumeRevision = 9L, position = 120L, volume = 0.4f),
            keepPending = true,
        )

        assertNotNull(onlyVolumeConfirmed.seek)
        assertNull(onlyVolumeConfirmed.volume)
        assertEquals(120L, PlaybackUiState("TV", "DLNA", positionSeconds = 40L, controls = both).displayedPositionSeconds)
        assertEquals(0.4f, PlaybackUiState("TV", "DLNA", volume = 0.8f, controls = both).displayedVolume)
    }

    @Test fun `stale command failure cannot replace a newer intent`() {
        val first = PlaybackControlStatePolicy.requestPlayPause(PlaybackControlUiState(), 1L, false, 2L)
        val second = PlaybackControlStatePolicy.requestPlayPause(first, 2L, true, 2L)

        val afterStaleFailure = PlaybackControlStatePolicy.fail(
            second,
            PlaybackControlKind.PLAY_PAUSE,
            commandId = 1L,
            message = "old failure",
        )

        assertEquals(second, afterStaleFailure)
        assertTrue(afterStaleFailure.displayedPlayingForTest())
    }

    @Test fun `leaving the active renderer session clears pending controls without inventing an issue`() {
        val pending = PlaybackControlStatePolicy.requestVolume(PlaybackControlUiState(), 3L, 0.2f, 1L)
        val cleared = PlaybackControlStatePolicy.reconcile(pending, renderer(), keepPending = false)

        assertFalse(cleared.hasPending)
        assertNull(cleared.issue)
    }

    @Test fun `unreported volume expiry retains the requested level without an issue`() {
        val pending = PlaybackControlStatePolicy.requestVolume(PlaybackControlUiState(), 3L, 0.2f, 8L)
        val expired = PlaybackControlStatePolicy.expireVolumeReconciliation(
            current = pending,
            commandId = 3L,
            rendererVolumeRevision = 8L,
            rendererVolume = 0.7f,
        )

        assertNotNull(expired)
        assertEquals(0.2f, expired.settledLevel)
        assertFalse(expired.rendererReportedAfterRequest)
        assertNull(expired.controls.volume)
        assertNull(expired.controls.issue)
    }

    @Test fun `mismatching newer volume report wins when reconciliation expires`() {
        val pending = PlaybackControlStatePolicy.requestVolume(PlaybackControlUiState(), 3L, 0.2f, 8L)
        val expired = PlaybackControlStatePolicy.expireVolumeReconciliation(
            current = pending,
            commandId = 3L,
            rendererVolumeRevision = 9L,
            rendererVolume = 0.35f,
        )

        assertNotNull(expired)
        assertEquals(0.35f, expired.settledLevel)
        assertTrue(expired.rendererReportedAfterRequest)
        assertNull(expired.controls.volume)
    }

    @Test fun `stale volume expiry cannot retire a newer command`() {
        val latest = PlaybackControlStatePolicy.requestVolume(PlaybackControlUiState(), 4L, 0.6f, 8L)

        assertNull(
            PlaybackControlStatePolicy.expireVolumeReconciliation(
                current = latest,
                commandId = 3L,
                rendererVolumeRevision = 8L,
                rendererVolume = 0.7f,
            ),
        )
    }

    private fun renderer(
        playbackRevision: Long = 0L,
        volumeRevision: Long = 0L,
        playing: Boolean = true,
        position: Long = 0L,
        volume: Float = 1f,
    ) = RendererPlaybackSnapshot(playbackRevision, volumeRevision, playing, position, volume)

    private fun PlaybackControlUiState.displayedPlayingForTest(): Boolean =
        PlaybackUiState("TV", "CAST", isPlaying = false, controls = this).displayedIsPlaying
}
