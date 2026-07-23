package com.videobridge.core.net

/**
 * Chooses which local network interface the proxy should bind to and advertise to the TV (§4/§6).
 *
 * Pure-JVM and unit-testable: callers (the Android [NetworkInfoProvider]) enumerate interfaces and
 * pass plain [Candidate] descriptors; this object applies the selection rules.
 *
 * WireGuard / VPN correctness: when a tunnel (WireGuard, OpenVPN, IPsec…) is up to reach a remote
 * Jellyfin, the OS exposes an extra interface (e.g. `tun0`/`wg0`) that often carries a *site-local*
 * address (10.x/172.16-31.x). The TV on the real Wi-Fi LAN cannot route to that tunnel address, so
 * binding the proxy there silently breaks playback. The proxy must bind to the Wi-Fi/Ethernet LAN
 * interface (which the TV shares) while Jellyfin traffic still flows out over the tunnel via normal
 * routing. These rules therefore demote tunnel/point-to-point/VPN interfaces and prefer Wi-Fi.
 *
 * IPv4 only by design: this selects/advertises an IPv4 LAN address for the TV-facing proxy. IPv6-only
 * LAN segments are unsupported (Cast mDNS and DLNA SSDP discovery are IPv4-centric in practice). See
 * docs/KNOWN_LIMITATIONS.md -> Networking constraints.
 */
object LanInterfaceSelector {

    /** Minimal, platform-agnostic view of a network interface + one of its IPv4 addresses. */
    data class Candidate(
        val name: String,
        val ipv4: String,
        val isUp: Boolean,
        val isLoopback: Boolean,
        val isVirtual: Boolean,
        val isPointToPoint: Boolean,
        val isSiteLocal: Boolean,
    )

    /** Interface-name prefixes that denote VPN tunnels or non-LAN transports (never TV-reachable). */
    private val TUNNEL_PREFIXES = listOf(
        "tun", "tap", "wg", "ppp", "ipsec", "utun", "ipsec", "gre", "nordlynx", "wireguard",
    )

    /** Interface-name prefixes that denote cellular/WAN transports (TV is never on these). */
    private val WAN_PREFIXES = listOf("rmnet", "ccmni", "pdp_ip", "rev_rmnet", "clat")

    /** Interface-name prefixes for the LAN transports a TV is typically reachable over. */
    private val LAN_PREFIXES = listOf("wlan", "ap", "swlan", "eth", "en", "br", "wifi")

    fun isTunnelInterface(name: String): Boolean {
        val n = name.lowercase()
        return TUNNEL_PREFIXES.any { n.startsWith(it) }
    }

    fun isWanInterface(name: String): Boolean {
        val n = name.lowercase()
        return WAN_PREFIXES.any { n.startsWith(it) }
    }

    private fun isLanNamed(name: String): Boolean {
        val n = name.lowercase()
        return LAN_PREFIXES.any { n.startsWith(it) }
    }

    /**
     * Pick the best LAN IPv4 address to bind/advertise, or null if no usable LAN interface exists.
     * Higher score wins; ties break deterministically by interface name then address so the result
     * is stable across calls (important: the TV is handed this address).
     */
    fun selectBindAddress(candidates: List<Candidate>): String? =
        candidates
            .filter { it.isUp && !it.isLoopback && it.ipv4.isNotBlank() }
            // Must be a private/LAN address; public addresses are never used for the TV-facing proxy.
            .filter { it.isSiteLocal }
            // Tunnels and point-to-point links reach the *server*, not the local TV — exclude them.
            .filterNot { isTunnelInterface(it.name) || isWanInterface(it.name) || it.isPointToPoint }
            .maxWithOrNull(
                compareBy<Candidate> { score(it) }
                    .thenByDescending { it.name.lowercase() }
                    .thenByDescending { it.ipv4 },
            )
            ?.ipv4

    private fun score(c: Candidate): Int {
        var s = 0
        if (isLanNamed(c.name)) s += 100
        if (!c.isVirtual) s += 10
        // Prefer the conventional home-LAN 192.168/16 range over 10/8 (often VPN/corporate).
        if (c.ipv4.startsWith("192.168.")) s += 5
        return s
    }
}
