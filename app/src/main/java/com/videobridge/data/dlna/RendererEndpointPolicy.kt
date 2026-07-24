package com.videobridge.data.dlna

import android.net.Network
import com.videobridge.diagnostics.NetworkInfoProvider
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.net.Inet4Address
import java.net.InetAddress
import java.net.URI
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * Trust policy for URLs learned from SSDP/device descriptions. It is deliberately distinct from the
 * user-entered Jellyfin URL policy: renderer endpoints must be private IPv4 peers on one selected
 * physical LAN, resolved through that Network and pinned for the lifetime of the renderer descriptor.
 */
class RendererEndpointPolicy(private val networkInfo: NetworkInfoProvider) {

    data class Endpoint(
        val url: String,
        val host: String,
        val addresses: List<InetAddress>,
        val network: Network,
    )

    /** Validate LOCATION and require it to resolve to the exact source that sent the SSDP reply. */
    fun location(rawUrl: String, source: InetAddress?): Endpoint? =
        resolve(rawUrl, requiredSource = source, expectedNetwork = null, expectedAddresses = null)

    /** Service URLs may change spelling/host name, but must stay on the discovered renderer addresses. */
    fun service(rawUrl: String, location: Endpoint): Endpoint? =
        resolve(rawUrl, requiredSource = null, expectedNetwork = location.network, expectedAddresses = location.addresses)

    /**
     * Every request uses the selected physical LAN Network plus a DNS implementation that returns only
     * the addresses validated at discovery time. This prevents default-route fallback and DNS rebinding.
     */
    fun client(base: OkHttpClient, endpoint: Endpoint, timeoutMillis: Long): OkHttpClient = base.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .callTimeout(timeoutMillis.coerceAtLeast(1), TimeUnit.MILLISECONDS)
        .socketFactory(endpoint.network.socketFactory)
        .dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                if (!hostname.equals(endpoint.host, ignoreCase = true)) {
                    throw UnknownHostException("Renderer endpoint host mismatch")
                }
                return endpoint.addresses
            }
        })
        .build()

    private fun resolve(
        rawUrl: String,
        requiredSource: InetAddress?,
        expectedNetwork: Network?,
        expectedAddresses: List<InetAddress>?,
    ): Endpoint? {
        val uri = runCatching { URI(rawUrl) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        if (uri.rawUserInfo != null || uri.rawFragment != null) return null
        val host = uri.host?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (host.equals("localhost", ignoreCase = true) || host.contains('%')) return null
        if (uri.port !in -1..65535) return null
        if (!isIpv4Literal(host) && !host.contains('.')) return null // no single-label/overlay DNS names

        val selectedNetwork = expectedNetwork ?: networkInfo.lanNetwork() ?: return null
        if (expectedNetwork != null && networkInfo.lanNetwork() != expectedNetwork) return null
        val addresses = runCatching { selectedNetwork.getAllByName(host).toList() }.getOrNull()?.distinct().orEmpty()
        if (addresses.isEmpty() || addresses.any { !isApprovedRendererAddress(it) }) return null
        if (requiredSource != null && addresses.none { it == requiredSource }) return null
        if (expectedAddresses != null && addresses.any { it !in expectedAddresses }) return null

        return Endpoint(
            url = uri.normalize().toString(),
            host = host,
            addresses = addresses,
            network = selectedNetwork,
        )
    }

    private fun isApprovedRendererAddress(address: InetAddress): Boolean {
        val ipv4 = address as? Inet4Address ?: return false // IPv4-first proxy advertisement
        val b = ipv4.address.map { it.toInt() and 0xff }
        return b[0] == 10 ||
            (b[0] == 172 && b[1] in 16..31) ||
            (b[0] == 192 && b[1] == 168)
    }

    private fun isIpv4Literal(host: String): Boolean = host.split('.').let { parts ->
        parts.size == 4 && parts.all { part -> part.toIntOrNull()?.let { it in 0..255 } == true }
    }
}
