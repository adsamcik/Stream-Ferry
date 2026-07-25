package com.adsamcik.streamferry.core

import com.adsamcik.streamferry.core.net.LanInterfaceSelector
import com.adsamcik.streamferry.core.net.LanInterfaceSelector.Candidate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LanInterfaceSelectorTest {

    private fun wifi(ip: String = "192.168.1.50", name: String = "wlan0") =
        Candidate(name, ip, isUp = true, isLoopback = false, isVirtual = false, isPointToPoint = false, isSiteLocal = true)

    private fun wireguard(ip: String = "10.7.0.2", name: String = "wg0") =
        Candidate(name, ip, isUp = true, isLoopback = false, isVirtual = false, isPointToPoint = true, isSiteLocal = true)

    @Test fun tunnelNameDetection() {
        assertTrue(LanInterfaceSelector.isTunnelInterface("tun0"))
        assertTrue(LanInterfaceSelector.isTunnelInterface("wg0"))
        assertTrue(LanInterfaceSelector.isTunnelInterface("ppp0"))
        assertTrue(LanInterfaceSelector.isTunnelInterface("utun3"))
        assertFalse(LanInterfaceSelector.isTunnelInterface("wlan0"))
        assertFalse(LanInterfaceSelector.isTunnelInterface("eth0"))
    }

    @Test fun prefersWifiOverWireguard_regardlessOfOrder() {
        assertEquals("192.168.1.50", LanInterfaceSelector.selectBindAddress(listOf(wireguard(), wifi())))
        assertEquals("192.168.1.50", LanInterfaceSelector.selectBindAddress(listOf(wifi(), wireguard())))
    }

    @Test fun excludesTunnelEvenWhenSiteLocal() {
        // WireGuard up, only the tunnel has an address: nothing TV-reachable -> null.
        assertNull(LanInterfaceSelector.selectBindAddress(listOf(wireguard())))
    }

    @Test fun excludesLoopbackDownAndPublic() {
        val loop = Candidate("lo", "127.0.0.1", true, isLoopback = true, isVirtual = false, isPointToPoint = false, isSiteLocal = false)
        val down = wifi().copy(isUp = false)
        val public = Candidate("eth0", "203.0.113.5", true, isLoopback = false, isVirtual = false, isPointToPoint = false, isSiteLocal = false)
        assertNull(LanInterfaceSelector.selectBindAddress(listOf(loop, down, public)))
    }

    @Test fun prefers192Over10WhenBothLan() {
        val eth10 = Candidate("eth0", "10.0.0.5", true, false, false, false, isSiteLocal = true)
        val wlan192 = wifi("192.168.0.20", "wlan0")
        assertEquals("192.168.0.20", LanInterfaceSelector.selectBindAddress(listOf(eth10, wlan192)))
    }

    @Test fun excludesCellularWan() {
        val cell = Candidate("rmnet0", "10.50.1.2", true, false, false, isPointToPoint = false, isSiteLocal = true)
        assertNull(LanInterfaceSelector.selectBindAddress(listOf(cell)))
        assertEquals("192.168.1.50", LanInterfaceSelector.selectBindAddress(listOf(cell, wifi())))
    }

    @Test fun deterministicForEqualCandidates() {
        val a = wifi("192.168.1.10", "wlan0")
        val b = wifi("192.168.1.11", "wlan1")
        val r1 = LanInterfaceSelector.selectBindAddress(listOf(a, b))
        val r2 = LanInterfaceSelector.selectBindAddress(listOf(b, a))
        assertEquals(r1, r2)
    }
}
