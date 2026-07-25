package com.adsamcik.streamferry.core

import com.adsamcik.streamferry.core.dlna.SsdpDiscoveryLimiter
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SsdpDiscoveryLimiterTest {

    @Test fun capsTotalDescribes() {
        val l = SsdpDiscoveryLimiter(maxDescribes = 2, maxPerSource = 10)
        assertTrue(l.allowDescribe("1.1.1.1"))
        assertTrue(l.allowDescribe("2.2.2.2"))
        assertFalse(l.allowDescribe("3.3.3.3")) // total cap reached
    }

    @Test fun capsPerSource() {
        val l = SsdpDiscoveryLimiter(maxDescribes = 100, maxPerSource = 2)
        assertTrue(l.allowDescribe("1.1.1.1"))
        assertTrue(l.allowDescribe("1.1.1.1"))
        assertFalse(l.allowDescribe("1.1.1.1")) // per-source cap reached
        assertTrue(l.allowDescribe("2.2.2.2")) // a different source is still allowed
    }
}
