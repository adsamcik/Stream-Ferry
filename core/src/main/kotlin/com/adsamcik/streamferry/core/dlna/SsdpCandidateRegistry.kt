package com.adsamcik.streamferry.core.dlna

import java.util.Locale

/**
 * Deduplicates description candidates without letting one bad advertisement permanently suppress a
 * later usable LOCATION for the same renderer. The separate discovery limiter remains the global
 * flood bound; this registry adds a small per-renderer candidate cap.
 */
class SsdpCandidateRegistry(
    private val maxCandidatesPerRenderer: Int = DEFAULT_MAX_CANDIDATES_PER_RENDERER,
    private val maxCandidatesTotal: Int = SsdpDiscoveryLimiter.DEFAULT_MAX_DESCRIBES,
) {
    enum class Decision {
        ACCEPT,
        DUPLICATE,
        RENDERER_LIMIT,
        SCAN_LIMIT,
    }

    private val candidateKeysByRenderer = HashMap<String, MutableSet<String>>()
    private var candidateCount = 0

    init {
        require(maxCandidatesPerRenderer > 0)
        require(maxCandidatesTotal > 0)
    }

    fun register(usn: String, location: String, sourceIp: String? = null): Decision {
        val identity = usn.trim().lowercase(Locale.ROOT)
        val normalizedLocation = sourceIp.orEmpty() + "\u0000" + location.trim()
        val candidateKeys = candidateKeysByRenderer[identity]
        if (normalizedLocation in candidateKeys.orEmpty()) return Decision.DUPLICATE
        if (candidateKeys != null && candidateKeys.size >= maxCandidatesPerRenderer) return Decision.RENDERER_LIMIT
        if (candidateCount >= maxCandidatesTotal) return Decision.SCAN_LIMIT
        val acceptedKeys = candidateKeys ?: LinkedHashSet<String>().also {
            candidateKeysByRenderer[identity] = it
        }
        acceptedKeys += normalizedLocation
        candidateCount += 1
        return Decision.ACCEPT
    }

    companion object {
        const val DEFAULT_MAX_CANDIDATES_PER_RENDERER = 2
    }
}
