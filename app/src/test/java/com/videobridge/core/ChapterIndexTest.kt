package com.videobridge.core

import com.videobridge.core.chapter.chapterIndexForPosition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ChapterIndexTest {

    private val starts = listOf(0L, 60L, 600L, 1800L) // 0:00, 1:00, 10:00, 30:00

    @Test fun emptyChapters_returnsNull() {
        assertNull(chapterIndexForPosition(emptyList(), 123))
    }

    @Test fun positionBeforeFirstChapter_returnsNull() {
        // First chapter starts at 5s; scrubbing to 2s is inside no chapter.
        assertNull(chapterIndexForPosition(listOf(5L, 50L), 2))
    }

    @Test fun positionAtFirstStart_returnsZero() {
        assertEquals(0, chapterIndexForPosition(starts, 0))
    }

    @Test fun positionWithinAChapter_returnsThatChapter() {
        assertEquals(1, chapterIndexForPosition(starts, 65))   // between 1:00 and 10:00 -> chapter 1
        assertEquals(2, chapterIndexForPosition(starts, 605))  // just after 10:00 -> chapter 2
    }

    @Test fun positionExactlyOnABoundary_belongsToThatChapter() {
        assertEquals(2, chapterIndexForPosition(starts, 600)) // exactly at 10:00 start
        assertEquals(1, chapterIndexForPosition(starts, 599)) // one second before -> previous chapter
    }

    @Test fun positionAtOrAfterLastStart_returnsLastIndex() {
        assertEquals(3, chapterIndexForPosition(starts, 1800))
        assertEquals(3, chapterIndexForPosition(starts, 9999))
    }

    @Test fun singleChapter() {
        assertEquals(0, chapterIndexForPosition(listOf(0L), 0))
        assertEquals(0, chapterIndexForPosition(listOf(0L), 123))
    }
}
