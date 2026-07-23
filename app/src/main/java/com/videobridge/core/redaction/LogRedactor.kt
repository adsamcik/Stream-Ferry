package com.videobridge.core.redaction

/**
 * Centralised redaction for logs and diagnostic exports.
 *
 * Security requirement (§13): never log Jellyfin tokens, passwords, Authorization headers,
 * Jellyfin URLs, full phone proxy URLs, upstream stream URLs, auth query params, or PlaySessionIds.
 *
 * All log/diagnostic strings MUST be passed through [redact] before emission. This is pure logic
 * so it is exhaustively unit-testable.
 */
object LogRedactor {

    private const val MASK = "***REDACTED***"

    // Query parameters that carry secrets or identifying material in Jellyfin / proxy URLs.
    private val SENSITIVE_QUERY_KEYS = setOf(
        "api_key", "apikey", "token", "accesstoken", "x-emby-token",
        "x-mediabrowser-token", "playsessionid", "play_session_id",
        "deviceid", "device_id", "userid", "user_id", "password", "pw",
    )

    private val AUTH_HEADER = Regex(
        "(authorization|x-emby-token|x-mediabrowser-token|x-emby-authorization)\\s*[:=]\\s*[^\\r\\n]+",
        RegexOption.IGNORE_CASE,
    )

    // ****** MediaBrowser Token="..." patterns inside header values.
    private val TOKEN_KV = Regex("(?i)(token|api_key|apikey)\\s*=\\s*\"?[A-Za-z0-9._\\-]+\"?")

    /** Redact an entire free-form string (header dumps, messages, exception text). */
    fun redact(input: String?): String {
        if (input.isNullOrEmpty()) return input ?: ""
        var out = input
        out = AUTH_HEADER.replace(out) { m -> "${m.groupValues[1]}: $MASK" }
        out = TOKEN_KV.replace(out, "$1=$MASK")
        out = redactUrls(out)
        return out
    }

    /** Replace any http(s) URL in the text with a host-only, query-stripped form. */
    fun redactUrls(input: String): String {
        val urlRegex = Regex("https?://[^\\s\"'<>]+", RegexOption.IGNORE_CASE)
        return urlRegex.replace(input) { m -> redactUrl(m.value) }
    }

    /**
     * Reduce a URL to scheme + a masked host (+ port) and a path shape, dropping userinfo, query and
     * the concrete path segments that may contain session IDs or media source IDs. The host is masked
     * because a Jellyfin hostname is itself sensitive under this app's security invariant.
     *
     * `http://10.0.0.5:54213/session/abcdef0123/stream?api_key=secret`
     *   -> `http://<host>:<port>/session/<redacted>`
     */
    fun redactUrl(url: String): String {
        return try {
            val u = java.net.URI(url)
            val scheme = u.scheme ?: return MASK
            if (u.host == null) return MASK
            val portPart = if (u.port != -1) ":<port>" else ""
            val pathShape = when {
                u.path.isNullOrEmpty() || u.path == "/" -> ""
                u.path.startsWith("/session/") -> "/session/<redacted>"
                else -> "/<path>"
            }
            "$scheme://<host>$portPart$pathShape"
        } catch (_: Exception) {
            MASK
        }
    }

    /** Strip sensitive query parameters from a query string, preserving non-sensitive keys. */
    fun redactQuery(query: String?): String? {
        if (query.isNullOrEmpty()) return query
        return query.split('&').joinToString("&") { pair ->
            val key = pair.substringBefore('=').lowercase()
            if (SENSITIVE_QUERY_KEYS.any { key.contains(it) }) {
                "${pair.substringBefore('=')}=$MASK"
            } else {
                pair
            }
        }
    }

    /** Mask a token/secret entirely (used when a value must be referenced but not shown). */
    fun mask(@Suppress("UNUSED_PARAMETER") secret: String?): String = MASK
}
