package com.videobridge.data.download

import com.videobridge.data.jellyfin.JellyfinHttpException
import java.io.IOException
import java.net.SocketTimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DownloadQueueTest {

    @Test
    fun originalFormatRoundTrips() {
        val restored = PersistedFormat.from(DownloadFormat.Original).toDownloadFormat()
        assertEquals(DownloadFormat.Original, restored)
    }

    @Test
    fun transcodeFormatRoundTripsAllFields() {
        val original = DownloadFormat.Transcode(
            label = "MP4 · 720p",
            container = "mp4",
            videoCodec = "h264",
            audioCodec = "aac",
            maxBitrateBps = 4_000_000,
        )
        val restored = PersistedFormat.from(original).toDownloadFormat()
        assertEquals(original, restored)
    }

    @Test
    fun unknownKindDefaultsToOriginal() {
        assertEquals(DownloadFormat.Original, PersistedFormat(kind = "something-else").toDownloadFormat())
    }

    private fun pending(id: String) = PendingDownload(id, "Title $id", PersistedFormat.from(DownloadFormat.Original))

    @Test
    fun selectResumableSkipsCompletedAndActive() {
        val pending = listOf(pending("a"), pending("b"), pending("c"), pending("d"))
        val resumable = DownloadQueue.selectResumable(
            pending = pending,
            completedIds = setOf("b"),
            activeIds = setOf("c"),
        )
        assertEquals(listOf("a", "d"), resumable.map { it.itemId })
    }

    @Test
    fun selectResumableReturnsEmptyWhenNothingPending() {
        assertTrue(DownloadQueue.selectResumable(emptyList(), emptySet(), emptySet()).isEmpty())
    }

    @Test
    fun selectResumablePreservesOrderAndFormat() {
        val transcode = DownloadFormat.Transcode("720p", "mp4", "h264", "aac", 4_000_000)
        val pending = listOf(
            PendingDownload("x", "X", PersistedFormat.from(transcode)),
            PendingDownload("y", "Y", PersistedFormat.from(DownloadFormat.Original)),
        )
        val resumable = DownloadQueue.selectResumable(pending, emptySet(), emptySet())
        assertEquals(listOf("x", "y"), resumable.map { it.itemId })
        assertEquals(transcode, resumable[0].format.toDownloadFormat())
    }

    @Test
    fun transientFailuresAreRecoverable() {
        assertTrue(DownloadQueue.isRecoverableFailure(IOException("connection reset")))
        assertTrue(DownloadQueue.isRecoverableFailure(SocketTimeoutException("timeout")))
        listOf(408, 425, 429, 500, 502, 503, 504).forEach { code ->
            assertTrue(DownloadQueue.isRecoverableFailure(JellyfinHttpException(code)), "HTTP $code should be recoverable")
        }
    }

    @Test
    fun permanentFailuresAreNotRecoverable() {
        // 4xx that mean the item/session is gone, plus auth, plus a non-downloadable (HLS-only) item.
        listOf(400, 401, 403, 404, 410, 416).forEach { code ->
            assertFalse(DownloadQueue.isRecoverableFailure(JellyfinHttpException(code)), "HTTP $code should be permanent")
        }
        assertFalse(DownloadQueue.isRecoverableFailure(IllegalArgumentException("This title can only be streamed, not downloaded.")))
        assertFalse(DownloadQueue.isRecoverableFailure(IllegalStateException("Empty response")))
    }
}
