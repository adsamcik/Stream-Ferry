package com.videobridge.playback

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Contract for [RendererCapabilityStore] (idea 5): learning that a renderer can't direct-play a given
 * source container so the next play of that format skips the doomed attempt. Verified against the
 * in-memory impl; the persistent impl shares the same [rendererCapabilityEntry] keying.
 */
class RendererCapabilityStoreTest {

    private val store = InMemoryRendererCapabilityStore()

    @Test fun unknownRendererDoesNotForceTranscode() {
        assertFalse(store.shouldForceTranscode("CAST:Living Room TV", "mkv"))
    }

    @Test fun recordedContainerForcesTranscodeForThatDeviceAndContainerOnly() {
        store.recordTranscodeRequired("CAST:Living Room TV", "mkv")
        assertTrue(store.shouldForceTranscode("CAST:Living Room TV", "mkv"))
        // A different container on the same device is still tried directly (it may well direct-play).
        assertFalse(store.shouldForceTranscode("CAST:Living Room TV", "mp4"))
        // A different device is unaffected.
        assertFalse(store.shouldForceTranscode("DLNA:Bedroom TV", "mkv"))
    }

    @Test fun clearForgetsEverything() {
        store.recordTranscodeRequired("CAST:TV", "mkv")
        store.clear()
        assertFalse(store.shouldForceTranscode("CAST:TV", "mkv"))
    }

    @Test fun nullContainerIsADeviceWideWildcardDistinctFromNamedContainers() {
        store.recordTranscodeRequired("CAST:TV", null)
        assertTrue(store.shouldForceTranscode("CAST:TV", null))
        assertFalse(store.shouldForceTranscode("CAST:TV", "mkv"))
    }

    @Test fun entryKeyingIsStableAndDistinguishesContainers() {
        assertTrue(rendererCapabilityEntry("CAST:TV", "mkv") == rendererCapabilityEntry("CAST:TV", "mkv"))
        assertTrue(rendererCapabilityEntry("CAST:TV", "mkv") != rendererCapabilityEntry("CAST:TV", "mp4"))
        assertTrue(rendererCapabilityEntry("CAST:TV", null) != rendererCapabilityEntry("CAST:TV", "mkv"))
    }
}
