package com.adsamcik.streamferry.playback

import com.adsamcik.streamferry.domain.MediaItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PlaybackQueueTest {

    @Test fun preservesFifoOrderAndAllowsIntentionalRepeats() {
        val first = media("first")
        val second = media("second")

        val queue = PlaybackQueue()
            .enqueue(1, first)
            .enqueue(2, second)
            .enqueue(3, first)

        assertEquals(listOf(1L, 2L, 3L), queue.entries.map { it.entryId })
        assertEquals(listOf("first", "second", "first"), queue.entries.map { it.item.id })
        assertEquals(1L, queue.next?.entryId)
        assertTrue(queue.isNotEmpty)
    }

    @Test fun removalTargetsOnlyTheRequestedEntry() {
        val first = media("same")
        val queue = PlaybackQueue().enqueue(1, first).enqueue(2, first)

        val updated = queue.remove(1)

        assertEquals(listOf(2L), updated.entries.map { it.entryId })
        assertEquals("same", updated.next?.item?.id)
        assertSame(updated, updated.remove(999))
    }

    @Test fun rejectsDuplicateEntryIdsBeforeTheyCanAmbiguouslyRemoveAnItem() {
        val queue = PlaybackQueue().enqueue(1, media("first"))

        assertFailsWith<IllegalArgumentException> {
            queue.enqueue(1, media("second"))
        }
    }

    private fun media(id: String) = MediaItem(
        id = id,
        title = id,
        year = null,
        runtimeSeconds = null,
        overview = null,
        resumePositionSeconds = null,
        isFolder = false,
    )
}
