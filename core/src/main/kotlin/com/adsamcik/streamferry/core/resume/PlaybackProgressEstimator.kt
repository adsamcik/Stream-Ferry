package com.adsamcik.streamferry.core.resume

import java.util.TreeMap
import kotlin.math.ceil

enum class ProgressConfidence { CLOCK, DELIVERY }

/** An estimate is never a renderer confirmation or evidence of completion. No addresses or media data. */
data class ProgressEstimate(
    val positionSeconds: Long,
    val anchorPositionSeconds: Long,
    val anchorAgeMillis: Long,
    val uncertaintySeconds: Long,
    val confidence: ProgressConfidence,
) {
    fun isValid() = anchorPositionSeconds >= 0 && positionSeconds >= anchorPositionSeconds &&
        anchorAgeMillis in 0..PlaybackProgressEstimator.MAX_DELIVERY_AGE_MS &&
        uncertaintySeconds in 0..120 && positionSeconds - anchorPositionSeconds <= 1_200

    /** Prefer replaying a little material to skipping material that may not have been watched. */
    fun recoveryPositionSeconds(): Long = maxOf(anchorPositionSeconds, positionSeconds - maxOf(5L, uncertaintySeconds))
}

/** Sleep-aware extrapolation, bounded by age and contiguous delivered media, independently of UI ticks. */
class PlaybackProgressEstimator {
    private var anchorPositionMs = 0L
    private var anchorAtMs: Long? = null
    private var playing = false
    private var rate = 1.0
    private val delivered = TreeMap<Long, Long>()
    private var deliveryAtMs: Long? = null
    private var bufferLeadMs: Long? = null

    @Synchronized fun reset() {
        anchorAtMs = null
        playing = false
        delivered.clear()
        deliveryAtMs = null
        bufferLeadMs = null
        lastDeliveredEndMs = null
    }

    @Synchronized fun confirm(positionSeconds: Long, isPlaying: Boolean, nowMs: Long, playbackRate: Double = 1.0) {
        if (positionSeconds < 0 || positionSeconds > Long.MAX_VALUE / 1000 || nowMs < 0) return
        val next = positionSeconds * 1000
        val oldAt = anchorAtMs
        val expected = if (oldAt != null && playing) anchorPositionMs + ((nowMs - oldAt).coerceAtLeast(0) * rate).toLong() else anchorPositionMs
        // A TV-remote seek can arrive without a local seek command. Discard obsolete buffer evidence.
        if (oldAt != null && kotlin.math.abs(next - expected) > 15_000) {
            delivered.clear()
            deliveryAtMs = null
            bufferLeadMs = null
            lastDeliveredEndMs = null
        }
        anchorPositionMs = next
        anchorAtMs = nowMs
        playing = isPlaying && playbackRate.isFinite() && playbackRate in 0.25..4.0
        rate = playbackRate
        contiguousEnd(next)?.let { end ->
            val lead = (end - next).coerceIn(0, 120_000)
            bufferLeadMs = bufferLeadMs?.let { (it * 3 + lead) / 4 } ?: lead
        }
        delivered.entries.removeIf { it.value < next - 30_000 }
    }

    /** Pause/buffering/seek freezes inference until the next real receiver report. */
    @Synchronized fun hold() { playing = false }

    @Synchronized fun delivered(startMs: Long, endMs: Long, nowMs: Long) {
        if (startMs < 0 || endMs <= startMs || nowMs < 0) return
        var start = startMs
        var end = endMs
        delivered.floorEntry(start)?.takeIf { it.value >= start }?.let {
            start = it.key
            end = maxOf(end, it.value)
            delivered.remove(it.key)
        }
        var next = delivered.ceilingEntry(start)
        while (next != null && next.key <= end) {
            end = maxOf(end, next.value)
            delivered.remove(next.key)
            next = delivered.ceilingEntry(start)
        }
        delivered[start] = end
        while (delivered.size > 512) delivered.pollFirstEntry()
        // Duplicate/retried downloads must not indefinitely rejuvenate old delivery evidence.
        if (end > (lastDeliveredEndMs ?: -1L)) {
            lastDeliveredEndMs = end
            deliveryAtMs = nowMs
        }
    }

    private var lastDeliveredEndMs: Long? = null

    @Synchronized fun estimate(nowMs: Long, durationSeconds: Long?): ProgressEstimate? {
        val at = anchorAtMs ?: return null
        val age = nowMs - at
        if (!playing || age <= 0 || age > MAX_DELIVERY_AGE_MS) return null
        val end = contiguousEnd(anchorPositionMs)
        val deliveryAge = deliveryAtMs?.let { nowMs - it }
        val supported = end != null && end > anchorPositionMs && deliveryAge != null && deliveryAge in 0..30_000
        if (!supported && age > MAX_CLOCK_AGE_MS) return null
        var position = anchorPositionMs + (age * rate).toLong()
        if (end != null) position = minOf(position, end)
        durationSeconds?.takeIf { it > 0 && it <= Long.MAX_VALUE / 1000 }?.let { position = minOf(position, it * 1000) }
        if (position <= anchorPositionMs) return null
        // Unobserved pauses become increasingly plausible. Calibrated buffering widens, never shrinks,
        // the recovery margin. A timer or a download cannot manufacture a fresh confirmation.
        val uncertainty = maxOf(5L, ceil(age / 10_000.0).toLong(), if (supported) (bufferLeadMs ?: 0) / 4000 else 0)
        return ProgressEstimate(position / 1000, anchorPositionMs / 1000, age, uncertainty,
            if (supported) ProgressConfidence.DELIVERY else ProgressConfidence.CLOCK)
    }

    private fun contiguousEnd(positionMs: Long): Long? = delivered.floorEntry(positionMs)?.value?.takeIf { it >= positionMs }

    companion object {
        const val MAX_CLOCK_AGE_MS = 30_000L
        const val MAX_DELIVERY_AGE_MS = 300_000L
    }
}
