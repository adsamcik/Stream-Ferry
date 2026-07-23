package com.videobridge.core.adaptive

/** One entry in the manual quality menu: a specific [bitrateBps] rung, or null for **Auto** (adaptive). */
data class QualityOption(val bitrateBps: Long?, val label: String, val isSelected: Boolean)

/**
 * Pure builder for the manual-quality picker (§9). Given the session's bitrate [ladder][ladderBps] and the
 * currently pinned bitrate ([pinnedBitrateBps] = null when adaptation is on), it produces an ordered menu:
 * an **Auto** entry first, then each rung best-quality-first, with the active entry marked selected.
 *
 * Letting the user pick a rung never invents a bitrate — the choices are exactly the ladder the adaptive
 * controller uses — so a manual pick and Auto share the same, testable set of quality levels. Pure JVM.
 */
object QualityMenu {
    fun options(ladderBps: List<Long>, pinnedBitrateBps: Long?): List<QualityOption> {
        val rungs = ladderBps.filter { it > 0 }.distinct().sorted()
        // Snap the pinned request to the rung the engine would actually stream (highest <= request), so the
        // selected entry matches what's playing even if a caller passes an off-ladder value.
        val pinnedRung = pinnedBitrateBps?.let { rungs.getOrNull(BitrateLadder.indexAtOrBelow(rungs, it)) }
        val auto = QualityOption(bitrateBps = null, label = "Auto", isSelected = pinnedBitrateBps == null)
        val rungOptions = rungs.asReversed().map { bps ->
            QualityOption(bitrateBps = bps, label = "${formatMbps(bps)} Mbps", isSelected = bps == pinnedRung)
        }
        return listOf(auto) + rungOptions
    }

    /** Bits/sec -> "X.X" Mbps, rounded to 0.1 Mbps (integer math; no locale/format dependency). */
    private fun formatMbps(bps: Long): String {
        val tenths = (bps + 50_000) / 100_000
        return "${tenths / 10}.${tenths % 10}"
    }
}
