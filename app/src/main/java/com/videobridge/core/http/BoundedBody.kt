package com.videobridge.core.http

import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Reads an untrusted upstream body into memory with a hard byte cap, so a semi-trusted Jellyfin
 * server (or a hostile DLNA renderer) cannot return an unbounded body and exhaust the phone's heap
 * (§16 availability). Returns null when the stream exceeds [maxBytes]; callers should treat that as
 * an upstream error (e.g. respond `502`).
 */
object BoundedBody {
    private const val CHUNK = 8 * 1024
    private const val INITIAL_CAPACITY = 64 * 1024

    /**
     * Read at most [maxBytes] from [input]. Returns the bytes read, or null if the stream contains
     * more than [maxBytes] (i.e. it overflowed the cap). The stream is read but not closed.
     */
    fun readAtMost(input: InputStream, maxBytes: Int): ByteArray? {
        require(maxBytes >= 0) { "maxBytes must be >= 0" }
        val out = ByteArrayOutputStream(minOf(maxBytes, INITIAL_CAPACITY).coerceAtLeast(16))
        val chunk = ByteArray(CHUNK)
        var total = 0L
        while (true) {
            val n = input.read(chunk)
            if (n < 0) break
            total += n
            if (total > maxBytes) return null // overflowed the cap: reject without keeping the bytes
            out.write(chunk, 0, n)
        }
        return out.toByteArray()
    }
}
