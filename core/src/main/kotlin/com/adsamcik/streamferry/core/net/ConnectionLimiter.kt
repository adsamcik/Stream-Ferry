package com.adsamcik.streamferry.core.net

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Bounds the number of concurrent proxy connections to resist a hostile LAN peer exhausting host
 * resources — coroutines, file descriptors, upstream sockets, buffers — by opening many connections
 * (§16 availability / DoS). Enforces a global cap and a per-client-IP cap. Pure-JVM and thread-safe:
 * the proxy calls [tryAcquire] for each accepted socket and [release] when that request finishes.
 *
 * The caps are generous enough never to bother a single real TV (HLS players open a few parallel
 * segment connections) while bounding a flood from a malicious peer.
 */
class ConnectionLimiter(
    val maxTotal: Int = DEFAULT_MAX_TOTAL,
    val maxPerIp: Int = DEFAULT_MAX_PER_IP,
) {
    init {
        require(maxTotal >= 1) { "maxTotal must be >= 1" }
        require(maxPerIp >= 1) { "maxPerIp must be >= 1" }
    }

    private val total = AtomicInteger(0)
    private val perIp = ConcurrentHashMap<String, Int>()

    /**
     * Try to reserve a connection slot for [ip]. Returns true if a slot was granted (the caller must
     * later [release] it), or false if the global or per-IP cap is already reached (the caller must
     * reject/close the connection).
     */
    fun tryAcquire(ip: String): Boolean {
        // Reserve a global slot first.
        while (true) {
            val cur = total.get()
            if (cur >= maxTotal) return false
            if (total.compareAndSet(cur, cur + 1)) break
        }
        // Reserve a per-IP slot atomically; roll back the global slot if the per-IP cap is hit.
        var granted = false
        perIp.compute(ip) { _, current ->
            val c = current ?: 0
            if (c >= maxPerIp) {
                current // unchanged (non-null here); slot not granted
            } else {
                granted = true
                c + 1
            }
        }
        if (!granted) total.decrementAndGet()
        return granted
    }

    /** Release a slot previously granted by [tryAcquire] for [ip]. */
    fun release(ip: String) {
        perIp.compute(ip) { _, current ->
            when {
                current == null -> null
                current <= 1 -> null // drop the entry at zero so the map can't grow unbounded
                else -> current - 1
            }
        }
        total.updateAndGet { if (it > 0) it - 1 else 0 }
    }

    fun activeTotal(): Int = total.get()

    fun activeFor(ip: String): Int = perIp[ip] ?: 0

    companion object {
        const val DEFAULT_MAX_TOTAL = 64
        const val DEFAULT_MAX_PER_IP = 16
    }
}
