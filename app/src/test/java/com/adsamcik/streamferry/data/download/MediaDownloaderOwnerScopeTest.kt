package com.adsamcik.streamferry.data.download

import com.adsamcik.streamferry.domain.JellyfinRepository
import com.adsamcik.streamferry.domain.MediaItem
import com.adsamcik.streamferry.logging.DiagnosticsLogger
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MediaDownloaderOwnerScopeTest {

    @Test
    fun inactiveOwnerCannotResumeItsQueueThroughAnotherAccountsClient() = runTest {
        val root = Files.createTempDirectory("download-owner-fence").toFile()
        try {
            val firstOwner = DownloadOwner(serverId = "server-a", userId = "user-a")
            val activeOwner = DownloadOwner(serverId = "server-b", userId = "user-b")
            val pending = PendingDownload(
                itemId = "shared-item",
                title = "Queued film",
                format = PersistedFormat.from(DownloadFormat.Original),
                owner = firstOwner,
                mediaItem = media("shared-item"),
            )
            val queue = DownloadQueueStore.forFilesDir(root)
            queue.add(pending)
            val downloader = MediaDownloader(
                jellyfin = mockk<JellyfinRepository>(relaxed = true),
                store = DownloadStore.forFilesDir(root),
                queue = queue,
                httpClient = OkHttpClient(),
                logger = mockk<DiagnosticsLogger>(relaxed = true),
                scope = backgroundScope,
                activeOwnerProvider = { activeOwner },
            )

            assertFalse(downloader.resumePending(firstOwner))
            assertEquals(listOf(pending), queue.allForOwner(firstOwner))
            assertTrue(downloader.states.value.isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    private fun media(id: String) = MediaItem(
        id = id,
        title = "Queued film",
        year = null,
        runtimeSeconds = null,
        overview = null,
        resumePositionSeconds = null,
        isFolder = false,
    )
}