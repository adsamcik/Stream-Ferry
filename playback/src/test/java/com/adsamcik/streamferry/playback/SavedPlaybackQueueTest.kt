package com.adsamcik.streamferry.playback

import com.adsamcik.streamferry.domain.MediaItem
import com.adsamcik.streamferry.domain.UserSession
import kotlin.test.*

class SavedPlaybackQueueTest {
    private val owner = UserSession("user", "server")
    private val media = MediaItem("video", "Video", null, 3600, null, null, false)
    private val first = PlaylistEntry(1, media, owner)
    private val second = PlaylistEntry(2, media, owner)

    @Test fun `saved queue resumes unfinished occurrence and keeps repeats`() {
        val initial = SavedQueueLibrary(QueueContents(PlaybackQueue(listOf(first, second)))).save("q", "Evenings")
        val playing = initial.updateWorking(initial.working.started(first).checkpoint(1, 125))
        val restored = playing.newQueue().load("q")
        assertEquals(125L, restored.working.resumeEntry?.item?.resumePositionSeconds)
        assertEquals(listOf(2L), restored.working.upNext.entries.map { it.entryId })
        val finished = restored.updateWorking(restored.working.finished(1))
        assertEquals(2L, finished.newQueue().load("q").working.resumeEntry?.entryId)
    }

    @Test fun `stale progress and completion cannot change newer queue entry`() {
        val contents = QueueContents().started(second).checkpoint(2, 20)
        assertEquals(contents, contents.checkpoint(1, 900).finished(1))
    }

    @Test fun `new queue preserves saved progress and deleting a name keeps working items`() {
        val saved = SavedQueueLibrary(QueueContents(PlaybackQueue(listOf(first)))).save("q", "  Later  ")
        assertEquals("Later", saved.activeName)
        assertEquals(0, saved.newQueue().working.size)
        assertEquals(saved.working, saved.newQueue().load("q").working)
        assertEquals(saved.working, saved.delete("q").working)
        assertNull(saved.delete("q").activeId)
        assertEquals(1L, saved.newQueue().maxEntryId)
    }

    @Test fun `remote entries require the same server and user`() {
        assertTrue(first.canPlayWith(owner))
        assertFalse(first.canPlayWith(null))
        assertFalse(first.canPlayWith(UserSession("other", "server")))
        assertFalse(first.canPlayWith(UserSession("user", "other")))
        assertFalse(PlaylistEntry(3, media).canPlayWith(owner))
    }

    @Test fun `blank names and empty saved queues are rejected`() {
        assertFailsWith<IllegalArgumentException> { SavedQueueLibrary().save("q", "Later") }
        assertFailsWith<IllegalArgumentException> {
            SavedQueueLibrary(QueueContents(current = first)).save("q", " ")
        }
    }
}
