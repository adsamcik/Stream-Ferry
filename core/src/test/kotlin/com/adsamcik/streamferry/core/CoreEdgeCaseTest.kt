package com.adsamcik.streamferry.core

import com.adsamcik.streamferry.core.buffer.MemoryBufferPolicy
import com.adsamcik.streamferry.core.dlna.DidlLite
import com.adsamcik.streamferry.core.hls.HlsRewriter
import com.adsamcik.streamferry.core.http.ByteRange
import com.adsamcik.streamferry.core.http.HttpRange
import com.adsamcik.streamferry.core.http.HttpResponsePlan
import com.adsamcik.streamferry.core.http.RangeParseResult
import com.adsamcik.streamferry.core.session.SessionLookup
import com.adsamcik.streamferry.core.session.SessionRegistry
import com.adsamcik.streamferry.core.stream.MediaProfile
import com.adsamcik.streamferry.core.stream.PlayMethod
import com.adsamcik.streamferry.core.stream.Protocol
import com.adsamcik.streamferry.core.stream.StreamPreferences
import com.adsamcik.streamferry.core.stream.StreamSelectionService
import com.adsamcik.streamferry.core.stream.TargetCapabilities
import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Additional edge-case coverage for the pure-JVM security/correctness core. These exercise paths
 * not covered by the existing focused test classes (allowed sub-paths, query stripping, ID-shape
 * bounds, backslash/NUL traversal, unknown/open-ended length response planning, HLS MIME + comment
 * preservation, DIDL duration/escaping, buffer windowing, and stream-selection branches).
 */
class CoreEdgeCaseTest {

    // ----------------------------- SessionRegistry -----------------------------

    private fun registry(now: () -> Long = { 0L }) =
        SessionRegistry(random = SecureRandom(), clock = now)

    @Test fun allowedSubPathsResolve() {
        val reg = registry()
        val s = reg.create("u", null, "application/vnd.apple.mpegurl", null, isHls = true)
        assertIs<SessionLookup.Ok>(reg.resolve("/session/${s.id}/playlist.m3u8"))
        assertIs<SessionLookup.Ok>(reg.resolve("/session/${s.id}/test"))
        assertIs<SessionLookup.Ok>(reg.resolve("/session/${s.id}/stream"))
    }

    @Test fun queryStringIsStrippedNotLeaked() {
        val reg = registry()
        val s = reg.create("http://jelly/stream?api_key=secret", "auth", "video/mp4", "ps")
        // A trailing query (e.g. Cast appends cache-busting params) must still resolve.
        val r = reg.resolve("/session/${s.id}/stream?foo=bar&t=123")
        assertIs<SessionLookup.Ok>(r)
        assertEquals(s.id, r.session.id)
    }

    @Test fun idShapeBoundsRejected() {
        val reg = registry()
        // Too short (< 32) and clearly-too-long (> 64) IDs are not valid session-id shapes.
        assertIs<SessionLookup.NotFound>(reg.resolve("/session/short/stream"))
        assertIs<SessionLookup.NotFound>(reg.resolve("/session/${"a".repeat(65)}/stream"))
    }

    @Test fun backslashAndNulRejected() {
        val reg = registry()
        val s = reg.create("u", null, "video/mp4", null)
        assertIs<SessionLookup.Forbidden>(reg.resolve("/session/${s.id}/str\\eam"))
        assertIs<SessionLookup.Forbidden>(reg.resolve("/session/${s.id}/stream\u0000"))
    }

    @Test fun purgeExpiredRemovesAndCounts() {
        var t = 0L
        val reg = registry { t }
        reg.create("u", null, "video/mp4", null, ttlMillis = 1000)
        reg.create("u", null, "video/mp4", null, ttlMillis = 1000)
        assertEquals(2, reg.activeCount())
        t = 5000
        assertEquals(2, reg.purgeExpired())
        assertEquals(0, reg.activeCount())
    }

    // ----------------------------- HttpResponsePlan ----------------------------

    @Test fun unknownLength200HasNoContentLength() {
        val plan = HttpResponsePlan.plan(RangeParseResult.None, -1, "video/mp4")
        assertEquals(HttpResponsePlan.Status.OK, plan.status)
        assertFalse(plan.headers.containsKey("Content-Length"))
        assertEquals("bytes", plan.headers["Accept-Ranges"])
        assertNull(plan.range)
    }

    @Test fun openEnded206OmitsContentLength() {
        val parsed = HttpRange.parse("bytes=100-", -1)
        assertEquals(RangeParseResult.Satisfiable(ByteRange(100, Long.MAX_VALUE)), parsed)
        val plan = HttpResponsePlan.plan(parsed, -1, "video/mp4")
        assertEquals(HttpResponsePlan.Status.PARTIAL_CONTENT, plan.status)
        assertFalse(plan.headers.containsKey("Content-Length"))
        assertTrue(plan.headers["Content-Range"]!!.endsWith("/*"))
    }

    @Test fun headRequestKeepsIdenticalHeaders() {
        val get = HttpResponsePlan.plan(RangeParseResult.None, 1000, "video/mp4", head = false)
        val head = HttpResponsePlan.plan(RangeParseResult.None, 1000, "video/mp4", head = true)
        assertEquals(get.headers, head.headers)
        assertEquals(get.status, head.status)
    }

    @Test fun malformedRangeServesFull200() {
        val plan = HttpResponsePlan.plan(RangeParseResult.Malformed, 1000, "video/mp4")
        assertEquals(HttpResponsePlan.Status.OK, plan.status)
        assertEquals("1000", plan.headers["Content-Length"])
    }

