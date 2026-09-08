package com.adsamcik.streamferry.core

import com.adsamcik.streamferry.core.hls.HlsDeliveryTimeline
import com.adsamcik.streamferry.core.http.ByteRange
import kotlin.test.*

class HlsDeliveryTimelineTest {
    @Test fun `fractional segments map to timeline without including init key or subtitle attributes`() {
        val spans = HlsDeliveryTimeline.parse("#EXTM3U\n#EXT-X-MAP:URI=\"init.mp4\"\n#EXT-X-KEY:METHOD=AES-128,URI=\"key\"\n#EXTINF:2.502,\na.m4s\n#EXTINF:3.001,\nb.m4s\n#EXT-X-ENDLIST")
        assertEquals(listOf("a.m4s", "b.m4s"), spans.map { it.reference })
        assertEquals(2502, spans[1].startMs)
        assertEquals(5503, spans[1].endMs)
    }
    @Test fun `byte ranges keep exact offsets and implicit offsets require the same resource`() {
        val body = "#EXTM3U\n#EXTINF:2,\n#EXT-X-BYTERANGE:100@20\nfile.mp4\n#EXTINF:2,\n#EXT-X-BYTERANGE:50\nfile.mp4"
        assertEquals(listOf(ByteRange(20,119), ByteRange(120,169)), HlsDeliveryTimeline.parse(body).map { it.byteRange })
        assertTrue(HlsDeliveryTimeline.parse(body.replace("50\nfile", "50\nother")).isEmpty())
    }
    @Test fun `live window gaps discontinuities and malformed durations cannot invent media time`() {
        val body = "#EXTM3U\n#EXTINF:2,\na.ts"
        for (tag in listOf("#EXT-X-MEDIA-SEQUENCE:50", "#EXT-X-GAP", "#EXT-X-DISCONTINUITY", "#EXT-X-I-FRAMES-ONLY")) {
            assertTrue(HlsDeliveryTimeline.parse(body.replace("#EXTM3U", "#EXTM3U\n$tag")).isEmpty(), tag)
        }
        for (duration in listOf("NaN", "Infinity", "-1", "0", "500000")) {
            assertTrue(HlsDeliveryTimeline.parse(body.replace("2,", "$duration,")).isEmpty())
        }
        assertTrue(HlsDeliveryTimeline.parse("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=100\nvideo.m3u8").isEmpty())
    }
}
