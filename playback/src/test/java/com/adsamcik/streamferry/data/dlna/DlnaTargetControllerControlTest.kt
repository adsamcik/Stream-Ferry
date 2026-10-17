package com.adsamcik.streamferry.data.dlna

import android.net.Network
import com.adsamcik.streamferry.diagnostics.NetworkInfoProvider
import com.adsamcik.streamferry.domain.PlaybackTargetEvent
import com.adsamcik.streamferry.domain.RendererStream
import com.adsamcik.streamferry.source.api.DiagnosticSink
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
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

@OptIn(ExperimentalCoroutinesApi::class)
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

    @Test
    fun `transitioning announces buffering before status and stable playback clears it`() = runTest {
        val fixture = pollingFixture()
        try {
            for (stableState in listOf("PLAYING", "PAUSED_PLAYBACK")) {
                fixture.transportState = stableState
                poll(fixture)
                assertEquals(
                    listOf(PlaybackTargetEvent.BufferingChanged(false), PlaybackTargetEvent.StatusChanged(42, stableState == "PLAYING")),
                    fixture.events.takeLast(2),
                )
                fixture.transportState = "TRANSITIONING"
                poll(fixture)
                assertEquals(
                    listOf(PlaybackTargetEvent.BufferingChanged(true), PlaybackTargetEvent.StatusChanged(42, false)),
                    fixture.events.takeLast(2),
                )
                fixture.transportState = stableState
                poll(fixture)
                assertEquals(
                    listOf(PlaybackTargetEvent.BufferingChanged(false), PlaybackTargetEvent.StatusChanged(42, stableState == "PLAYING")),
                    fixture.events.takeLast(2),
                )
            }
        } finally {
            fixture.controller.disconnect()
        }
    }

    @Test
    fun `position without a transport state cannot clear buffering or report a pause`() = runTest {
        val fixture = pollingFixture()
        try {
            fixture.transportState = "TRANSITIONING"
            poll(fixture)
            fixture.events.clear()
            fixture.transportState = null
            poll(fixture)
            assertTrue(fixture.events.isEmpty())
            fixture.transportState = "PLAYING"
            poll(fixture)
            assertTrue(fixture.events.isNotEmpty())
            // The IO control can finish after a scheduled poll; repeated authoritative polls are valid.
            fixture.events.chunked(2).forEach { events ->
                assertEquals(
                    listOf(PlaybackTargetEvent.BufferingChanged(false), PlaybackTargetEvent.StatusChanged(42, true)),
                    events,
                )
            }
        } finally {
            fixture.controller.disconnect()
        }
    }

    private class PollingFixture(val controller: DlnaTargetController) {
        var transportState: String? = "PLAYING"
        val events = mutableListOf<PlaybackTargetEvent>()
    }

    private suspend fun TestScope.pollingFixture(): PollingFixture {
        val network = mockk<NetworkInfoProvider>(relaxed = true)
        every { network.lanNetwork() } returns mockk<Network>(relaxed = true)
        every { network.lanIpv4() } returns "10.0.1.81"
        lateinit var fixture: PollingFixture
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val action = request.header("SOAPACTION")?.substringAfter('#')?.trim('"')
            val body = when {
                request.url.encodedPath == "/description.xml" ->
                    """<root><device><deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
                        <friendlyName>Test TV</friendlyName><UDN>uuid:test-tv</UDN><serviceList><service>
                        <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
                        <controlURL>/control</controlURL></service></serviceList></device></root>"""
                action == "GetTransportInfo" -> fixture.transportState?.let {
                    "<CurrentTransportState>$it</CurrentTransportState><CurrentTransportStatus>OK</CurrentTransportStatus>"
                } ?: "<CurrentTransportStatus>OK</CurrentTransportStatus>"
                action == "GetPositionInfo" -> "<RelTime>0:00:42</RelTime>"
                else -> "<Response/>"
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody("text/xml".toMediaType())).build()
        }.build()
        val controller = DlnaTargetController(
            mockk<DiagnosticSink>(relaxed = true), network, http,
            configuredRenderers = listOf(ConfiguredDlnaRenderer("http://10.0.1.44/description.xml", "uuid:test-tv", "10.0.1.44")),
            pollingDispatcher = StandardTestDispatcher(testScheduler),
        )
        fixture = PollingFixture(controller)
        controller.connect(controller.discover(500).single())
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { controller.events.collect { fixture.events += it } }
        controller.load("http://10.0.1.81/video", RendererStream("video/mp2t"), "Video", 120, 0, false)
        runCurrent()
        return fixture
    }

    private suspend fun TestScope.poll(fixture: PollingFixture) {
        fixture.controller.seekTo(42)
        advanceTimeBy(351)
        runCurrent()
    }
}
