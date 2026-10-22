package com.adsamcik.streamferry.app

import com.adsamcik.streamferry.data.download.DownloadEntry
import com.adsamcik.streamferry.domain.MediaItem
import com.adsamcik.streamferry.domain.MediaSourceIds
import org.junit.Test
import kotlin.test.assertEquals

class DownloadedPlaybackItemTest {
    @Test fun `download metadata preserves selected video and series preferences identity`() {
        val video = MediaItem("Y", "Episode Y", null, 600, null, null, false, seriesId = "series-Y")
        val entry = DownloadEntry("Y", "Episode Y", "Y.mp4", "video/mp4", mediaItem = video)
        assertEquals(video, entry.playbackItem())
    }

    @Test fun `legacy or mismatched metadata cannot borrow a previous gallery identity`() {
        val entry = DownloadEntry("Y", "Video Y", "Y.mp4", "video/mp4", runtimeSeconds = 700)
        val stale = MediaItem("X", "Video X", null, 600, null, null, false)
        for (candidate in listOf(entry, entry.copy(mediaItem = stale))) {
            val item = candidate.playbackItem()
            assertEquals("Y", item.id)
            assertEquals("Video Y", item.title)
            assertEquals(700L, item.runtimeSeconds)
            assertEquals(MediaSourceIds.REMOTE, item.sourceId)
        }
    }
}
