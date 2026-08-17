package com.adsamcik.streamferry.data.dlna

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DlnaPollSessionGateTest {

    @Test fun invalidatedPollCannotCommitAfterReplacementStarts() {
        val gate = DlnaPollSessionGate()
        val oldPoll = gate.begin()

        gate.invalidate()
        val replacementPoll = gate.begin()

        val emitted = mutableListOf<String>()
        assertFalse(gate.commit(oldPoll) { emitted += "stale" })
        assertTrue(gate.commit(replacementPoll) { emitted += "current" })
        assertEquals(listOf("current"), emitted)
    }

    @Test fun beginningAnotherPollSupersedesThePreviousToken() {
        val gate = DlnaPollSessionGate()
        val first = gate.begin()
        val second = gate.begin()

        assertFalse(gate.isCurrent(first))
        assertTrue(gate.isCurrent(second))
    }
}
