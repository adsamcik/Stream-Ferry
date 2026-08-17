package com.adsamcik.streamferry.core.net

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Origin boundary for Jellyfin media requests that can carry a session token.
 *
 * A server-controlled media locator is allowed to change paths, queries and fragments, but never the
 * configured server authority: scheme, canonical host and effective port are all pinned. This makes a
 * relative HLS URI safe to resolve while rejecting protocol-relative, cross-origin and non-HTTP(S)
 * locators before a caller can attach an Authorization header.
 *
 * This is deliberately an authority policy, not a DNS resolver. HTTPS certificate validation remains
 * the transport-level protection for a configured HTTPS host.
 */
class TrustedMediaOriginPolicy private constructor(
    private val baseUrl: HttpUrl,
    private val origin: Origin,
) {
    /** True only for an HTTP(S) URL on the pinned scheme, host and effective port without user-info. */
    fun isTrusted(url: HttpUrl): Boolean =
        url.scheme == origin.scheme &&
            url.host == origin.host &&
            url.port == origin.port &&
            url.username.isEmpty() &&
            url.password.isEmpty()

    /**
     * Parse an already-absolute URL and return it only when it remains on the pinned origin.
     * A proxy uses this to validate its session's initial URL and every registry lookup.
     */
    fun trustedAbsolute(url: String): HttpUrl? {
        if (!hasExplicitScheme(url) || !isAllowedReference(url)) return null
        return url.toHttpUrlOrNull()?.takeIf(::isTrusted)
    }

    /**
     * Resolve a server-provided URL reference against a trusted resource URL.
     *
     * Relative references are intentionally supported for normal Jellyfin paths and nested HLS
     * playlists. Network-path references (`//host/path`), non-HTTP(S) schemes, user-info, malformed
     * URLs and every origin change return null instead of producing a fetchable target.
     */
    fun resolve(reference: String, relativeTo: HttpUrl = baseUrl): HttpUrl? {
        if (!isTrusted(relativeTo) || !isAllowedReference(reference)) return null
        val target = if (hasExplicitScheme(reference)) {
            reference.toHttpUrlOrNull()
        } else {
            relativeTo.resolve(reference)
        }
        return target?.takeIf(::isTrusted)
    }

    private data class Origin(val scheme: String, val host: String, val port: Int)

    companion object {
        private val EXPLICIT_SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")

        /** Create a policy from the already-validated Jellyfin server URL or initial media URL. */
        fun fromBaseUrl(rawBaseUrl: String): TrustedMediaOriginPolicy? =
            rawBaseUrl.toHttpUrlOrNull()?.let(::fromBaseUrl)

        /** Create a policy from a parsed HTTP(S) base URL. */
        fun fromBaseUrl(baseUrl: HttpUrl): TrustedMediaOriginPolicy? {
            if (baseUrl.scheme !in setOf("http", "https")) return null
            if (baseUrl.username.isNotEmpty() || baseUrl.password.isNotEmpty()) return null
            return TrustedMediaOriginPolicy(
                // Server base paths (for example /jellyfin) are directories even when user
                // normalization removes the trailing slash. Keep that semantic for relative
                // PlaybackInfo URLs while preserving the independently pinned authority.
                baseUrl = directoryBase(baseUrl),
                origin = Origin(baseUrl.scheme, baseUrl.host, baseUrl.port),
            )
        }

        private fun directoryBase(baseUrl: HttpUrl): HttpUrl = baseUrl.newBuilder()
            .encodedPath(baseUrl.encodedPath.let { if (it.endsWith("/")) it else "$it/" })
            .query(null)
            .fragment(null)
            .build()

        private fun hasExplicitScheme(reference: String): Boolean =
            EXPLICIT_SCHEME.containsMatchIn(reference)

        private fun isAllowedReference(reference: String): Boolean {
            // Do not silently trim server-controlled values: a value we cannot parse exactly should not
            // become an authenticated request. A leading backslash pair is rejected alongside `//`
            // because URL parsers commonly treat it as a network-path reference too.
            if (reference.isEmpty() || reference != reference.trim()) return false
            if (reference.startsWith("//") || reference.startsWith("\\\\")) return false
            if (hasExplicitScheme(reference)) {
                val scheme = reference.substringBefore(':').lowercase()
                if (scheme != "http" && scheme != "https") return false
            }
            return true
        }
    }
}
