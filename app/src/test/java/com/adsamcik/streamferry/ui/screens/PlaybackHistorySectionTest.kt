package com.adsamcik.streamferry.ui.screens

import com.adsamcik.streamferry.core.resume.SmartResumeSourceType
import com.adsamcik.streamferry.ui.state.SmartResumeUiState
import kotlin.test.Test
import kotlin.test.assertEquals

class PlaybackHistorySectionTest {
    @Test fun recencyUsesCompactStableBuckets() {
        val now = 10 * 24 * 60 * 60 * 1_000L

        assertEquals("Just now", formatPlaybackHistoryRecency(now - 30_000, now))
        assertEquals("8m ago", formatPlaybackHistoryRecency(now - 8 * 60_000, now))
        assertEquals("3h ago", formatPlaybackHistoryRecency(now - 3 * 60 * 60_000, now))
        assertEquals("2d ago", formatPlaybackHistoryRecency(now - 2 * 24 * 60 * 60_000, now))
    }

    @Test fun positionLabelExplainsWherePlaybackStopped() {
        val state = historyState(position = 754, duration = 2_700)

        assertEquals("Left at 12:34 of 45:00", playbackHistoryPositionLabel(state))
        assertEquals(
            "Finished · 45:00",
            playbackHistoryPositionLabel(state.copy(isFinished = true)),
        )
    }

    private fun historyState(position: Long, duration: Long?) = SmartResumeUiState(
        historyKey = "key",
        mediaId = "movie",
        sourceType = SmartResumeSourceType.JELLYFIN,
        title = "Movie",
        subtitle = null,
        sourceLabel = "Jellyfin",
        positionSeconds = position,
        durationSeconds = duration,
        progressFraction = null,
        actionLabel = "Resume",
        updatedAtMillis = 0,
        isFinished = false,
    )
}
