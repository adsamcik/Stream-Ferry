package com.adsamcik.streamferry.core.buffer

/**
 * Memory-only buffering policy (§5).
 *
 * The phone buffers media ONLY in RAM. Two strategies:
 *  1. PASS_THROUGH: small bounded buffer; read upstream chunk, forward downstream, drop. (MVP)
 *  2. ROLLING_PREBUFFER: bounded read-ahead window with eviction. (Phase 9)
 *
 * Forbidden: full-file predownload, disk cache, unbounded arrays/queues/channels, buffering an
 * entire movie. Streaming is a user-initiated, foreground-service-backed activity, so memory pressure
 * NEVER stops the stream: under pressure we only reduce read-ahead *overhead* — shrinking the rolling
 * prebuffer window and, at the extreme, falling back to the minimal-overhead PASS_THROUGH mode (which
 * still streams the same bytes at the same quality). All limits are bounded constants; this object
 * holds pure policy/decision logic.
 */
object MemoryBufferPolicy {

    enum class Strategy { PASS_THROUGH, ROLLING_PREBUFFER }

    // --- Pass-through (always available) ---
    /** Per-transfer copy chunk used to move bytes upstream->downstream. */
    const val PASS_THROUGH_CHUNK_BYTES = 64 * 1024            // 64 KiB
    /** Default soft cap on in-flight bytes for a single pass-through transfer. */
    const val PASS_THROUGH_DEFAULT_BUFFER_BYTES = 2 * 1024 * 1024   // 2 MiB
    /** Hard cap: a pass-through transfer may never hold more than this in RAM. */
    const val PASS_THROUGH_HARD_BUFFER_BYTES = 8 * 1024 * 1024      // 8 MiB

    // --- Rolling prebuffer (phase 9) ---
    /** Default read-ahead window. */
    const val PREBUFFER_DEFAULT_BYTES = 16 * 1024 * 1024      // 16 MiB
    /** Hard ceiling for the read-ahead window regardless of user setting. */
    const val PREBUFFER_HARD_BYTES = 64 * 1024 * 1024         // 64 MiB
    /** Target read-ahead duration; converted to bytes using observed bitrate. */
    const val PREBUFFER_TARGET_SECONDS = 8
    const val PREBUFFER_MAX_SECONDS = 20

    /** Fraction of remaining heap below which we begin shrinking the prebuffer. */
    const val MEMORY_PRESSURE_SHRINK_THRESHOLD = 0.25
    /** Fraction of remaining heap below which we stop *prebuffering* (never the stream) and fall back to pass-through. */
    const val MEMORY_PRESSURE_STOP_THRESHOLD = 0.12

    /**
     * Clamp a user-requested prebuffer size to the allowed [0, hard] range.
     */
    fun clampPrebufferBytes(requestedBytes: Long): Long =
        requestedBytes.coerceIn(0L, PREBUFFER_HARD_BYTES.toLong())

    /**
     * Convert a target duration + observed bitrate (bits/sec) into a byte window, clamped.
     * For 4K/HDR high-bitrate content this naturally produces a large value that the hard cap
     * then limits, so the window represents fewer seconds rather than risking OOM.
     */
    fun windowBytesFor(bitrateBitsPerSec: Long, seconds: Int = PREBUFFER_TARGET_SECONDS): Long {
        if (bitrateBitsPerSec <= 0) return PREBUFFER_DEFAULT_BYTES.toLong()
        val bytes = bitrateBitsPerSec / 8 * seconds.coerceAtMost(PREBUFFER_MAX_SECONDS)
        return clampPrebufferBytes(bytes)
    }

    sealed interface PressureDecision {
        data object KeepCurrent : PressureDecision
        data class Shrink(val newWindowBytes: Long) : PressureDecision
        data object DegradeToPassThrough : PressureDecision
    }

    /**
     * Decide what to do with the prebuffer under the current heap conditions.
     *
     * @param freeHeapFraction freeMemory/maxMemory in [0,1].
     * @param currentWindowBytes the current rolling window size.
     */
    fun onMemoryPressure(freeHeapFraction: Double, currentWindowBytes: Long): PressureDecision = when {
        freeHeapFraction <= MEMORY_PRESSURE_STOP_THRESHOLD -> PressureDecision.DegradeToPassThrough
        freeHeapFraction <= MEMORY_PRESSURE_SHRINK_THRESHOLD ->
            PressureDecision.Shrink((currentWindowBytes / 2).coerceAtLeast(PASS_THROUGH_DEFAULT_BUFFER_BYTES.toLong()))
        else -> PressureDecision.KeepCurrent
    }

    /**
     * Whether a seek to [seekByteOffset] can be served from a rolling window that currently covers
     * [windowStart, windowStart+windowLen). A seek outside the window forces a fresh upstream range
     * request (the old window is evicted), never a disk read.
     */
    fun seekServeableFromWindow(seekByteOffset: Long, windowStart: Long, windowLen: Long): Boolean =
        seekByteOffset in windowStart until (windowStart + windowLen)
}
