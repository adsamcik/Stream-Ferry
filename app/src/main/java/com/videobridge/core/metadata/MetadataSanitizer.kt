package com.videobridge.core.metadata

/**
 * Bounds text that leaves the app as media metadata or is shown by system UI.
 *
 * Media titles originate with a server or local filename, so they must not be allowed to inject
 * control characters into a receiver/notification or make an unbounded Cast message. The cap is
 * measured in UTF-8 bytes rather than UTF-16 code units because Cast transports UTF-8 JSON.
 */
object MetadataSanitizer {

    /** Kept deliberately small relative to Cast's message-size limit. */
    const val MAX_RECEIVER_TITLE_UTF8_BYTES = 512

    const val FALLBACK_TITLE = "Video Bridge"

    /** A safe, non-empty title for Cast/DLNA metadata and Android media surfaces. */
    fun receiverTitle(raw: String?): String =
        normalize(raw, MAX_RECEIVER_TITLE_UTF8_BYTES).ifBlank { FALLBACK_TITLE }

    /**
     * Replaces control characters with normalized spaces, collapses whitespace, repairs malformed
     * surrogate pairs, and returns a prefix that fits in [maxUtf8Bytes].
     */
    fun normalize(raw: String?, maxUtf8Bytes: Int): String {
        require(maxUtf8Bytes >= 0) { "maxUtf8Bytes must be non-negative" }
        if (raw.isNullOrEmpty() || maxUtf8Bytes == 0) return ""

        val out = StringBuilder(minOf(raw.length, maxUtf8Bytes))
        var utf8Bytes = 0
        var pendingSpace = false
        var index = 0
        while (index < raw.length) {
            val first = raw[index]
            val (codePoint, width) = when {
                Character.isHighSurrogate(first) && index + 1 < raw.length && Character.isLowSurrogate(raw[index + 1]) ->
                    Character.toCodePoint(first, raw[index + 1]) to 2
                Character.isSurrogate(first) -> REPLACEMENT_CHARACTER to 1
                else -> first.code to 1
            }
            index += width

            val type = Character.getType(codePoint)
            if (
                type == Character.CONTROL.toInt() ||
                Character.isWhitespace(codePoint) ||
                Character.isSpaceChar(codePoint)
            ) {
                pendingSpace = out.isNotEmpty()
                continue
            }

            val requiredBytes = utf8ByteCount(codePoint) + if (pendingSpace) 1 else 0
            if (utf8Bytes + requiredBytes > maxUtf8Bytes) break
            if (pendingSpace) {
                out.append(' ')
                utf8Bytes += 1
                pendingSpace = false
            }
            out.appendCodePoint(codePoint)
            utf8Bytes += utf8ByteCount(codePoint)
        }
        return out.toString()
    }

    private fun utf8ByteCount(codePoint: Int): Int = when {
        codePoint <= 0x7f -> 1
        codePoint <= 0x7ff -> 2
        codePoint <= 0xffff -> 3
        else -> 4
    }

    private const val REPLACEMENT_CHARACTER = 0xfffd
}
