package com.adsamcik.streamferry.core

import com.adsamcik.streamferry.core.http.UpstreamRangeVerifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UpstreamRangeVerifierTest {

    @Test fun parsesCanonicalUnsatisfiedRangeTotal() {
        assertEquals(100L, UpstreamRangeVerifier.unsatisfiedTotal(416, listOf("bytes */100")))
        assertEquals(0L, UpstreamRangeVerifier.unsatisfiedTotal(416, listOf("bytes */0")))
    }

    @Test fun rejectsUnprovenUnsatisfiedRangeResponses() {
        assertNull(UpstreamRangeVerifier.unsatisfiedTotal(206, listOf("bytes */100")))
        assertNull(UpstreamRangeVerifier.unsatisfiedTotal(416, emptyList()))
        assertNull(UpstreamRangeVerifier.unsatisfiedTotal(416, listOf("bytes */100", "bytes */100")))
        assertNull(UpstreamRangeVerifier.unsatisfiedTotal(416, listOf("bytes 0-99/100")))
        assertNull(UpstreamRangeVerifier.unsatisfiedTotal(416, listOf("bytes */-1")))
        assertNull(UpstreamRangeVerifier.unsatisfiedTotal(416, listOf("bytes */9223372036854775808")))
    }
}
