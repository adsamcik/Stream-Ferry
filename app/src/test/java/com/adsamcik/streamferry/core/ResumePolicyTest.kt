package com.adsamcik.streamferry.core

import com.adsamcik.streamferry.core.resume.ResumePolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ResumePolicyTest {

    @Test fun tooEarlyDoesNotResume() {
        assertNull(ResumePolicy.resumePosition(5, 3600))
        assertFalse(ResumePolicy.shouldSave(5, 3600))
    }

    @Test fun midpointResumes() {
        assertEquals(1800L, ResumePolicy.resumePosition(1800, 3600))
        assertTrue(ResumePolicy.shouldSave(1800, 3600))
    }

    @Test fun nearEndTreatedAsFinished() {
        // Within END_MARGIN of the end.
        assertNull(ResumePolicy.resumePosition(3595, 3600))
        assertFalse(ResumePolicy.shouldSave(3595, 3600))
        // Past the finished fraction.
        assertNull(ResumePolicy.resumePosition(3500, 3600)) // 3500/3600 = 0.972 >= 0.97
    }

    @Test fun unknownDurationResumesWhenPastStart() {
        assertEquals(1800L, ResumePolicy.resumePosition(1800, null))
        assertTrue(ResumePolicy.shouldSave(1800, null))
        assertNull(ResumePolicy.resumePosition(5, null))
    }

    @Test fun zeroOrNegativeDurationNotFinished() {
        assertEquals(1800L, ResumePolicy.resumePosition(1800, 0))
        assertEquals(1800L, ResumePolicy.resumePosition(1800, -1))
    }

    @Test fun progressFractionIsPositionOverDuration() {
        assertEquals(0.5f, ResumePolicy.progressFraction(1800, 3600))
        assertEquals(0.25f, ResumePolicy.progressFraction(900, 3600))
    }

    @Test fun progressFractionClampsAndGuardsNulls() {
        // Clamped to [0, 1] even if the position somehow exceeds the runtime.
        assertEquals(1f, ResumePolicy.progressFraction(4000, 3600))
        // No position or unknown/non-positive runtime -> null (no progress bar).
        assertNull(ResumePolicy.progressFraction(null, 3600))
        assertNull(ResumePolicy.progressFraction(1800, null))
        assertNull(ResumePolicy.progressFraction(1800, 0))
    }

    @Test fun watchedFractionPrefersPlayedPercentage() {
        // A partial PlayedPercentage wins over position/runtime.
        assertEquals(0.4f, ResumePolicy.watchedFraction(playedPercentage = 40.0, positionSeconds = 1800, durationSeconds = 3600))
    }

    @Test fun watchedFractionFallsBackToPositionOverRuntime() {
        assertEquals(0.5f, ResumePolicy.watchedFraction(playedPercentage = null, positionSeconds = 1800, durationSeconds = 3600))
    }

    @Test fun watchedFractionNullWhenFullyOrNotStarted() {
        // 0 or 100 percent are not "in progress" -> fall through; with no position that yields null.
        assertNull(ResumePolicy.watchedFraction(playedPercentage = 0.0, positionSeconds = null, durationSeconds = 3600))
        assertNull(ResumePolicy.watchedFraction(playedPercentage = 100.0, positionSeconds = null, durationSeconds = 3600))
        assertNull(ResumePolicy.watchedFraction(playedPercentage = null, positionSeconds = null, durationSeconds = null))
    }
}
