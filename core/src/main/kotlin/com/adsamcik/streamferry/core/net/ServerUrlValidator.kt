package com.adsamcik.streamferry.core.net

import java.net.URI

/**
 * Validates and normalises a user-entered Jellyfin server address (§8 manual server entry).
 *
 * Security policy (mirrors `network_security_config` + the threat model):
 *  - `https` is always allowed.
 *  - `http` is allowed ONLY for a private/LAN host the user explicitly approves; remote `http` is
 *    rejected so a token is never sent in clear text over the internet.
 *  - The scheme defaults to `https` when the user omits it.
 *
 * Pure JVM + deterministic so the (security-sensitive) decision is unit-tested without Android.
 * It never logs and holds no secrets — only the host the user typed.
 */
object ServerUrlValidator {

    sealed interface Result {
        /** A usable, normalised base URL (no trailing slash). */
        data class Valid(val baseUrl: String, val isHttp: Boolean, val isLan: Boolean) : Result

        /** A LAN `http` address that the user must explicitly approve before it is used. */
        data class NeedsHttpApproval(val baseUrl: String) : Result

        /** The address cannot be used; [reason] is a short, user-facing, secret-free message. */
        data class Invalid(val reason: String) : Result
    }

    fun validate(raw: String, userApprovedHttp: Boolean): Result {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return Result.Invalid("Enter your Jellyfin server address.")

        val withScheme = if (trimmed.contains("://")) trimmed else "https://$trimmed"
        val uri = runCatching { URI(withScheme) }.getOrNull()
            ?: return Result.Invalid("That doesn't look like a valid address.")

        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            return Result.Invalid("Use an http or https address.")
        }

        val host = uri.host?.takeIf { it.isNotBlank() }
            ?: return Result.Invalid("Enter a valid host name.")

        val port = uri.port // -1 when not specified
        val path = uri.rawPath.orEmpty().trimEnd('/')
        val baseUrl = buildString {
            append(scheme).append("://").append(host)
            if (port != -1) append(':').append(port)
            append(path)
        }

        val isLan = isPrivateHost(host)
        if (scheme == "http") {
            if (!isLan) return Result.Invalid("Remote http is blocked. Use https for a server on the internet.")
            if (!userApprovedHttp) return Result.NeedsHttpApproval(baseUrl)
            return Result.Valid(baseUrl, isHttp = true, isLan = true)
        }
        return Result.Valid(baseUrl, isHttp = false, isLan = isLan)
    }

    /**
     * True for loopback / link-local / private / CGNAT hosts, single-label and reserved local DNS
     * names (mDNS `.local`, RFC 8375 `home.arpa`, RFC 6762 App. G private-use TLDs, the ICANN-reserved
     * `.internal`), and overlay-mesh VPN domains (NetBird, Tailscale MagicDNS) whose names resolve to
     * private peer addresses.
     */
    fun isPrivateHost(rawHost: String): Boolean {
        val host = rawHost.trim()
            .removePrefix("[").removeSuffix("]") // IPv6 literal brackets
            .trimEnd('.')                        // FQDN trailing dot (e.g. "media.local.")
            .lowercase()
        if (host.isEmpty()) return false
        if (host == "localhost") return true
        if (LOCAL_SUFFIXES.any { host.endsWith(it) }) return true
        // Overlay/mesh VPN peer-DNS domains resolve to private peer addresses (e.g. NetBird/Tailscale
        // peers live in the 100.64.0.0/10 CGNAT range or fd00::/8 ULA), so treat the mesh domain and
        // any subdomain as LAN even though they are multi-label public-looking names.
        if (MESH_DOMAINS.any { host == it || host.endsWith(".$it") }) return true

        parseIpv4(host)?.let { return isPrivateIpv4(it) }
        if (host.contains(':')) return isPrivateIpv6(host) // bracket-stripped IPv6 literal

        // A bare single-label hostname (no dots) is only resolvable on the local network.
        if (!host.contains('.')) return true
        return false
    }

    /**
     * Reserved / conventional DNS suffixes that only resolve on a local or private network and are
     * therefore safe to reach over http (after the user approves it). None of these are delegated in
     * the public DNS root.
     */
    private val LOCAL_SUFFIXES = listOf(
        ".local",       // mDNS / Bonjour (RFC 6762)
        ".localhost",   // loopback (RFC 6761) — e.g. foo.localhost
        ".lan",
        ".home",
        ".home.arpa",   // residential home networks (RFC 8375)
        ".internal",    // ICANN-reserved for private use (2024); also GCP internal DNS
        ".intranet",
        ".private",
        ".corp",
        ".localdomain",
    )

    /**
     * Overlay/mesh VPN peer-DNS domains whose hostnames resolve to private peer addresses. ZeroTier,
     * Headscale and self-hosted setups use custom or IP-based addressing already covered by the
     * private/CGNAT/ULA ranges below.
     */
    private val MESH_DOMAINS = listOf(
        "netbird.cloud",  // NetBird (managed)
        "netbird.local",  // NetBird (self-hosted DNS)
        "ts.net",         // Tailscale MagicDNS: <host>.<tailnet>.ts.net
    )

    private fun parseIpv4(host: String): IntArray? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val octets = IntArray(4)
        for (i in 0 until 4) {
            val n = parts[i].toIntOrNull() ?: return null
            if (n !in 0..255 || (parts[i].length > 1 && parts[i].startsWith("0"))) return null
            octets[i] = n
        }
        return octets
    }

    private fun isPrivateIpv4(o: IntArray): Boolean = when {
        o[0] == 10 -> true                                   // 10.0.0.0/8
        o[0] == 127 -> true                                  // loopback
        o[0] == 192 && o[1] == 168 -> true                   // 192.168.0.0/16
        o[0] == 172 && o[1] in 16..31 -> true                // 172.16.0.0/12
        o[0] == 169 && o[1] == 254 -> true                   // link-local
        o[0] == 100 && o[1] in 64..127 -> true               // CGNAT 100.64.0.0/10
        else -> false
    }

    private fun isPrivateIpv6(host: String): Boolean {
        val h = host.substringBefore('%') // strip a zone id, e.g. fe80::1%eth0
        if (h == "::1") return true        // loopback
        val firstHextet = h.substringBefore(':') // e.g. "fd7a", "fe80"
        return firstHextet.startsWith("fc") || firstHextet.startsWith("fd") || // fc00::/7 unique-local
            firstHextet.startsWith("fe8") || firstHextet.startsWith("fe9") ||  // fe80::/10 link-local
            firstHextet.startsWith("fea") || firstHextet.startsWith("feb")
    }
}
