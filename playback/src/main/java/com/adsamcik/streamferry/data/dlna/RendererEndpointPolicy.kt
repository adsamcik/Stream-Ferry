package com.adsamcik.streamferry.data.dlna

import android.net.Network
import com.adsamcik.streamferry.core.net.ServerUrlValidator
import com.adsamcik.streamferry.diagnostics.NetworkInfoProvider
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

/**
 * Trust policy for URLs learned from SSDP/device descriptions. It is deliberately distinct from the
 * remote-source URL policy: renderer endpoints must be private IPv4 peers on one selected
 * physical LAN and are pinned to the SSDP response source for the renderer descriptor's lifetime.
 */
class RendererEndpointPolicy(private val networkInfo: NetworkInfoProvider) {

    data class Endpoint(
        val url: String,
        val host: String,
        val addresses: List<InetAddress>,
        val network: Network,
        val localAddress: InetAddress,
    )

    /**
     * Validate LOCATION and pin it to the exact source that sent the SSDP reply. Host names do not
     * need a second DNS lookup: the unicast reply already proves the renderer's reachable address,
     * and pinning avoids slow/broken vendor DNS while ensuring the fetch cannot leave that peer.
     */
    fun location(rawUrl: String, source: InetAddress?): Endpoint? {
        source ?: return null
        return resolve(rawUrl, requiredSource = source, expectedNetwork = null, expectedAddresses = null)
    }

    /**
     * Service URLs may change spelling/host name, but their client remains pinned to the addresses
     * proven by the SSDP reply. An absolute IPv4 literal must name one of those exact addresses.
     */
    fun service(rawUrl: String, location: Endpoint): Endpoint? =
        resolve(rawUrl, requiredSource = null, expectedNetwork = location.network, expectedAddresses = location.addresses)

    /**
     * Every request is bound to the selected physical LAN address plus a DNS implementation that returns
     * only addresses validated at discovery time. Direct address binding is important on Android 16
     * vendor kernels that reject `Network.socketFactory` with EPERM while a VPN is active; it still keeps
     * renderer traffic off the VPN because the socket's source is the verified Wi-Fi/Ethernet address.
     */
    fun client(base: OkHttpClient, endpoint: Endpoint, timeoutMillis: Long): OkHttpClient = base.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .callTimeout(timeoutMillis.coerceAtLeast(1), TimeUnit.MILLISECONDS)
        .socketFactory(LocalAddressSocketFactory(endpoint.localAddress))
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
        if (uri.port == 0 || uri.port !in -1..65535) return null
        val literalHost = isIpv4Literal(host)
        if (!literalHost && !ServerUrlValidator.isPrivateHost(host)) return null

        val selectedNetwork = expectedNetwork ?: networkInfo.lanNetwork() ?: return null
        if (expectedNetwork != null && networkInfo.lanNetwork() != expectedNetwork) return null
        val addresses = when {
            requiredSource != null -> {
                if (!isApprovedRendererAddress(requiredSource)) return null
                if (literalHost) {
                    val advertised = ipv4Literal(host) ?: return null
                    if (advertised != requiredSource) return null
                }
                listOf(requiredSource)
            }
            expectedAddresses != null -> {
                if (expectedAddresses.isEmpty() || expectedAddresses.any { !isApprovedRendererAddress(it) }) return null
                if (literalHost) {
                    val advertised = ipv4Literal(host) ?: return null
                    if (advertised !in expectedAddresses) return null
                }
                expectedAddresses
            }
            else -> return null
        }
        val localAddress = networkInfo.lanIpv4()
            ?.let { runCatching { InetAddress.getByName(it) }.getOrNull() as? Inet4Address }
            ?.takeIf { it.isSiteLocalAddress }
            ?: return null

        return Endpoint(
            url = uri.normalize().toString(),
            host = host,
            addresses = addresses,
            network = selectedNetwork,
            localAddress = localAddress,
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
        parts.size == 4 && parts.all { part ->
            part.toIntOrNull()?.let { it in 0..255 } == true &&
                (part.length == 1 || !part.startsWith('0'))
        }
    }

    private fun ipv4Literal(host: String): Inet4Address? =
        runCatching { InetAddress.getByName(host) }.getOrNull() as? Inet4Address

    /** SocketFactory equivalent of binding a UDP socket to the physical LAN interface/address. */
    private class LocalAddressSocketFactory(private val localAddress: InetAddress) : SocketFactory() {
        private fun unconnected(): Socket = Socket().apply {
            reuseAddress = true
            bind(InetSocketAddress(localAddress, 0))
        }

        override fun createSocket(): Socket = unconnected()

        override fun createSocket(host: String, port: Int): Socket =
            unconnected().apply { connect(InetSocketAddress(host, port)) }

        override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
            unconnected().apply { connect(InetSocketAddress(host, port)) }

        override fun createSocket(host: InetAddress, port: Int): Socket =
            unconnected().apply { connect(InetSocketAddress(host, port)) }

        override fun createSocket(
            address: InetAddress,
            port: Int,
            localAddress: InetAddress,
            localPort: Int,
        ): Socket = unconnected().apply { connect(InetSocketAddress(address, port)) }

        override fun equals(other: Any?): Boolean =
            other is LocalAddressSocketFactory && other.localAddress == localAddress

        override fun hashCode(): Int = localAddress.hashCode()
    }
}
