package com.adsamcik.streamferry.ui.state

import com.adsamcik.streamferry.core.resume.SmartResumeRecord
import com.adsamcik.streamferry.core.resume.SmartResumeRecordState
import com.adsamcik.streamferry.core.resume.SmartResumeSourceType
import com.adsamcik.streamferry.domain.UserSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SmartResumeUiStateTest {
    @Test fun completedPlaybackAppearsInHistoryButNotSmartResume() {
        val record = record(state = SmartResumeRecordState.FINISHED, position = 595)

        assertNull(record.toUiState(UserSession("user", "server")))
        val history = record.toPlaybackHistoryUiState(UserSession("user", "server"))!!
        assertEquals(true, history.isFinished)
        assertEquals("Watch again", history.actionLabel)
        assertEquals(1f, history.progressFraction)
    }

    @Test fun shortPlaybackRemainsDiscoverableAndStartsFromBeginning() {
        val history = record(position = 5).toPlaybackHistoryUiState(UserSession("user", "server"))!!

        assertEquals("Play from start", history.actionLabel)
        assertEquals(5, history.positionSeconds)
        assertNull(record(position = 5).toUiState(UserSession("user", "server")))
    }

    @Test fun anotherActiveAccountCannotSeeHistoryRecord() {
        assertNull(record().toPlaybackHistoryUiState(UserSession("someone-else", "server")))
    }

    private fun record(
        state: SmartResumeRecordState = SmartResumeRecordState.IN_PROGRESS,
        position: Long = 120,
    ) = SmartResumeRecord(
            sourceType = SmartResumeSourceType.REMOTE,
        mediaId = "movie",
        displayTitle = "Movie",
        durationSeconds = 600,
        serverId = "server",
        userId = "user",
        confirmedPositionSeconds = position,
        updatedAtMillis = 1_000,
        sessionId = "session",
        generation = 1,
        sequence = 1,
        state = state,
    )
}
