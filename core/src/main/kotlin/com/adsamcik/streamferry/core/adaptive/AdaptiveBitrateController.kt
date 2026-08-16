package com.adsamcik.streamferry.core.adaptive

/**
 * Default streaming bitrate ladder (bits/sec) and helpers (§9 quality adaptation).
 *
 * The ladder is the set of `MaxStreamingBitrate` values the app may request from Jellyfin. Adaptation
 * moves between rungs; it never invents an arbitrary bitrate, so each step is a sensible, testable
 * quality level. Pure data — no Android, no secrets.
 */
object BitrateLadder {

    /** A broad ladder from low-DSL to high-bitrate 4K. */
    val DEFAULT: List<Long> = listOf(
        720_000L,      // ~0.7 Mbps  (very constrained)
        1_500_000L,    // ~1.5 Mbps  (SD)
        3_000_000L,    // ~3 Mbps    (720p)
        4_000_000L,    // ~4 Mbps
        6_000_000L,    // ~6 Mbps    (1080p)
        8_000_000L,    // ~8 Mbps
        12_000_000L,   // ~12 Mbps
        20_000_000L,   // ~20 Mbps   (1080p high / 4K low)
        40_000_000L,   // ~40 Mbps   (4K)
    )

    /**
     * Build a ladder for a source of [sourceBitrateBps]. Rungs above the source are pointless (the
     * server can't produce more than the original for direct play), so the ladder is capped at the
     * source bitrate, which is added as the top rung when known.
     */
    fun forSource(sourceBitrateBps: Long?, base: List<Long> = DEFAULT): List<Long> {
        val cap = sourceBitrateBps?.takeIf { it > 0 }
        val rungs = if (cap == null) base else base.filter { it < cap } + cap
        return rungs.filter { it > 0 }.distinct().sorted().ifEmpty { listOf(cap ?: base.first()) }
    }

    /** Index of the highest rung whose bitrate is `<= target`, or 0 when even the lowest exceeds it. */
    fun indexAtOrBelow(ladder: List<Long>, target: Long): Int {
        var idx = 0
        for (i in ladder.indices) if (ladder[i] <= target) idx = i else break
        return idx
    }
}

/**
 * Intelligent, gradual adaptive-bitrate controller (§9).
 *
 * During playback the local proxy relays bytes from Jellyfin to the TV. If the phone→server link
 * cannot sustain the current quality the TV rebuffers. This controller watches two signals over a
 * rolling window of **at least 30 seconds** and decides whether to change the requested quality:
 *
 *  1. **Average throughput** — bytes the proxy actually delivered, averaged over the window. This is
 *     the "average possible speed" the user asked us to measure.
 *  2. **Rebuffer events** — explicit stalls reported by the renderer (e.g. Cast `BUFFERING`).
 *
 * Behaviour:
 *  - **No decision until ≥ 30 s of data** has accumulated (warm-up), and at most one change per
 *    [Config.minSwitchIntervalMillis] (hysteresis prevents oscillation).
 *  - **Step down** when the window shows rebuffering or the average can't sustain the current rung,
 *    going straight to the highest rung the measured throughput can support (with safety headroom) —
 *    intelligent, not just one blind notch — but always at least one rung so we make progress.
 *  - **Step up** one rung at a time, and only when the average comfortably exceeds the next rung
 *    (headroom factor) with zero rebuffering — cautious, so we don't immediately re-stall.
 *  - After a change the window is reset, so the next decision is again based on a fresh ≥ 30 s sample
 *    at the new quality.
 *
 * Pure JVM + deterministic (injected [clock]); holds only numbers, so it is safe to log and is fully
 * unit-tested. Throughput is bucketed per [Config.bucketMillis] so memory is bounded regardless of
 * how often bytes are recorded.
 */
