package com.adsamcik.streamferry.core.hls

import java.security.SecureRandom
import java.util.Base64

/**
 * Per-session, in-memory map between an **opaque** reference and a real upstream HLS resource URL
 * (a media playlist, segment, key, or subtitle) (§7).
 *
 * When the proxy rewrites an HLS playlist it replaces every Jellyfin URL with a phone proxy URL of the
 * form `…/stream?seg=<opaque>`. The opaque token is a fresh 128-bit random value — it is NOT derived
 * from the URL — so the TV can never reverse it back to the Jellyfin URL or its token. The mapping is
 * kept only here in RAM and is cleared when the session ends.
 *
 * Bounded: at most [maxEntries] mappings are retained (oldest evicted), so a long or refreshing live
 * playlist cannot grow memory without limit. The same URL always maps to the same opaque within a
 * session (so playlist refreshes are stable and the TV's cache works).
 *
 * Pure JVM + thread-safe; holds only opaque tokens and (secret) URLs that are never logged.
 */
class HlsSegmentRegistry(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val random: SecureRandom = SecureRandom(),
) {
    init {
        require(maxEntries >= 1) { "maxEntries must be >= 1" }
    }

    private val byUrl = HashMap<String, String>()
    private val byOpaque = object : LinkedHashMap<String, String>(64, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>): Boolean {
            val over = size > maxEntries
            if (over) byUrl.remove(eldest.value)
            return over
        }
    }

    /** Map [upstreamUrl] to a stable opaque token, creating one on first use. */
    @Synchronized
    fun encode(upstreamUrl: String): String {
        byUrl[upstreamUrl]?.let { return it }
        val opaque = newOpaque()
        byOpaque[opaque] = upstreamUrl // may evict the eldest entry (and clean byUrl)
        byUrl[upstreamUrl] = opaque
        return opaque
    }

    /** Resolve an opaque token back to the real upstream URL, or null if unknown/evicted. */
    @Synchronized
    fun resolve(opaque: String): String? = byOpaque[opaque]

    @Synchronized
    fun size(): Int = byOpaque.size

    private fun newOpaque(): String {
        val bytes = ByteArray(OPAQUE_BYTES)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    companion object {
        const val DEFAULT_MAX_ENTRIES = 8192
        const val OPAQUE_BYTES = 16 // 128-bit opaque reference, not derived from the URL
    }
}
