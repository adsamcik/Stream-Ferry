package com.adsamcik.streamferry.core

import com.adsamcik.streamferry.core.http.ByteRange
import com.adsamcik.streamferry.core.http.HttpRange
import com.adsamcik.streamferry.core.http.HttpResponsePlan
import com.adsamcik.streamferry.core.http.RangeParseResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertIs

class HttpRangeTest {

    @Test fun noHeader_isNone() {
        assertIs<RangeParseResult.None>(HttpRange.parse(null, 1000))
        assertIs<RangeParseResult.None>(HttpRange.parse("", 1000))
    }

    @Test fun openEndedRange() {
        val r = HttpRange.parse("bytes=500-", 1000)
        assertEquals(RangeParseResult.Satisfiable(ByteRange(500, 999)), r)
    }

    @Test fun closedRange() {
        val r = HttpRange.parse("bytes=0-499", 1000)
        assertEquals(RangeParseResult.Satisfiable(ByteRange(0, 499)), r)
    }

    @Test fun rangeClampedToLength() {
        val r = HttpRange.parse("bytes=0-100000", 1000)
        assertEquals(RangeParseResult.Satisfiable(ByteRange(0, 999)), r)
    }

    @Test fun suffixRange() {
        val r = HttpRange.parse("bytes=-200", 1000)
        assertEquals(RangeParseResult.Satisfiable(ByteRange(800, 999)), r)
    }

    @Test fun suffixLargerThanLength_clampsToStart() {
        val r = HttpRange.parse("bytes=-5000", 1000)
        assertEquals(RangeParseResult.Satisfiable(ByteRange(0, 999)), r)
    }

    @Test fun startBeyondLength_unsatisfiable() {
        assertEquals(RangeParseResult.Unsatisfiable(1000), HttpRange.parse("bytes=1000-", 1000))
        assertEquals(RangeParseResult.Unsatisfiable(1000), HttpRange.parse("bytes=2000-3000", 1000))
    }

    @Test fun malformed() {
        assertIs<RangeParseResult.Malformed>(HttpRange.parse("bytes=abc-def", 1000))
        assertIs<RangeParseResult.Malformed>(HttpRange.parse("bytes=-", 1000))
        assertIs<RangeParseResult.Malformed>(HttpRange.parse("items=0-1", 1000))
        assertIs<RangeParseResult.Malformed>(HttpRange.parse("bytes=500-100", 1000)) // end < start
    }

    @Test fun unknownLength_openRange() {
        val r = HttpRange.parse("bytes=100-", -1)
        assertEquals(RangeParseResult.Satisfiable(ByteRange(100, Long.MAX_VALUE)), r)
    }

    @Test fun contentRangeHeader() {
        assertEquals("bytes 0-499/1000", HttpRange.contentRange(ByteRange(0, 499), 1000))
        assertEquals("bytes 0-499/*", HttpRange.contentRange(ByteRange(0, 499), -1))
        assertEquals("bytes */1000", HttpRange.unsatisfiedContentRange(1000))
    }

    @Test fun responsePlan_full200() {
        val plan = HttpResponsePlan.plan(RangeParseResult.None, 1000, "video/mp4")
        assertEquals(HttpResponsePlan.Status.OK, plan.status)
        assertEquals("1000", plan.headers["Content-Length"])
        assertEquals("bytes", plan.headers["Accept-Ranges"])
    }

    @Test fun responsePlan_partial206() {
        val plan = HttpResponsePlan.plan(RangeParseResult.Satisfiable(ByteRange(0, 499)), 1000, "video/mp4")
        assertEquals(HttpResponsePlan.Status.PARTIAL_CONTENT, plan.status)
        assertEquals("bytes 0-499/1000", plan.headers["Content-Range"])
        assertEquals("500", plan.headers["Content-Length"])
    }

    @Test fun responsePlan_416() {
        val plan = HttpResponsePlan.plan(RangeParseResult.Unsatisfiable(1000), 1000, "video/mp4")
        assertEquals(HttpResponsePlan.Status.RANGE_NOT_SATISFIABLE, plan.status)
        assertEquals("bytes */1000", plan.headers["Content-Range"])
        assertTrue(plan.range == null)
    }

    // ----- Direct-play seekability (the crux of the "TV plays from the start" seek/resume fix) -----

    @Test fun responsePlan_openEndedRange_knownTotal_isSeekable() {
        // A renderer seeks by issuing an open-ended `bytes=N-`. With the entity length known (the proxy
        // now carries the direct-play MediaSource size), this resolves to a proper 206 with a concrete
        // Content-Length + Content-Range, so the renderer lands at the seek position instead of restarting.
        val parsed = HttpRange.parse("bytes=500-", 1000)
        val plan = HttpResponsePlan.plan(parsed, 1000, "video/mp4")
        assertEquals(HttpResponsePlan.Status.PARTIAL_CONTENT, plan.status)
        assertEquals("bytes 500-999/1000", plan.headers["Content-Range"])
        assertEquals("500", plan.headers["Content-Length"])
    }

    @Test fun responsePlan_openEndedRange_unknownTotal_notSeekable() {
        // The pre-fix behaviour, retained for transcodes: with an unknown length the same seek request has
        // no Content-Length and a wildcard total, so the renderer treats the stream as non-seekable (why a
        // live transcode must be seeked server-side, and why direct play must propagate its length).
        val parsed = HttpRange.parse("bytes=500-", -1)
        val plan = HttpResponsePlan.plan(parsed, -1, "video/mp4")
        assertEquals(HttpResponsePlan.Status.PARTIAL_CONTENT, plan.status)
        assertTrue(plan.headers["Content-Length"] == null)
        assertTrue(plan.headers["Content-Range"]!!.endsWith("/*"))
    }
}
