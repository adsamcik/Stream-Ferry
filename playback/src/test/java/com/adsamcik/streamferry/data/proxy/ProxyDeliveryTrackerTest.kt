package com.adsamcik.streamferry.data.proxy

import com.adsamcik.streamferry.core.http.ByteRange
import kotlin.test.*

class ProxyDeliveryTrackerTest {
    @Test fun `external audio rendition must cover the same timeline as video`() {
        val tracker = ProxyDeliveryTracker()
        tracker.playlist(null, "#EXTM3U\n#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"sound\",URI=\"audio\"\n#EXT-X-STREAM-INF:BANDWIDTH=100,AUDIO=\"sound\"\nvideo",
            mapOf("video" to "v", "audio" to "a"))
        tracker.playlist("v", "#EXTM3U\n#EXTINF:10,\nv.m4s", mapOf("v.m4s" to "video"))
        tracker.playlist("a", "#EXTM3U\n#EXTINF:6,\na.aac", mapOf("a.aac" to "audio"))
        assertTrue(tracker.completed("video", null, 0).isEmpty())
        assertEquals(6000, tracker.completed("audio", null, 0).single().endMs)
        val epoch = tracker.renew()
        assertTrue(tracker.completed("video", null, epoch).isEmpty())
    }

    @Test fun `only primary media segments provide evidence`() {
        val tracker = ProxyDeliveryTracker()
        tracker.playlist(null, "#EXTM3U\n#EXT-X-MEDIA:TYPE=AUDIO,URI=\"audio\"\n#EXT-X-STREAM-INF:BANDWIDTH=100\nvideo",
            mapOf("video" to "v", "audio" to "a"))
        tracker.playlist("a", "#EXTM3U\n#EXTINF:10,\na.aac", mapOf("a.aac" to "audio"))
        tracker.playlist("v", "#EXTM3U\n#EXT-X-MAP:URI=\"init\"\n#EXTINF:10,\nv.m4s", mapOf("v.m4s" to "video", "init" to "init"))
        assertTrue(tracker.completed("audio", null,0).isEmpty())
        assertTrue(tracker.completed("init", null,0).isEmpty())
        assertEquals(10_000, tracker.completed("video",null,0).single().endMs)
    }
    @Test fun `partial ranges and old seek epochs cannot count as delivery`() {
        val tracker = ProxyDeliveryTracker()
        tracker.playlist(null,"#EXTM3U\n#EXTINF:10,\n#EXT-X-BYTERANGE:100@50\nv.mp4",mapOf("v.mp4" to "v"))
        assertTrue(tracker.completed("v",null,0).isEmpty())
        assertTrue(tracker.completed("v",ByteRange(50,99),0).isEmpty())
        assertEquals(1, tracker.completed("v",ByteRange(50,149),0).size)
        val next = tracker.renew()
        assertTrue(tracker.completed("v",ByteRange(50,149),0).isEmpty())
        assertEquals(1, tracker.completed("v",ByteRange(50,149),next).size)
    }
    @Test fun `ambiguous media reuse and subtitles are ignored`() {
        val tracker = ProxyDeliveryTracker()
        tracker.playlist(null,"#EXTM3U\n#EXTINF:10,\nv.ts",mapOf("v.ts" to "v"))
        tracker.playlist(null,"#EXTM3U\n#EXTINF:20,\nv.ts",mapOf("v.ts" to "v"))
        tracker.playlist(null,"#EXTM3U\n#EXTINF:10,\nsub.vtt",mapOf("sub.vtt" to "sub"))
        assertTrue(tracker.completed("v",null,0).isEmpty())
        assertTrue(tracker.completed("sub",null,0).isEmpty())
    }
}
