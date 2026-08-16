package com.adsamcik.streamferry.data.dlna

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DlnaResumeSeekPolicyTest {
    @Test fun `resume seek retries are bounded and retain the exact target`() {
        val first = PendingDlnaResumeSeek(positionSeconds = 3_725L)
        val second = DlnaResumeSeekPolicy.afterFailure(first)
        val third = DlnaResumeSeekPolicy.afterFailure(requireNotNull(second))

        assertEquals(PendingDlnaResumeSeek(3_725L, failedAttempts = 1), second)
        assertEquals(PendingDlnaResumeSeek(3_725L, failedAttempts = 2), third)
        assertNull(DlnaResumeSeekPolicy.afterFailure(requireNotNull(third)))
    }
}
