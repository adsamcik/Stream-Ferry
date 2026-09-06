package com.adsamcik.streamferry.data.queue

import com.adsamcik.streamferry.domain.MediaItem
import com.adsamcik.streamferry.domain.UserSession
import com.adsamcik.streamferry.playback.*
import java.nio.file.Files
import kotlin.test.*

class SavedQueueStoreTest {
    private val item = MediaItem("video", "Video", null, 600, null, 42, false)
    private val entry = PlaylistEntry(1, item, UserSession("user", "server"))
    private val library = SavedQueueLibrary(QueueContents(current = entry)).save("one", "Weeknights")

    @Test fun `process restart restores current item progress owner and named queues`() {
        val dir = Files.createTempDirectory("saved-queues").toFile()
        try {
            val file = dir.resolve("queues.json")
            SavedQueueStore(file).write(library)
            assertEquals(library, SavedQueueStore(file).read())
            SavedQueueStore(file).clear()
            assertEquals(SavedQueueLibrary(), SavedQueueStore(file).read())
        } finally { dir.deleteRecursively() }
    }

    @Test fun `interrupted or corrupt write recovers last committed queue`() {
        val dir = Files.createTempDirectory("saved-queues-recovery").toFile()
        try {
            val file = dir.resolve("queues.json")
            val store = SavedQueueStore(file)
            store.write(library)
            store.write(library.newQueue())
            file.writeText("{partial")
            assertEquals(library, SavedQueueStore(file).read())
            store.clear()
            assertFalse(dir.resolve("queues.json.bak").exists())
        } finally { dir.deleteRecursively() }
    }

    @Test fun `local and repeated entries survive serialization independently`() {
        val local = entry.copy(entryId = 2, item = item.copy(id = "content://media/video/3", sourceId = "local"), owner = null)
        val contents = QueueContents(PlaybackQueue(listOf(entry.copy(entryId = 3), local)), entry)
        val queues = SavedQueueLibrary(contents).save("mix", "Mixed")
        assertEquals(queues, SavedQueueCodec.decode(SavedQueueCodec.encode(queues)))
    }

    @Test fun `unknown versions and unscoped remote entries fail closed`() {
        val encoded = SavedQueueCodec.encode(library)
        assertFails { SavedQueueCodec.decode(encoded.replaceFirst("\"working\"", "\"version\":999,\"working\"")) }
        assertFails { SavedQueueCodec.decode(encoded.replace("\"userId\":\"user\"", "\"userId\":null")) }
    }
}
