package com.adsamcik.streamferry.data.proxy

import java.io.InputStream

/**
 * Minimal, defensive HTTP request-line + header reader for the local proxy. Bounds the request size
 * to resist abuse from a hostile LAN client (§17). Only the fields we need are parsed.
 */
data class RequestLine(
    val method: String,
    val path: String,
    val rangeHeader: String?,
    val knownTotalLength: Long? = null,
) {
    companion object {
        private const val MAX_REQUEST_BYTES = 8 * 1024
        private const val MAX_LINE = 4096

        fun read(input: InputStream): RequestLine? {
            val first = readLine(input) ?: return null
            val parts = first.split(' ')
            if (parts.size < 2) return null
            val method = parts[0].uppercase()
            val rawTarget = parts[1]
            var range: String? = null
            var total = 0
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                total += line.length
                if (total > MAX_REQUEST_BYTES) return null
                val idx = line.indexOf(':')
                if (idx <= 0) continue
                val key = line.substring(0, idx).trim().lowercase()
                val value = line.substring(idx + 1).trim()
                if (key == "range") range = value
            }
            return RequestLine(method, rawTarget, range)
        }

        private fun readLine(input: InputStream): String? {
            val sb = StringBuilder()
            var prev = -1
            var count = 0
            while (true) {
                val c = input.read()
                if (c == -1) return if (sb.isEmpty()) null else sb.toString()
                if (c == '\n'.code) {
                    if (prev == '\r'.code && sb.isNotEmpty()) sb.deleteCharAt(sb.length - 1)
                    return sb.toString()
                }
                sb.append(c.toChar())
                prev = c
                if (++count > MAX_LINE) return null
            }
        }
    }
}