class AdaptiveBitrateController(
    ladderBps: List<Long>,
    startBitrateBps: Long? = null,
    val config: Config = Config(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class Config(
        /** Averaging window length. Must be ≥ 30 s per the product requirement. */
        val windowMillis: Long = 30_000,
        /** Minimum data coverage before any decision is made. */
        val minObservationMillis: Long = 30_000,
        /** Minimum time between two quality changes (hysteresis). */
        val minSwitchIntervalMillis: Long = 30_000,
        /** Throughput bucket granularity; bounds memory and smooths spikes. */
        val bucketMillis: Long = 1_000,
        /** A new (lower) rung must sit at/below this fraction of measured throughput. */
        val downSafetyFactor: Double = 0.8,
        /** Average must exceed the next rung by this factor before stepping up. */
        val upHeadroomFactor: Double = 1.3,
        /** This many rebuffers within the window forces a down-shift consideration. */
        val rebufferDownshiftCount: Int = 1,
    ) {
        init {
            require(windowMillis >= 30_000) { "windowMillis must be >= 30s" }
            require(minObservationMillis >= 30_000) { "minObservationMillis must be >= 30s" }
            require(bucketMillis in 1..windowMillis) { "bucketMillis out of range" }
            require(downSafetyFactor in 0.1..1.0) { "downSafetyFactor out of range" }
            require(upHeadroomFactor >= 1.0) { "upHeadroomFactor must be >= 1.0" }
            require(rebufferDownshiftCount >= 1) { "rebufferDownshiftCount must be >= 1" }
        }
    }

    val ladder: List<Long> = ladderBps.filter { it > 0 }.distinct().sorted()
        .ifEmpty { error("ladder must contain at least one positive bitrate") }

    @Volatile
    var currentIndex: Int = startBitrateBps?.let { BitrateLadder.indexAtOrBelow(ladder, it) }
        ?: ladder.lastIndex
        private set

    val currentBitrateBps: Long get() = ladder[currentIndex]

    enum class Direction { UP, DOWN }

    sealed interface Decision {
        val measuredThroughputBps: Long

        /** No change this evaluation. [reason] is a short, secret-free explanation. */
        data class Hold(override val measuredThroughputBps: Long, val reason: String) : Decision

        /** Apply [newBitrateBps] (= `ladder[newIndex]`). The caller must call [noteApplied] on success. */
        data class ChangeBitrate(
            val direction: Direction,
            val newBitrateBps: Long,
            val newIndex: Int,
            override val measuredThroughputBps: Long,
            val reason: String,
        ) : Decision
    }

    private class Bucket(val startMillis: Long, var bytes: Long)

    private val lock = Any()
    private val buckets = ArrayDeque<Bucket>()
    private val rebuffers = ArrayDeque<Long>()
    private val startMillis = clock()

    // Offset back by one switch interval so the FIRST decision is gated only by the warm-up window;
    // the switch-interval cooldown then applies to every change after the first.
    private var lastSwitchMillis = startMillis - config.minSwitchIntervalMillis

    /**
     * Record [bytes] delivered downstream at [nowMillis] (called by the proxy's byte pump). Thread-safe:
     * the proxy records bytes from its own IO thread while the engine evaluates from another.
     */
    fun recordThroughput(bytes: Long, nowMillis: Long = clock()) {
        synchronized(lock) {
            if (bytes <= 0) return
            prune(nowMillis)
            val bucketStart = nowMillis - (nowMillis % config.bucketMillis)
            val last = buckets.lastOrNull()
            if (last != null && last.startMillis == bucketStart) {
                last.bytes += bytes
            } else {
                buckets.addLast(Bucket(bucketStart, bytes))
            }
        }
    }

    /** Record a renderer stall / rebuffering event at [nowMillis]. */
    fun recordRebuffer(nowMillis: Long = clock()) = synchronized(lock) {
        prune(nowMillis)
        rebuffers.addLast(nowMillis)
    }

    /** Rebuffer events retained in the current window — used to detect a "stall storm" (server transcode
     *  that can't keep up even at the lowest rung, so the caller can escalate to on-device transcode). */
    fun rebufferCountInWindow(nowMillis: Long = clock()): Int = synchronized(lock) {
        prune(nowMillis)
        rebuffers.size
    }

    /** Average delivered throughput (bits/sec) over the retained window, or 0 with no data. */
    fun averageThroughputBps(nowMillis: Long = clock()): Long = synchronized(lock) {
        prune(nowMillis)
        val oldest = buckets.firstOrNull()?.startMillis ?: return 0L
        val measuredMillis = (nowMillis - oldest).coerceAtMost(config.windowMillis)
        if (measuredMillis <= 0) return 0L
        val totalBytes = buckets.sumOf { it.bytes }
        return totalBytes * 8 * 1000 / measuredMillis
    }

    /**
     * Evaluate whether to change quality. Pure read; on a [Decision.ChangeBitrate] the caller applies
     * the new bitrate to the live stream and then calls [noteApplied].
     */
    fun evaluate(nowMillis: Long = clock()): Decision = synchronized(lock) {
        prune(nowMillis)
        val oldest = buckets.firstOrNull()?.startMillis
            ?: return Decision.Hold(0L, "no throughput data yet")

        val measuredMillis = (nowMillis - oldest).coerceAtMost(config.windowMillis)
        if (measuredMillis < config.minObservationMillis || nowMillis - startMillis < config.minObservationMillis) {
            return Decision.Hold(unsafeAverage(nowMillis), "warming up (<30s of data)")
        }
        if (nowMillis - lastSwitchMillis < config.minSwitchIntervalMillis) {
            return Decision.Hold(unsafeAverage(nowMillis), "cooldown after last change")
        }

        val avgBps = unsafeAverage(nowMillis)
        val rebufferCount = rebuffers.size
        val current = ladder[currentIndex]
        val needDown = rebufferCount >= config.rebufferDownshiftCount || avgBps < current

        if (needDown && currentIndex > 0) {
            val budget = (avgBps * config.downSafetyFactor).toLong()
            val targetIndex = BitrateLadder.indexAtOrBelow(ladder, budget)
            val newIndex = targetIndex.coerceIn(0, currentIndex - 1)
            val why = if (rebufferCount > 0) {
                "rebuffering x$rebufferCount; avg ${mbps(avgBps)} Mbps"
            } else {
                "avg ${mbps(avgBps)} Mbps below current ${mbps(current)} Mbps"
            }
            return Decision.ChangeBitrate(Direction.DOWN, ladder[newIndex], newIndex, avgBps, why)
        }

        if (!needDown && rebufferCount == 0 && currentIndex < ladder.lastIndex) {
            val next = ladder[currentIndex + 1]
            if (avgBps >= next * config.upHeadroomFactor) {
                return Decision.ChangeBitrate(
                    Direction.UP, next, currentIndex + 1, avgBps,
                    "stable; avg ${mbps(avgBps)} Mbps supports ${mbps(next)} Mbps",
                )
            }
        }

        return Decision.Hold(avgBps, "stable at ${mbps(current)} Mbps (avg ${mbps(avgBps)} Mbps)")
    }

    /** Commit a change returned by [evaluate]: move to [newIndex] and reset the measurement window. */
    fun noteApplied(newIndex: Int, nowMillis: Long = clock()) = synchronized(lock) {
        currentIndex = newIndex.coerceIn(0, ladder.lastIndex)
        lastSwitchMillis = nowMillis
        buckets.clear()
        rebuffers.clear()
    }

    /** Average over the window assuming [lock] is already held (used inside [evaluate]). */
    private fun unsafeAverage(nowMillis: Long): Long {
        val oldest = buckets.firstOrNull()?.startMillis ?: return 0L
        val measuredMillis = (nowMillis - oldest).coerceAtMost(config.windowMillis)
        if (measuredMillis <= 0) return 0L
        return buckets.sumOf { it.bytes } * 8 * 1000 / measuredMillis
    }

    private fun prune(nowMillis: Long) {
        val cutoff = nowMillis - config.windowMillis
        while (buckets.isNotEmpty() && buckets.first().startMillis < cutoff) buckets.removeFirst()
        while (rebuffers.isNotEmpty() && rebuffers.first() < cutoff) rebuffers.removeFirst()
    }

    private fun mbps(bps: Long): String {
        val tenths = (bps + 50_000) / 100_000 // round to 0.1 Mbps
        return "${tenths / 10}.${tenths % 10}"
    }
}
