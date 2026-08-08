package com.adsamcik.streamferry.data.jellyfin

import com.adsamcik.streamferry.domain.MediaItem

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

class JellyfinBrowseSnapshotTest {

    @Test fun `paged overlap keeps the first stable occurrence of each item`() {
        val initial = season(id = "s1", title = "Season 1", index = 1)
        val duplicateFromNextPage = season(id = "s1", title = "Renamed during scan", index = 1)
        val snapshot = canonicalizeJellyfinChildren(
            listOf(initial, season(id = "s2", title = "Season 2", index = 2), duplicateFromNextPage),
        )

        assertEquals(listOf("s1", "s2"), snapshot.map { it.id })
        assertEquals("Season 1", snapshot.first().title)
    }

    @Test fun `complete season snapshot uses numeric Jellyfin order`() {
        val snapshot = canonicalizeJellyfinChildren(
            listOf(
                season(id = "s20", title = "Season 20", index = 20),
                season(id = "unknown", title = "Bonus material", index = null),
                season(id = "s1", title = "Season 1", index = 1),
                season(id = "specials", title = "Specials", index = 0),
            ),
        )

        assertEquals(listOf("specials", "s1", "s20", "unknown"), snapshot.map { it.id })
    }

    private fun season(id: String, title: String, index: Int?): MediaItem = MediaItem(
        id = id,
        title = title,
        year = null,
        runtimeSeconds = null,
        overview = null,
        resumePositionSeconds = null,
        isFolder = true,
        type = "Season",
        indexNumber = index,
    )
}
