package com.adsamcik.streamferry.core.buffer

/**
 * Memory-only proxy copy policy.
 *
 * Production streaming is deliberately pass-through: read one fixed chunk, write it downstream, and
 * reuse the same array. There is no read-ahead queue, rolling seek window, disk cache, or full-file
 * accumulation. HTTP range requests provide seeking without pretending that an unimplemented prebuffer
 * exists.
 */
object MemoryBufferPolicy {

    /** Per-transfer copy chunk used to move bytes upstream->downstream. */
    const val COPY_CHUNK_BYTES = 64 * 1024 // 64 KiB

    /** Flush cadence for long resilient responses; this is not an allocation or read-ahead window. */
    const val FLUSH_INTERVAL_BYTES = 2 * 1024 * 1024 // 2 MiB
}
