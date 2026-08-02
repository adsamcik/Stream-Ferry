package com.adsamcik.streamferry.core.dlna

import java.util.Locale

/**
 * Deduplicates description candidates without letting one bad advertisement permanently suppress a
 * later usable LOCATION for the same renderer. The separate discovery limiter remains the global
 * flood bound; this registry adds a small per-renderer candidate cap.
 */
class SsdpCandidateRegistry(
    private val maxCandidatesPerRenderer: Int = DEFAULT_MAX_CANDIDATES_PER_RENDERER,
) {
    enum class Decision {
        ACCEPT,
        DUPLICATE,
        RENDERER_LIMIT,
    }

    private val candidateKeysByRenderer = HashMap<String, MutableSet<String>>()

    init {
        require(maxCandidatesPerRenderer > 0)
    }

    fun register(usn: String, location: String, sourceIp: String? = null): Decision {
        val identity = usn.trim().lowercase(Locale.ROOT)
        val normalizedLocation = sourceIp.orEmpty() + "\u0000" + location.trim()
        val candidateKeys = candidateKeysByRenderer.getOrPut(identity) { LinkedHashSet() }
        if (normalizedLocation in candidateKeys) return Decision.DUPLICATE
        if (candidateKeys.size >= maxCandidatesPerRenderer) return Decision.RENDERER_LIMIT
        candidateKeys += normalizedLocation
        return Decision.ACCEPT
    }

    companion object {
        const val DEFAULT_MAX_CANDIDATES_PER_RENDERER = 2
    }
}
