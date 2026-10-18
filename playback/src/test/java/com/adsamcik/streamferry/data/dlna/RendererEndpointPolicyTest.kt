package com.adsamcik.streamferry.data.dlna

import android.net.Network
import com.adsamcik.streamferry.diagnostics.NetworkInfoProvider
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import java.net.InetAddress
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import okhttp3.OkHttpClient
import org.junit.Assume.assumeNotNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class RendererEndpointPolicyTest {

    private val lanNetwork = mockk<Network>()
    private val networkInfo = mockk<NetworkInfoProvider>().also {
        every { it.lanNetwork() } returns lanNetwork
        every { it.lanIpv4() } returns "10.0.1.81"
    }
    private val policy = RendererEndpointPolicy(networkInfo)
    private val rendererAddress = InetAddress.getByName("10.0.1.44")

    @Test fun literalLocationMustMatchSsdpSource() {
        val endpoint = policy.location("http://10.0.1.44:9197/description.xml", rendererAddress)

        assertNotNull(endpoint)
        assertEquals(listOf(rendererAddress), endpoint.addresses)
        assertNull(policy.location("http://10.0.1.45:9197/description.xml", rendererAddress))
    }

    @Test fun localHostnameLocationIsPinnedDirectlyToSsdpSource() {
        val endpoint = policy.location("http://living-room-tv:9197/description.xml", rendererAddress)

        assertNotNull(endpoint)
        assertEquals("living-room-tv", endpoint.host)
        assertEquals(listOf(rendererAddress), endpoint.addresses)
    }

    @Test fun serviceHostnameIsPinnedToDiscoveredRenderer() {
        val location = policy.location("http://10.0.1.44:9197/description.xml", rendererAddress)
        assertNotNull(location)

        val service = policy.service("http://living-room-tv.local:9197/avtransport", location)

        assertNotNull(service)
        assertEquals(listOf(rendererAddress), service.addresses)
        assertNull(policy.service("http://10.0.1.45:9197/avtransport", location))
        assertNull(policy.service("http://attacker.example.com/avtransport", location))
    }

    @Test fun rejectsLocationWithoutPacketSource() {
        assertNull(policy.location("http://10.0.1.44:9197/description.xml", null))
    }
    @Test fun refreshedClientUsesCurrentLanAddressAndRetainsDiscoveredPeerPin() {
        // Use a real machine-local TCP connection, without sending anything onto the LAN.
        val currentAddress = NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
        assumeNotNull(currentAddress)
        val current = requireNotNull(currentAddress)
        every { networkInfo.lanIpv4() } returns current.hostAddress
        val discovered = requireNotNull(policy.location("http://living-room-tv/description.xml", rendererAddress))
            .copy(localAddress = InetAddress.getByName(
                if (current.hostAddress == "10.255.255.254") "10.255.255.253" else "10.255.255.254",
            )) // obsolete discovery-time source
        val client = policy.client(OkHttpClient(), discovered, 1_000)
        assertEquals(listOf(rendererAddress), client.dns.lookup("living-room-tv"))
        assertFailsWith<java.net.UnknownHostException> { client.dns.lookup("another-device") }
        ServerSocket().use { listener ->
            listener.bind(InetSocketAddress(current, 0))
            listener.soTimeout = 2_000
            client.socketFactory.createSocket().use { socket ->
                socket.connect(InetSocketAddress(current, listener.localPort), 2_000)
                listener.accept().use { accepted ->
                    assertEquals(current, socket.localAddress)
                    // The local bind is the contract; host NAT/proxies may rewrite the observed peer.
                    accepted.soTimeout = 2_000
                    socket.getOutputStream().write(0x47)
                    assertEquals(0x47, accepted.getInputStream().read())
                }
            }
        }
    }

    @Test fun endpointFromAnotherNetworkRequiresRediscovery() {
        val endpoint = requireNotNull(policy.location("http://10.0.1.44/description.xml", rendererAddress))
        every { networkInfo.lanNetwork() } returns mockk<Network>()
        assertFailsWith<IOException> { policy.client(OkHttpClient(), endpoint, 1_000) }
    }

    @Test fun missingLanAddressCannotFallBackToDefaultRoute() {
        val endpoint = requireNotNull(policy.location("http://10.0.1.44/description.xml", rendererAddress))
        for (unusableAddress in listOf(null, "8.8.8.8", "127.0.0.1")) {
            every { networkInfo.lanIpv4() } returns unusableAddress
            assertFailsWith<IOException> { policy.client(OkHttpClient(), endpoint, 1_000) }
        }
    }

    @Test fun addressChangeAfterClientCreationRejectsSocketBeforeBinding() {
        val endpoint = requireNotNull(policy.location("http://10.0.1.44/description.xml", rendererAddress))
        val client = policy.client(OkHttpClient(), endpoint, 1_000)
        every { networkInfo.lanIpv4() } returns "10.0.1.82"
        assertFailsWith<IOException> { client.socketFactory.createSocket() }
    }

}