    @Test fun everyPlanCarriesCorsHeadersForCastReceiverXhr() {
        // The Cast receiver fetches HLS playlists/segments via cross-origin XHR; without CORS the
        // browser blocks the read. All three response shapes (200/206/416) must advertise CORS.
        val plans = listOf(
            HttpResponsePlan.plan(RangeParseResult.None, 1000, "video/mp4"),
            HttpResponsePlan.plan(RangeParseResult.Satisfiable(ByteRange(0, 499)), 1000, "video/mp4"),
            HttpResponsePlan.plan(RangeParseResult.Unsatisfiable(1000), 1000, "video/mp4"),
        )
        for (plan in plans) {
            assertEquals("*", plan.headers["Access-Control-Allow-Origin"])
            assertTrue(plan.headers["Access-Control-Allow-Headers"]!!.contains("Range"))
            assertTrue(plan.headers["Access-Control-Allow-Headers"]!!.contains("Accept-Encoding"))
            assertTrue(plan.headers["Access-Control-Expose-Headers"]!!.contains("Content-Range"))
        }
    }

    // ------------------------------- HlsRewriter -------------------------------

    @Test fun mimeForExtensionIsCaseInsensitive() {
        assertEquals("application/vnd.apple.mpegurl", HlsRewriter.mimeForExtension("M3U8"))
        assertEquals("video/mp2t", HlsRewriter.mimeForExtension("ts"))
        assertNull(HlsRewriter.mimeForExtension("mkv"))
    }

    @Test fun masterPlaylistCommentsPreservedVariantsRewritten() {
        val r = HlsRewriter("http://10.0.0.5:5000/session/ID")
        val master = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080
            https://jelly/variant.m3u8?api_key=secret
        """.trimIndent()
        val out = r.rewrite(master) { "ENC" }
        assertTrue(out.contains("#EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080"))
        assertFalse(out.contains("jelly"))
        assertFalse(out.contains("secret"))
        assertTrue(out.contains("http://10.0.0.5:5000/session/ID/stream?seg=ENC"))
    }

    @Test fun blankLinesPreserved() {
        val r = HlsRewriter("http://p/session/ID")
        val out = r.rewrite("#EXTM3U\n\n#EXT-X-ENDLIST\n") { "ENC" }
        assertTrue(out.contains("\n\n"))
        assertTrue(out.endsWith("\n"))
    }

    // --------------------------------- DidlLite --------------------------------

    @Test fun durationFormatting() {
        assertEquals("0:00:00", DidlLite.formatDuration(0))
        assertEquals("0:00:59", DidlLite.formatDuration(59))
        assertEquals("1:00:00", DidlLite.formatDuration(3600))
        assertEquals("2:03:04", DidlLite.formatDuration(2 * 3600 + 3 * 60 + 4))
    }

    @Test fun didlEscapesApostropheAndIncludesProfile() {
        val xml = DidlLite.build(
            proxyUrl = "http://10.0.0.5:5000/session/ID/stream",
            title = "It's a Trap",
            mimeType = "video/mp4",
            dlnaProfile = "AVC_MP4_MP_HD_720p_AAC",
        )
        assertTrue(xml.contains("It&apos;s a Trap"))
        assertTrue(xml.contains("DLNA.ORG_PN=AVC_MP4_MP_HD_720p_AAC"))
        assertFalse(xml.contains("jelly"))
    }

    // ----------------------------- MemoryBufferPolicy --------------------------

    @Test fun passThroughFlushIntervalExceedsItsReusableCopyChunk() {
        assertTrue(
            MemoryBufferPolicy.FLUSH_INTERVAL_BYTES >= MemoryBufferPolicy.COPY_CHUNK_BYTES,
        )
    }

    // --------------------------- StreamSelectionService ------------------------

    private val svc = StreamSelectionService()
    private val castH264 = TargetCapabilities(
        protocol = Protocol.CAST,
        supportedContainers = setOf("mp4"),
        supportedVideoCodecs = setOf("h264"),
        supportedAudioCodecs = setOf("aac"),
    )

    private fun media(
        container: String = "mp4", v: String = "h264", a: String = "aac",
        sub: String? = null,
    ) = MediaProfile(container, v, audioCodec = a, subtitleFormat = sub)

    @Test fun preferQualityCompatibleIsDirectPlay() {
        val d = svc.select(castH264, media(), StreamPreferences(mode = StreamPreferences.Mode.PREFER_QUALITY))
        assertEquals(PlayMethod.DIRECT_PLAY, d.playMethod)
        assertTrue(d.rationale.contains("Quality mode"))
    }

    @Test fun disableSubtitlesSkipsBurnIn() {
        val d = svc.select(castH264, media(sub = "pgs"), StreamPreferences(disableSubtitles = true))
        assertEquals(PlayMethod.DIRECT_PLAY, d.playMethod)
        assertFalse(d.burnInSubtitles)
    }

    @Test fun producesHlsTracksTargetHlsCapability() {
        val withHls = svc.select(castH264.copy(supportsHls = true), media(v = "hevc"), StreamPreferences())
        assertEquals(PlayMethod.HLS_TRANSCODE, withHls.playMethod)
        assertTrue(withHls.producesHls)

        val noHls = svc.select(castH264.copy(supportsHls = false), media(v = "hevc"), StreamPreferences())
        assertEquals(PlayMethod.HLS_TRANSCODE, noHls.playMethod)
        assertFalse(noHls.producesHls)
    }
}
