package com.adsamcik.streamferry.core

import com.adsamcik.streamferry.core.http.UpstreamRangeVerifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class UpstreamRangeVerifierTest {

    @Test fun boundedRequestsAcceptEndsClampedByTheProvenEntityLength() {
        for (requestedEnd in listOf(7L, 999L, Long.MAX_VALUE)) {
            assertTrue(UpstreamRangeVerifier.matchesRequestHeader(
                206, listOf("bytes 2-7/8"), 6, "bytes=2-$requestedEnd",
            ))
        }
        assertTrue(UpstreamRangeVerifier.matchesRequestHeader(206, listOf("bytes 0-7/8"), 8, "bytes=0-999"))
        assertTrue(UpstreamRangeVerifier.matchesRequestHeader(206, listOf("bytes 7-7/8"), 1, "bytes=7-999"))
        assertTrue(UpstreamRangeVerifier.matchesRequestHeader(206, listOf("bytes 2-5/8"), 4, "bytes=2-5"))
    }

    @Test fun eofClampingStillRejectsUnprovenOrDifferentWindows() {
        assertFalse(UpstreamRangeVerifier.matchesRequestHeader(206, listOf("bytes 2-6/8"), 5, "bytes=2-999"))
        assertFalse(UpstreamRangeVerifier.matchesRequestHeader(206, listOf("bytes 2-7/1000"), 6, "bytes=2-999"))
        assertFalse(UpstreamRangeVerifier.matchesRequestHeader(206, listOf("bytes 3-7/8"), 5, "bytes=2-999"))
        assertFalse(UpstreamRangeVerifier.matchesRequestHeader(206, listOf("bytes 2-7/8"), 5, "bytes=2-999"))
        assertFalse(UpstreamRangeVerifier.matchesRequestHeader(206, listOf("bytes 2-7/8"), 6, "bytes=2-5"))
        assertFalse(UpstreamRangeVerifier.matchesRequestHeader(206, listOf("bytes 2-7/8"), 6, "bytes=8-999"))
        assertFalse(UpstreamRangeVerifier.matchesRequestHeader(200, listOf("bytes 2-7/8"), 6, "bytes=2-999"))
        assertFalse(UpstreamRangeVerifier.matchesRequestHeader(
            206, listOf("bytes 2-7/8", "bytes 2-7/8"), 6, "bytes=2-999",
        ))
    }

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
