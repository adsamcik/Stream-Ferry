package com.adsamcik.streamferry.playback

import com.adsamcik.streamferry.core.resume.SmartResumeSeed
import com.adsamcik.streamferry.core.resume.SmartResumeSourceType
import kotlin.test.*

class PlaybackWatchStatePolicyTest {
    private val first = SmartResumeSeed(SmartResumeSourceType.REMOTE, "a", "A", serverId = "server", userId = "user")

    @Test fun `watch edits update current queued and saved snapshots only for the owning account`() {
        val owner = com.adsamcik.streamferry.domain.UserSession("user", "server")
        val movie = com.adsamcik.streamferry.domain.MediaItem("movie", "Movie", null, 1200, null, 300, false)
        val a = PlaylistEntry(1, movie, owner)
        val b = PlaylistEntry(2, movie, owner.copy(userId = "other"))
        val contents = QueueContents(PlaybackQueue(listOf(a.copy(entryId = 3), b)), a)
        val queues = SavedQueueLibrary(contents).save("saved", "Saved")
        val changed = queues.updateWatchState(owner, movie.id) { it.copy(played = true, resumePositionSeconds = null) }
        assertTrue(changed.working.current!!.item.played)
        assertTrue(changed.working.upNext.entries.first().item.played)
        assertEquals(b, changed.working.upNext.entries.last())
        assertEquals(changed.working, changed.saved.single().contents)
    }

    @Test fun `queued and foreign account edits do not suppress current or future playback`() {
        val policy = PlaybackWatchStatePolicy()
        policy.begin(first, false) {}
        assertFalse(policy.suppress("server", "user", "b") {})
        assertFalse(policy.suppress("server", "other", "a") {})
        assertTrue(policy.current!!.automatic)
        policy.begin(first.copy(mediaId = "b"), false) {}
        assertTrue(policy.current!!.automatic)
    }

    @Test fun `manual override survives recovery but resets on next playback including repeated titles`() {
        val policy = PlaybackWatchStatePolicy()
        policy.begin(first, false) {}
        val original = policy.current!!
        var detached = false
        assertTrue(policy.suppress("server", "user", "a") { detached = true })
        assertTrue(detached)
        assertFalse(original.automatic)
        policy.end()
        assertFalse(policy.suppress("server", "user", "a") {})
        policy.begin(first, true) { assertFalse(it) }
        assertSame(original, policy.current)
        policy.begin(first, false) { assertTrue(it) }
        assertNotSame(original, policy.current)
        assertFalse(original.automatic, "Buffered events retain their original playback's override")
        policy.begin(first.copy(mediaId = "b"), false) { assertTrue(it) }
    }

    @Test fun `downloaded playback participates while ownerless local playback does not`() {
        val policy = PlaybackWatchStatePolicy()
        policy.begin(first.copy(sourceType = SmartResumeSourceType.DOWNLOADED), false) {}
        assertTrue(policy.suppress("server", "user", "a") {})
        policy.begin(null, false) {}
        assertFalse(policy.suppress("server", "user", "a") {})
    }
}
