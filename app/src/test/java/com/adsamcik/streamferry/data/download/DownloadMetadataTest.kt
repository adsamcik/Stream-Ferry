package com.adsamcik.streamferry.data.download

import com.adsamcik.streamferry.source.api.DownloadFormat

import com.adsamcik.streamferry.domain.MediaItem
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DownloadMetadataTest {

    private val media = MediaItem(
        id = "movie-1",
        title = "Offline Movie",
        year = 2026,
        runtimeSeconds = 1234,
        overview = "A full metadata snapshot.",
        resumePositionSeconds = 42,
        isFolder = false,
        type = "Movie",
        subtitle = "Director's cut",
        imageTag = "poster-tag",
    )
    private val owner = DownloadOwner(serverId = "server-a", userId = "user-a")

    @Test
    fun completedAndPendingMetadataRoundTrip() {
        val pending = PendingDownload(
            itemId = media.id,
            title = media.title,
            format = PersistedFormat.from(DownloadFormat.Original),
            owner = owner,
            mediaItem = media,
        )
        val entry = DownloadEntry(
            itemId = media.id,
            title = media.title,
            fileName = "movie-1.mp4",
            mimeType = "video/mp4",
            sizeBytes = 123,
            owner = owner,
            mediaItem = media,
        )
        val json = Json { ignoreUnknownKeys = true }

        assertEquals(
            pending,
            json.decodeFromString(PendingDownload.serializer(), json.encodeToString(PendingDownload.serializer(), pending)),
        )
        assertEquals(
            entry,
            json.decodeFromString(DownloadEntry.serializer(), json.encodeToString(DownloadEntry.serializer(), entry)),
        )
    }

    @Test
    fun legacyMetadataWithoutOwnerOrSnapshotStillDecodes() {
        val json = Json { ignoreUnknownKeys = true }
        val pending = json.decodeFromString(
            PendingDownload.serializer(),
            """{"itemId":"legacy","title":"Legacy","format":{"kind":"original"}}""",
        )
        val entry = json.decodeFromString(
            DownloadEntry.serializer(),
            """{"itemId":"legacy","title":"Legacy","fileName":"legacy.mp4","mimeType":"video/mp4"}""",
        )

        assertNull(pending.owner)
        assertNull(pending.mediaItem)
        assertNull(entry.owner)
        assertNull(entry.mediaItem)
    }

    @Test
    fun listPrunesAnEntryWhoseMediaFileIsMissing() = runBlocking {
        val root = Files.createTempDirectory("download-store").toFile()
        try {
            val store = DownloadStore.forFilesDir(root)
            val entry = DownloadEntry(
                itemId = media.id,
                title = media.title,
                fileName = "movie-1.mp4",
                mimeType = "video/mp4",
                sizeBytes = 3,
                owner = owner,
                mediaItem = media,
            )
            store.upsert(entry)

            assertTrue(store.list().isEmpty())

            // A later file with the same name must not resurrect the orphaned index entry: list() prunes,
            // rather than merely hiding, an entry that cannot be played.
            store.fileFor(entry).apply {
                parentFile?.mkdirs()
                writeBytes(byteArrayOf(1, 2, 3))
            }
            assertNull(store.get(entry.itemId))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun sameItemIdFromDifferentOwnersUsesSeparateFilesAndIndexEntries() = runBlocking {
        val root = Files.createTempDirectory("download-owner-scope").toFile()
        try {
            val store = DownloadStore.forFilesDir(root)
            val secondOwner = DownloadOwner(serverId = "server-b", userId = "user-b")

            fun entryFor(entryOwner: DownloadOwner) = DownloadEntry(
                itemId = media.id,
                title = media.title,
                fileName = DownloadIdentity(entryOwner, media.id).fileName("mp4", "video/mp4"),
                mimeType = "video/mp4",
                sizeBytes = 3,
                owner = entryOwner,
                mediaItem = media,
            )

            val first = entryFor(owner)
            val second = entryFor(secondOwner)
            assertNotEquals(first.fileName, second.fileName)
            assertNotEquals(
                DownloadIdentity(owner, media.id).resumeKey,
                DownloadIdentity(secondOwner, media.id).resumeKey,
            )
            listOf(first, second).forEach { entry ->
                store.fileFor(entry).apply {
                    parentFile?.mkdirs()
                    writeBytes(byteArrayOf(1, 2, 3))
                }
                store.upsert(entry)
            }

            assertEquals(first, store.get(owner, media.id))
            assertEquals(second, store.get(secondOwner, media.id))
            assertEquals(listOf(first), store.list(owner))

            store.remove(owner, media.id)
            assertNull(store.get(owner, media.id))
            assertEquals(second, store.get(secondOwner, media.id))
        } finally {
            root.deleteRecursively()
        }
    }
}
