package com.adsamcik.streamferry.core

import com.adsamcik.streamferry.core.dlna.DlnaTerminalStatePolicy
import kotlin.test.Test
import kotlin.test.assertEquals

class DlnaTerminalStatePolicyTest {

    @Test
    fun `early renderer stop is not completion`() {
        assertEquals(
            DlnaTerminalStatePolicy.Outcome.STOPPED,
            DlnaTerminalStatePolicy.classify(
                everPlayed = true,
                transportState = "STOPPED",
                furthestPositionSeconds = 120,
                durationSeconds = 3_600,
            ),
        )
    }

    @Test
    fun `stop near known end is completion`() {
        assertEquals(
            DlnaTerminalStatePolicy.Outcome.COMPLETED,
            DlnaTerminalStatePolicy.classify(
                everPlayed = true,
                transportState = "STOPPED",
                furthestPositionSeconds = 3_575,
                durationSeconds = 3_600,
            ),
        )
    }

    @Test
    fun `unknown duration never guesses completion`() {
        assertEquals(
            DlnaTerminalStatePolicy.Outcome.STOPPED,
            DlnaTerminalStatePolicy.classify(
                everPlayed = true,
                transportState = "NO_MEDIA_PRESENT",
                furthestPositionSeconds = 1_200,
                durationSeconds = null,
            ),
        )
    }

    @Test
    fun `terminal state before this item played is ignored`() {
        assertEquals(
            DlnaTerminalStatePolicy.Outcome.NONE,
            DlnaTerminalStatePolicy.classify(
                everPlayed = false,
                transportState = "STOPPED",
                furthestPositionSeconds = 0,
                durationSeconds = 100,
            ),
        )
    }

    @Test
    fun `paused state is not terminal`() {
        assertEquals(
            DlnaTerminalStatePolicy.Outcome.NONE,
            DlnaTerminalStatePolicy.classify(
                everPlayed = true,
                transportState = "PAUSED_PLAYBACK",
                furthestPositionSeconds = 50,
                durationSeconds = 100,
            ),
        )
    }
}
