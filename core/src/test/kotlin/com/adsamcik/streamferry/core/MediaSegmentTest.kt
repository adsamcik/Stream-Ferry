package com.adsamcik.streamferry.core

import com.adsamcik.streamferry.core.segments.MediaSegment
import com.adsamcik.streamferry.core.segments.MediaSegmentTracker
import com.adsamcik.streamferry.core.segments.SegmentType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Covers [MediaSegmentTracker] + [SegmentType.fromApi] — the pure intro/outro skip logic. */
class MediaSegmentTest {

    private val segments = listOf(
        MediaSegment(SegmentType.RECAP, 0, 20),
        MediaSegment(SegmentType.INTRO, 45, 95),
        MediaSegment(SegmentType.OUTRO, 1300, 1360),
    )

    @Test fun activeIndexFindsTheContainingSegment() {
        assertEquals(0, MediaSegmentTracker.activeIndex(segments, 5))   // in the recap
        assertEquals(1, MediaSegmentTracker.activeIndex(segments, 45))  // exactly at intro start (inclusive)
        assertEquals(1, MediaSegmentTracker.activeIndex(segments, 94))  // within the intro
        assertEquals(2, MediaSegmentTracker.activeIndex(segments, 1359))
    }

    @Test fun endIsExclusiveAndGapsHaveNoSegment() {
        assertNull(MediaSegmentTracker.activeIndex(segments, 95))   // intro end is exclusive
        assertNull(MediaSegmentTracker.activeIndex(segments, 30))   // between recap and intro
        assertNull(MediaSegmentTracker.activeIndex(segments, 5000)) // past the end
    }

    @Test fun activeSegmentReturnsTheSegment() {
        assertEquals(SegmentType.INTRO, MediaSegmentTracker.activeSegment(segments, 60)?.type)
        assertEquals(95L, MediaSegmentTracker.activeSegment(segments, 60)?.endSeconds)
        assertNull(MediaSegmentTracker.activeSegment(segments, 30))
    }

    @Test fun emptySegmentsNeverMatch() {
        assertNull(MediaSegmentTracker.activeIndex(emptyList(), 10))
    }

    @Test fun segmentTypeMappingIsCaseInsensitiveWithUnknownFallback() {
        assertEquals(SegmentType.INTRO, SegmentType.fromApi("Intro"))
        assertEquals(SegmentType.OUTRO, SegmentType.fromApi("OUTRO"))
        assertEquals(SegmentType.RECAP, SegmentType.fromApi("recap"))
        assertEquals(SegmentType.UNKNOWN, SegmentType.fromApi("SponsorBlock"))
        assertEquals(SegmentType.UNKNOWN, SegmentType.fromApi(null))
    }
}
