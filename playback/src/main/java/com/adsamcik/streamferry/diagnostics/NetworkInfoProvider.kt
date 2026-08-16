package com.adsamcik.streamferry.diagnostics

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import com.adsamcik.streamferry.core.net.LanInterfaceSelector
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Resolves the phone's LAN IPv4 address and VPN/transport state for proxy binding + diagnostics
 * (§4). Pure platform reads; no secrets.
 */
class NetworkInfoProvider(context: Context) {

    private val appContext = context.applicationContext

    /**
     * Best LAN IPv4 address to bind the proxy and advertise to the TV, or null if none.
     *
     * Selection is delegated to the pure-JVM [LanInterfaceSelector] so the (security/correctness
     * sensitive) choice is unit-tested. Crucially, when a VPN such as WireGuard is up to reach a
     * a remote media server, the tunnel interface (e.g. `wg0`/`tun0`) may also carry a site-local address;
     * binding the proxy there would make the phone unreachable by the TV on the real Wi-Fi LAN. The
     * selector demotes tunnel/point-to-point/cellular interfaces and prefers the Wi-Fi/Ethernet LAN.
     */
    fun lanIpv4(): String? = selectedLanIpv4() ?: LanInterfaceSelector.selectBindAddress(lanCandidates())

    /** Address attached to the same physical LAN Network used for renderer traffic, when available. */
    private fun selectedLanIpv4(): String? {
        val cm = appContext.getSystemService(ConnectivityManager::class.java) ?: return null
        return cm.getLinkProperties(lanNetwork() ?: return null)?.linkAddresses
            ?.mapNotNull { it.address as? Inet4Address }
            ?.firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress
    }

    private fun lanCandidates(): List<LanInterfaceSelector.Candidate> {
        val interfaces = runCatching { NetworkInterface.getNetworkInterfaces()?.toList() }
            .getOrNull() ?: return emptyList()
        return interfaces.flatMap { iface ->
            val name = iface.name ?: return@flatMap emptyList()
            val isUp = runCatching { iface.isUp }.getOrDefault(false)
            val isLoopback = runCatching { iface.isLoopback }.getOrDefault(false)
            val isVirtual = runCatching { iface.isVirtual }.getOrDefault(false)
            val isP2p = runCatching { iface.isPointToPoint }.getOrDefault(false)
            iface.inetAddresses.toList()
                .filterIsInstance<Inet4Address>()
                .mapNotNull { addr ->
                    val ip = addr.hostAddress ?: return@mapNotNull null
                    LanInterfaceSelector.Candidate(
                        name = name,
                        ipv4 = ip,
                        isUp = isUp,
                        isLoopback = isLoopback,
                        isVirtual = isVirtual,
                        isPointToPoint = isP2p,
                        isSiteLocal = addr.isSiteLocalAddress,
                    )
                }
        }
    }

    fun isVpnActive(): Boolean {
        val cm = appContext.getSystemService(ConnectivityManager::class.java) ?: return false
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }

    fun isWifiConnected(): Boolean {
        val cm = appContext.getSystemService(ConnectivityManager::class.java) ?: return false
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    /** Selected physical LAN Network, preferring Wi-Fi and then Ethernet, never a VPN/default tunnel. */
    @Suppress("DEPRECATION")
    fun lanNetwork(): Network? {
        val cm = appContext.getSystemService(ConnectivityManager::class.java) ?: return null
        return cm.allNetworks.firstOrNull { net ->
            val caps = cm.getNetworkCapabilities(net) ?: return@firstOrNull false
            !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
        }
    }

    /**
     * The Wi-Fi [Network] (even when a VPN is the *default* network), or null. Sockets/clients bound to
     * it reach LAN devices (the TV) directly over Wi-Fi instead of being captured by the VPN tunnel —
     * required for DLNA device-description fetch + SOAP control while a VPN reaches a media source.
     */
    @Suppress("DEPRECATION")
    fun wifiNetwork(): Network? {
        val cm = appContext.getSystemService(ConnectivityManager::class.java) ?: return null
        return cm.allNetworks.firstOrNull { net ->
            val caps = cm.getNetworkCapabilities(net)
            caps != null &&
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }
    }

    @Suppress("DEPRECATION")
    fun multicastLock(tag: String): WifiManager.MulticastLock? {
        val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
        return wifi.createMulticastLock(tag)
    }
}
