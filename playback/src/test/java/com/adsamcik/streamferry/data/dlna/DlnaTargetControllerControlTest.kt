package com.adsamcik.streamferry.data.dlna

import android.net.Network
import com.adsamcik.streamferry.diagnostics.NetworkInfoProvider
import com.adsamcik.streamferry.source.api.DiagnosticSink
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DlnaTargetControllerControlTest {

    private fun controller() = DlnaTargetController(
        logger = mockk<DiagnosticSink>(relaxed = true),
        network = mockk<NetworkInfoProvider>(relaxed = true),
    )

    @Test
    fun `transport command fails when no renderer is connected`() = runTest {
        assertFailsWith<IllegalStateException> { controller().play() }
    }

    @Test
    fun `volume command fails when no renderer is connected`() = runTest {
        assertFailsWith<IllegalStateException> { controller().setVolume(0.5f) }
    }

    @Test
    fun `fresh transport read follows native resume before the paused status poll runs`() = runTest {
        val network = mockk<NetworkInfoProvider>(relaxed = true)
        every { network.lanNetwork() } returns mockk<Network>(relaxed = true)
        every { network.lanIpv4() } returns "10.0.1.81"
        var transportState = "PAUSED_PLAYBACK"
        var transportReads = 0
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val body = if (request.url.encodedPath == "/description.xml") {
                """<root><device><deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
                    <friendlyName>Test TV</friendlyName><UDN>uuid:test-tv</UDN><serviceList><service>
                    <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
                    <controlURL>/control</controlURL></service></serviceList></device></root>"""
            } else {
                assertEquals("\"urn:schemas-upnp-org:service:AVTransport:1#GetTransportInfo\"", request.header("SOAPACTION"))
                transportReads++
                """<CurrentTransportState>$transportState</CurrentTransportState><CurrentTransportStatus>OK</CurrentTransportStatus>"""
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody("text/xml".toMediaType())).build()
        }.build()
        val controller = DlnaTargetController(
            mockk<DiagnosticSink>(relaxed = true), network, http,
            configuredRenderers = listOf(ConfiguredDlnaRenderer("http://10.0.1.44/description.xml", "uuid:test-tv", "10.0.1.44")),
        )
        try {
            controller.connect(controller.discover(500).single())
            // No load means no polling job: every result must come from this fresh SOAP read.
            assertFalse(controller.readIsPlaying()!!)
            transportState = "TRANSITIONING"
            assertTrue(controller.readIsPlaying()!!)
            transportState = "PLAYING"
            assertTrue(controller.readIsPlaying()!!)
            transportState = "PAUSED_RECORDING"
            assertFalse(controller.readIsPlaying()!!)
            transportState = "STOPPED"
            assertFalse(controller.readIsPlaying()!!)
            transportState = "NO_MEDIA_PRESENT"
            assertNull(controller.readIsPlaying())
            assertEquals(6, transportReads)
        } finally {
            controller.disconnect()
        }
        assertNull(controller.readIsPlaying())
    }
}
