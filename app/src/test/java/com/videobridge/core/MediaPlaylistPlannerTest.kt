package com.videobridge.core

import com.videobridge.core.hls.MediaPlaylistPlanner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MediaPlaylistPlannerTest {

    private val planner = MediaPlaylistPlanner(targetSegmentSeconds = 2.0)

    @Test fun evenDivisionProducesUniformSegments() {
        val segments = planner.planSegments(10.0)
        assertEquals(5, segments.size)
        assertEquals(0.0, segments.first().startSeconds)
        assertEquals(8.0, segments.last().startSeconds)
        assertTrue(segments.all { it.durationSeconds == 2.0 })
    }

    @Test fun remainderGoesInFinalSegment() {
        val segments = planner.planSegments(9.5)
        assertEquals(5, segments.size)
        assertEquals(1.5, segments.last().durationSeconds)
        assertEquals(8.0, segments.last().startSeconds)
    }

    @Test fun zeroRuntimeHasNoSegments() {
        assertTrue(planner.planSegments(0.0).isEmpty())
    }

    @Test fun emptyPlaylistHasNoMediaSegments() {
        // A zero/unknown duration plans no segments; the resulting VOD playlist then has NO media
        // segments, so a renderer has nothing to fetch and hangs in LOADING (the "local file won't start
        // playing" bug). PlaybackEngine.playLocal must probe the file's real duration before building an
        // on-device transcode (LocalMediaProbe.probeDurationSeconds) so this can't happen.
        val playlist = planner.buildVodPlaylist(emptyList(), fmp4 = false, segmentUri = { "seg/$it" })
        assertFalse(playlist.contains("#EXTINF"))
        assertTrue(playlist.trimEnd().endsWith("#EXT-X-ENDLIST"))
    }

    @Test fun negativeRuntimeRejected() {
        assertFailsWith<IllegalArgumentException> { planner.planSegments(-1.0) }
    }

    @Test fun nonFiniteOrOversizedRuntimeRejectedBeforeAllocation() {
        assertFailsWith<IllegalArgumentException> { planner.planSegments(Double.POSITIVE_INFINITY) }
        assertFailsWith<IllegalArgumentException> {
            planner.planSegments((MediaPlaylistPlanner.MAX_SEGMENTS + 1) * MediaPlaylistPlanner.DEFAULT_SEGMENT_SECONDS)
        }
    }

    @Test fun masterPlaylistDeclaresCodecsAndPointsAtMedia() {
        // Cast/CMAF receivers need the CODECS attribute to start an fMP4 stream; the master must carry it
        // and reference the media playlist.
        val master = planner.buildMasterPlaylist(
            mediaUri = "http://p/stream?seg=media",
            codecs = "avc1.640028,mp4a.40.2",
            width = 1280,
            height = 720,
            bandwidthBps = 4_000_000,
        )
        assertTrue(master.startsWith("#EXTM3U"))
        assertTrue(master.contains("#EXT-X-STREAM-INF:BANDWIDTH=4000000"))
        assertTrue(master.contains("CODECS=\"avc1.640028,mp4a.40.2\""))
        assertTrue(master.contains("RESOLUTION=1280x720"))
        assertTrue(master.trimEnd().endsWith("http://p/stream?seg=media"))
    }

    @Test fun masterPlaylistOmitsCodecsWhenUnknown() {
        val master = planner.buildMasterPlaylist("media.m3u8", codecs = null, width = null, height = null, bandwidthBps = 0)
        assertFalse(master.contains("CODECS="))
        assertFalse(master.contains("RESOLUTION="))
        assertTrue(master.contains("BANDWIDTH=1")) // coerced to >= 1
    }

    @Test fun seekMapsToCorrectSegment() {
        val runtime = 10.0
        assertEquals(0, planner.segmentIndexForPosition(0.0, runtime))
        assertEquals(0, planner.segmentIndexForPosition(1.9, runtime))
        assertEquals(1, planner.segmentIndexForPosition(2.0, runtime))
        assertEquals(3, planner.segmentIndexForPosition(7.0, runtime))
        // A seek to the very end clamps to the last segment, never out of range.
        assertEquals(4, planner.segmentIndexForPosition(10.0, runtime))
        assertEquals(4, planner.segmentIndexForPosition(999.0, runtime))
        // Negative clamps to 0.
        assertEquals(0, planner.segmentIndexForPosition(-5.0, runtime))
    }

    @Test fun seekedSegmentStartNeverExceedsPosition() {
        // The defining property of full-seek correctness: the chosen segment begins at/before the seek.
        val runtime = 123.4
        val segments = planner.planSegments(runtime)
        for (pos in listOf(0.0, 5.0, 33.3, 60.0, 119.9, 123.4)) {
            val idx = planner.segmentIndexForPosition(pos, runtime)
            assertTrue(segments[idx].startSeconds <= pos + 1e-9, "segment start must not exceed seek position")
        }
    }

    @Test fun tsPlaylistIsWellFormed() {
        val segments = planner.planSegments(5.0)
        val playlist = planner.buildVodPlaylist(segments, fmp4 = false, segmentUri = { "seg/$it" })
        assertTrue(playlist.startsWith("#EXTM3U"))
        assertTrue(playlist.contains("#EXT-X-VERSION:3"))
        assertTrue(playlist.contains("#EXT-X-PLAYLIST-TYPE:VOD"))
        assertTrue(playlist.contains("#EXT-X-TARGETDURATION:2"))
        assertTrue(playlist.contains("#EXTINF:2.000,"))
        assertTrue(playlist.contains("seg/0"))
        assertTrue(playlist.contains("seg/2"))
        assertTrue(playlist.trimEnd().endsWith("#EXT-X-ENDLIST"))
        assertFalse(playlist.contains("#EXT-X-MAP"))
    }

    @Test fun fmp4PlaylistEmitsInitMap() {
        val segments = planner.planSegments(4.0)
        val playlist = planner.buildVodPlaylist(
            segments, fmp4 = true, segmentUri = { "seg/$it.m4s" }, initUri = { "init.mp4" },
        )
        assertTrue(playlist.contains("#EXT-X-VERSION:7"))
        assertTrue(playlist.contains("#EXT-X-MAP:URI=\"init.mp4\""))
        assertTrue(playlist.contains("seg/0.m4s"))
    }

    @Test fun fmp4WithoutInitUriThrows() {
        val segments = planner.planSegments(4.0)
        assertFailsWith<IllegalArgumentException> {
            planner.buildVodPlaylist(segments, fmp4 = true, segmentUri = { "seg/$it" })
        }
    }
}
