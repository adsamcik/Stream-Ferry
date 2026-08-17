package com.adsamcik.streamferry.core.chapter

/**
 * Pure helper for the seek-preview scrubber: given chapter ("section") start times and a scrub
 * position, find which chapter contains it. Framework-free so it is unit-tested in the sandbox.
 *
 * Returns the index of the **last** chapter whose start is at or before [positionSeconds] (the chapter
 * currently being scrubbed through), or `null` when there are no chapters or the position precedes the
 * first chapter. [startsAscending] MUST be sorted ascending, as Jellyfin returns chapters.
 */
fun chapterIndexForPosition(startsAscending: List<Long>, positionSeconds: Long): Int? {
    if (startsAscending.isEmpty()) return null
    if (positionSeconds < startsAscending.first()) return null
    // Binary search for the rightmost start <= positionSeconds.
    var lo = 0
    var hi = startsAscending.size - 1
    var ans = 0
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        if (startsAscending[mid] <= positionSeconds) {
            ans = mid
            lo = mid + 1
        } else {
            hi = mid - 1
        }
    }
    return ans
}
