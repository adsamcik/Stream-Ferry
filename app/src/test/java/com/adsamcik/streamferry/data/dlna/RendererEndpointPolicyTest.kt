package com.adsamcik.streamferry.data.dlna

import android.net.Network
import com.adsamcik.streamferry.diagnostics.NetworkInfoProvider
import io.mockk.every
import io.mockk.mockk
import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
