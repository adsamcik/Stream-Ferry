package com.adsamcik.streamferry.core

import com.adsamcik.streamferry.core.hls.HlsSegmentRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HlsSegmentRegistryTest {

    private val secret = "https://jelly.example.com/videos/1/hls1/main/0.ts?api_key=SECRET123&PlaySessionId=abc"

    @Test fun encodeThenResolveRoundTrips() {
        val r = HlsSegmentRegistry()
        val opaque = r.encode(secret)
        assertEquals(secret, r.resolve(opaque))
    }

    @Test fun sameUrlGetsStableOpaque() {
        val r = HlsSegmentRegistry()
        assertEquals(r.encode(secret), r.encode(secret))
        assertEquals(1, r.size())
    }

    @Test fun differentUrlsGetDifferentOpaques() {
        val r = HlsSegmentRegistry()
        val a = r.encode("https://jelly/a.ts?api_key=x")
        val b = r.encode("https://jelly/b.ts?api_key=x")
        assertFalse(a == b)
    }

    @Test fun opaqueDoesNotLeakTheUpstreamUrlOrToken() {
        val r = HlsSegmentRegistry()
        val opaque = r.encode(secret)
        assertFalse(opaque.contains("jelly"))
        assertFalse(opaque.contains("SECRET123"))
        assertFalse(opaque.contains("api_key"))
        assertFalse(opaque.contains("/"))
    }

    @Test fun resolveUnknownReturnsNull() {
        assertNull(HlsSegmentRegistry().resolve("nope"))
    }

    @Test fun boundedByMaxEntriesEvictsOldest() {
        val r = HlsSegmentRegistry(maxEntries = 2)
        val o1 = r.encode("u1")
        val o2 = r.encode("u2")
        val o3 = r.encode("u3") // evicts u1
        assertEquals(2, r.size())
        assertNull(r.resolve(o1))
        assertEquals("u2", r.resolve(o2))
        assertEquals("u3", r.resolve(o3))
        // After eviction the evicted URL can be re-encoded to a fresh opaque.
        val o1b = r.encode("u1")
        assertTrue(o1b != o1)
        assertEquals("u1", r.resolve(o1b))
    }

    @Test fun batchRetainsEveryMappingItReturns() {
        val r = HlsSegmentRegistry(maxEntries = 3)
        val oldest = r.encode("oldest")
        r.encode("unprotected-1")
        r.encode("unprotected-2")

        val batch = r.encodeBatch(listOf("oldest", "new-1", "new-2"))

        assertEquals(oldest, batch.getValue("oldest"))
        assertEquals(3, r.size())
        batch.forEach { (url, opaque) -> assertEquals(url, r.resolve(opaque)) }
    }

    @Test fun oversizedBatchRejectsWithoutChangingExistingMappings() {
        val r = HlsSegmentRegistry(maxEntries = 2)
        val first = r.encode("first")
        val second = r.encode("second")

        kotlin.test.assertFailsWith<IllegalArgumentException> {
            r.encodeBatch(listOf("new-1", "new-2", "new-3"))
        }

        assertEquals("first", r.resolve(first))
        assertEquals("second", r.resolve(second))
        assertEquals(2, r.size())
    }

    @Test fun batchDeduplicatesUrlsBeforeApplyingCapacity() {
        val r = HlsSegmentRegistry(maxEntries = 1)

        val batch = r.encodeBatch(listOf("same", "same"))

        assertEquals(1, batch.size)
        assertEquals("same", r.resolve(batch.getValue("same")))
    }
}
