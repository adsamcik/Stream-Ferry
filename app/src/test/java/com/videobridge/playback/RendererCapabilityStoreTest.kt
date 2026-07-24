package com.videobridge.playback

import com.videobridge.core.stream.MediaProfile
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Contract for [RendererCapabilityStore]: only a qualified decoder failure is remembered, and the learned
 * entry is keyed by the receiver plus the relevant media-format tuple rather than a whole container.
 */
class RendererCapabilityStoreTest {

    private val store = InMemoryRendererCapabilityStore()
    private val hevc10BitMkv = RendererMediaFormat(
        container = "mkv",
        videoCodec = "hevc",
        videoProfile = "main 10",
        videoLevel = 153,
        bitDepth = 10,
        isHdr = true,
        heightPx = 2160,
        audioCodec = "eac3",
        audioChannels = 6,
    )

    @Test
    fun unknownRendererDoesNotForceTranscode() {
        assertFalse(store.shouldForceTranscode("CAST:Living Room TV", hevc10BitMkv))
    }

    @Test
    fun recordedFormatForcesTranscodeForThatExactRendererAndFormatOnly() {
        store.recordTranscodeRequired("CAST:Living Room TV", hevc10BitMkv)
        assertTrue(store.shouldForceTranscode("CAST:Living Room TV", hevc10BitMkv))
        // Same container but different decoder path must still receive an optimistic direct-play attempt.
        assertFalse(
            store.shouldForceTranscode(
                "CAST:Living Room TV",
                hevc10BitMkv.copy(videoCodec = "h264", bitDepth = 8, isHdr = false),
            ),
        )
        assertFalse(
            store.shouldForceTranscode(
                "CAST:Living Room TV",
                hevc10BitMkv.copy(audioCodec = "aac", audioChannels = 2),
            ),
        )
        // A different device is unaffected.
        assertFalse(store.shouldForceTranscode("DLNA:Bedroom TV", hevc10BitMkv))
    }

    @Test
    fun profileConversionIncludesDecoderRelevantFields() {
        val profile = MediaProfile(
            container = "mkv",
            videoCodec = "hevc",
            videoProfile = "main 10",
            videoLevel = 153,
            bitDepth = 10,
            isHdr = true,
            heightPx = 2160,
            audioCodec = "eac3",
            audioChannels = 6,
        )
        assertTrue(RendererMediaFormat.from(profile) == hevc10BitMkv)
    }

    @Test
    fun onlyQualifiedDirectPlayEvidenceMayBePersisted() {
        val profile = MediaProfile(container = "mkv", videoCodec = "hevc", audioCodec = "eac3")
        assertTrue(
            shouldPersistTranscodeRequirement(
                qualifiedFormatEvidence = true,
                isOnlineDirectPlay = true,
                profile = profile,
            ),
        )
        assertFalse(
            shouldPersistTranscodeRequirement(
                qualifiedFormatEvidence = false,
                isOnlineDirectPlay = true,
                profile = profile,
            ),
        )
        assertFalse(
            shouldPersistTranscodeRequirement(
                qualifiedFormatEvidence = true,
                isOnlineDirectPlay = false,
                profile = profile,
            ),
        )
        assertFalse(
            shouldPersistTranscodeRequirement(
                qualifiedFormatEvidence = true,
                isOnlineDirectPlay = true,
                profile = null,
            ),
        )
    }

    @Test
    fun clearForgetsEverything() {
        store.recordTranscodeRequired("CAST:TV", hevc10BitMkv)
        store.clear()
        assertFalse(store.shouldForceTranscode("CAST:TV", hevc10BitMkv))
    }

    @Test
    fun entryKeyingIsStableAndEscapesDistinctValues() {
        assertTrue(
            rendererCapabilityEntry("CAST:TV", hevc10BitMkv) ==
                rendererCapabilityEntry("CAST:TV", hevc10BitMkv),
        )
        assertFalse(
            rendererCapabilityEntry("CAST:TV", hevc10BitMkv) ==
                rendererCapabilityEntry("CAST:TV", hevc10BitMkv.copy(videoLevel = 120)),
        )
        assertFalse(
            rendererCapabilityEntry("CAST:TV|Office", hevc10BitMkv) ==
                rendererCapabilityEntry("CAST:TV", hevc10BitMkv.copy(container = "Office|mkv")),
        )
    }
}
