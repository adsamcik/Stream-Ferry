package com.adsamcik.streamferry.core.dlna

/**
 * Bounds the work a single SSDP discovery scan will do, so a hostile LAN device that floods M-SEARCH
 * replies with many distinct USNs/LOCATIONs can't make the control point issue an unbounded number of
 * device-description HTTP fetches (each of which costs a timeout + a bounded read) — §6/§16. Caps the
 * total number of `describe()` calls per scan and the number per source IP. Pure-JVM; used from the
 * single discovery thread, so it is intentionally not synchronized.
 */
class SsdpDiscoveryLimiter(
    val maxDescribes: Int = DEFAULT_MAX_DESCRIBES,
    val maxPerSource: Int = DEFAULT_MAX_PER_SOURCE,
) {
    private var totalDescribes = 0
    private val perSource = HashMap<String, Int>()

    /**
     * Whether a `describe()` for a renderer advertised from [sourceIp] is permitted this scan. Counts
     * the call when it returns true. The total cap is the real bound (UDP source IPs can be spoofed);
     * the per-source cap is a refinement against an unspoofed flood.
     */
    fun allowDescribe(sourceIp: String): Boolean {
        if (totalDescribes >= maxDescribes) return false
        val used = perSource.getOrDefault(sourceIp, 0)
        if (used >= maxPerSource) return false
        perSource[sourceIp] = used + 1
        totalDescribes += 1
        return true
    }

    companion object {
        const val DEFAULT_MAX_DESCRIBES = 16
        const val DEFAULT_MAX_PER_SOURCE = 4
    }
}
