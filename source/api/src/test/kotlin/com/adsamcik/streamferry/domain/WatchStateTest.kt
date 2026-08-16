package com.adsamcik.streamferry.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WatchStateTest {

    @Test
    fun markWatchedClearsResumeAndSetsFullProgress() {
        val updated = episode(played = false, position = 91, percentage = 38.0).withWatchState(played = true)

        assertTrue(updated.played)
        assertEquals(100.0, updated.playedPercentage)
        assertNull(updated.resumePositionSeconds)
    }

    @Test
    fun markUnwatchedKeepsAnExistingResumePoint() {
        val updated = episode(played = true, position = 91, percentage = 100.0).withWatchState(played = false)

        assertFalse(updated.played)
        assertEquals(0.0, updated.playedPercentage)
        assertEquals(91, updated.resumePositionSeconds)
    }

    @Test
    fun resetProgressReturnsEpisodeToAnUnwatchedStart() {
        val updated = episode(played = true, position = 600, percentage = 100.0).withProgressReset()

        assertFalse(updated.played)
        assertEquals(0.0, updated.playedPercentage)
        assertNull(updated.resumePositionSeconds)
        assertFalse(updated.hasProgressToReset())
    }

    @Test
    fun episodeClassificationDoesNotDependOnConcreteSource() {
        assertTrue(episode().isEpisode())
        assertTrue(episode().copy(sourceId = MediaSourceIds.LOCAL).isEpisode())
        assertFalse(episode().copy(type = "Movie").isEpisode())
    }

    private fun episode(
        played: Boolean = false,
        position: Long? = null,
        percentage: Double? = null,
    ) = MediaItem(
        id = "episode-1",
        title = "Episode 1",
        year = 2026,
        runtimeSeconds = 1_200,
        overview = null,
        resumePositionSeconds = position,
        isFolder = false,
        type = "Episode",
        played = played,
        playedPercentage = percentage,
    )
}
