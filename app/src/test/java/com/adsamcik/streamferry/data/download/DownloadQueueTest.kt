package com.adsamcik.streamferry.data.download

import com.adsamcik.streamferry.source.api.DownloadFormat

import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.nio.file.Files
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
            completed = setOf(pending[1].identity),
            active = setOf(pending[2].identity),
        )
        assertEquals(listOf("a", "d"), resumable.map { it.itemId })
    }

    @Test
    fun sameItemIdFromDifferentOwnersDoesNotBlockResume() {
        val first = PendingDownload(
            "same", "First", PersistedFormat.from(DownloadFormat.Original), DownloadOwner("server-a", "user-a"),
        )
        val second = PendingDownload(
            "same", "Second", PersistedFormat.from(DownloadFormat.Original), DownloadOwner("server-b", "user-b"),
        )

        val resumable = DownloadQueue.selectResumable(
            listOf(first, second),
            completed = setOf(first.identity),
            active = emptySet(),
        )

        assertEquals(listOf(second), resumable)
    }

    @Test
    fun queueStorageKeepsSameIdSeparatedByOwner() = runBlocking {
        val root = Files.createTempDirectory("download-queue-owner").toFile()
        try {
            val ownerA = DownloadOwner("server-a", "user-a")
            val ownerB = DownloadOwner("server-b", "user-b")
            val first = PendingDownload(
                itemId = "same",
                title = "First",
                format = PersistedFormat.from(DownloadFormat.Original),
                owner = ownerA,
            )
            val second = PendingDownload(
                itemId = "same",
                title = "Second",
                format = PersistedFormat.from(DownloadFormat.Original),
                owner = ownerB,
            )
            val queue = DownloadQueueStore.forFilesDir(root)

            queue.add(first)
            queue.add(second)
            assertEquals(listOf(first), queue.allForOwner(ownerA))
            assertEquals(listOf(second), queue.allForOwner(ownerB))

            queue.remove(first.itemId, ownerA)
            assertTrue(queue.allForOwner(ownerA).isEmpty())
            assertEquals(listOf(second), queue.allForOwner(ownerB))
        } finally {
            root.deleteRecursively()
        }
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
            assertTrue(DownloadQueue.isRecoverableFailure(MediaDownloader.DownloadHttpException(code)), "HTTP $code should be recoverable")
        }
    }

    @Test
    fun permanentFailuresAreNotRecoverable() {
        // 4xx that mean the item/session is gone, plus auth, plus a non-downloadable (HLS-only) item.
        listOf(400, 401, 403, 404, 410, 416).forEach { code ->
            assertFalse(DownloadQueue.isRecoverableFailure(MediaDownloader.DownloadHttpException(code)), "HTTP $code should be permanent")
        }
        assertFalse(DownloadQueue.isRecoverableFailure(IllegalArgumentException("This title can only be streamed, not downloaded.")))
        assertFalse(DownloadQueue.isRecoverableFailure(IllegalStateException("Empty response")))
    }
}
