package com.videobridge.data.jellyfin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Covers [pickNextEpisodeId] — choosing the following episode from the ordered ids returned by
 * `/Shows/{id}/Episodes?startItemId=`, robust to inclusive vs. exclusive startItemId semantics.
 */
class NextEpisodeTest {

    @Test fun inclusiveList_currentFirst_picksTheOneAfter() {
        // startItemId inclusive: [current, next]
        assertEquals("ep2", pickNextEpisodeId(listOf("ep1", "ep2"), currentId = "ep1"))
    }

    @Test fun exclusiveList_nextFirst_picksTheFirst() {
        // startItemId exclusive: [next, ...]
        assertEquals("ep2", pickNextEpisodeId(listOf("ep2", "ep3"), currentId = "ep1"))
    }

    @Test fun lastEpisode_onlyCurrentReturned_hasNoNext() {
        assertNull(pickNextEpisodeId(listOf("ep1"), currentId = "ep1"))
    }

    @Test fun emptyResult_hasNoNext() {
        assertNull(pickNextEpisodeId(emptyList(), currentId = "ep1"))
    }

    @Test fun crossesSeasonBoundary_returnsNextRegardlessOfName() {
        // The series-wide episode list crosses seasons; the id after the S1 finale is the S2 premiere.
        assertEquals("s2e1", pickNextEpisodeId(listOf("s1e10", "s2e1"), currentId = "s1e10"))
    }
}
