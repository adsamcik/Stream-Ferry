package com.adsamcik.streamferry.data.dlna

import android.net.Network
import com.adsamcik.streamferry.core.dlna.DidlLite
import com.adsamcik.streamferry.diagnostics.NetworkInfoProvider
import com.adsamcik.streamferry.domain.PlaybackTargetEvent
import com.adsamcik.streamferry.domain.PlaybackFailureKind
import com.adsamcik.streamferry.domain.PlaybackTargetException
import com.adsamcik.streamferry.domain.RendererStream
import com.adsamcik.streamferry.source.api.DiagnosticSink
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
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
import okio.Buffer
import java.io.IOException
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

    @Test
    fun `paused handoff holds requested position through idle polls without starting the TV`() = runTest {
        val fixture = pollingFixture(startPosition = 63, initialState = "STOPPED", initialPosition = 0)
        try {
            // Longer than both the engine seek-settle window and several idle SOAP polls.
            advanceTimeBy(30_001)
            runCurrent()
            val statuses = fixture.events.filterIsInstance<PlaybackTargetEvent.StatusChanged>()
            assertTrue(statuses.size >= 3)
            assertTrue(statuses.all { it.positionSeconds == 63L && !it.isPlaying && !it.isPositionConfirmed })
            assertTrue(fixture.seekTargets.isEmpty())
            assertEquals(0, fixture.playCommands)
            assertFalse(fixture.controller.readIsPlaying()!!)
        } finally {
            fixture.controller.disconnect()
        }
    }

    @Test
    fun `new paused scrub replaces handoff position and resume retries the latest seek`() = runTest {
        val fixture = pollingFixture(startPosition = 63, initialState = "STOPPED", initialPosition = 0)
        try {
            fixture.controller.seekTo(87)
            advanceTimeBy(351)
            runCurrent()
            assertTrue(fixture.seekTargets.isEmpty())
            assertEquals(
                PlaybackTargetEvent.StatusChanged(87, false, isPositionConfirmed = false),
                fixture.events.filterIsInstance<PlaybackTargetEvent.StatusChanged>().last(),
            )
            fixture.failedSeeksRemaining = 1
            val confirmed = async(UnconfinedTestDispatcher(testScheduler)) {
                fixture.controller.events.first {
                    it is PlaybackTargetEvent.StatusChanged && it.isPositionConfirmed && it.positionSeconds == 87L
                }
            }
            fixture.controller.play()
            // Await real IO completion through the observable renderer confirmation. The test scheduler
            // advances the poll delays while the mocked SOAP call uses the controller's normal IO path.
            confirmed.await()
            assertEquals(listOf("0:01:27", "0:01:27"), fixture.seekTargets)
            assertEquals(1, fixture.playCommands)
            assertTrue(fixture.events.filterIsInstance<PlaybackTargetEvent.StatusChanged>().none {
                it.isPositionConfirmed && it.positionSeconds == 0L
            })
            assertTrue(fixture.events.none { it is PlaybackTargetEvent.ControlError })
        } finally {
            fixture.controller.disconnect()
        }
    }

    @Test
    fun `missing transport state disconnects even when position replies keep succeeding`() = runTest {
        val fixture = pollingFixture()
        try {
            val disconnected = async(UnconfinedTestDispatcher(testScheduler)) {
                fixture.controller.events.first { it is PlaybackTargetEvent.Disconnected }
            }
            fixture.transportState = null
            disconnected.await()
            assertEquals(1, fixture.events.count { it is PlaybackTargetEvent.Disconnected })
            advanceTimeBy(20_000); runCurrent()
            assertEquals(1, fixture.events.count { it is PlaybackTargetEvent.Disconnected })
        } finally {
            fixture.controller.disconnect()
        }
    }

    @Test
    fun `generic transport error does not claim a decoder failure or emit misleading stopped status`() = runTest {
        val fixture = pollingFixture()
        try {
            // Fixture loading uses real IO; discard any status observed before that load returned.
            fixture.events.clear()
            val error = async(UnconfinedTestDispatcher(testScheduler)) {
                fixture.controller.events.first { it is PlaybackTargetEvent.Error }
            }
            fixture.transportState = null
            fixture.transportStatus = "ERROR_OCCURRED"
            error.await()
            assertEquals(PlaybackFailureKind.UNKNOWN,
                fixture.events.filterIsInstance<PlaybackTargetEvent.Error>().single().kind)
            assertTrue(fixture.events.none { it is PlaybackTargetEvent.StatusChanged })
            assertTrue(fixture.events.none { it is PlaybackTargetEvent.Disconnected })
        } finally { fixture.controller.disconnect() }
    }

    @Test
    fun `playing renderer survives sticky generic transport error`() = runTest {
        val fixture = pollingFixture()
        try {
            fixture.transportStatus = "ERROR_OCCURRED"
            repeat(3) {
                fixture.positionSeconds++
                val expectedPosition = fixture.positionSeconds
                val progressed = async(UnconfinedTestDispatcher(testScheduler)) {
                    fixture.controller.events.first {
                        it is PlaybackTargetEvent.StatusChanged && it.positionSeconds == expectedPosition
                    }
                }
                progressed.await()
            }
            assertTrue(fixture.events.filterIsInstance<PlaybackTargetEvent.StatusChanged>().size >= 3)
            assertTrue(fixture.events.none { it is PlaybackTargetEvent.Error || it is PlaybackTargetEvent.Disconnected })
        } finally { fixture.controller.disconnect() }
    }

    @Test
    fun `transitioning is bounded without making a format claim`() = runTest {
        val fixture = pollingFixture(initialState = "TRANSITIONING")
        try {
            fixture.playStartsPlayback = false
            fixture.controller.play()
            val startedAt = testScheduler.currentTime
            val error = async(UnconfinedTestDispatcher(testScheduler)) {
                fixture.controller.events.first { it is PlaybackTargetEvent.Error } as PlaybackTargetEvent.Error
            }.await()
            assertTrue(testScheduler.currentTime - startedAt in 30_000L..40_000L)
            assertEquals(PlaybackFailureKind.NETWORK, error.kind)
            assertFalse(error.qualifiedFormatEvidence)
            advanceTimeBy(40_000); runCurrent()
            assertEquals(1, fixture.events.filterIsInstance<PlaybackTargetEvent.Error>().size)
        } finally { fixture.controller.disconnect() }
    }

    @Test
    fun `accepted play that stays stopped has a startup deadline while paused load does not`() = runTest {
        val fixture = pollingFixture(initialState = "STOPPED")
        try {
            advanceTimeBy(40_000); runCurrent()
            assertTrue(fixture.events.none { it is PlaybackTargetEvent.Error })
            fixture.playStartsPlayback = false
            fixture.controller.play()
            val startedAt = testScheduler.currentTime
            val error = async(UnconfinedTestDispatcher(testScheduler)) {
                fixture.controller.events.first { it is PlaybackTargetEvent.Error } as PlaybackTargetEvent.Error
            }.await()
            assertTrue(testScheduler.currentTime - startedAt in 30_000L..40_000L)
            assertEquals(PlaybackFailureKind.NETWORK, error.kind)
        } finally { fixture.controller.disconnect() }
    }

    @Test
    fun `explicit UPnP format rejection is separated from busy network and transition faults`() = runTest {
        val fixture = pollingFixture()
        try {
            for ((code, expected) in listOf(
                704 to PlaybackFailureKind.FORMAT, 714 to PlaybackFailureKind.FORMAT,
                701 to PlaybackFailureKind.UNKNOWN, 705 to PlaybackFailureKind.UNKNOWN,
                715 to PlaybackFailureKind.UNKNOWN, 703 to PlaybackFailureKind.NETWORK,
                716 to PlaybackFailureKind.NETWORK,
            )) {
                fixture.playFaultCode = code
                val failure = assertFailsWith<PlaybackTargetException> { fixture.controller.play() }
                assertEquals(expected, failure.kind, "UPnP $code")
                assertEquals(expected == PlaybackFailureKind.FORMAT, failure.qualifiedFormatEvidence)
            }
            fixture.playFaultCode = null
            fixture.playIoFailure = true
            val failure = assertFailsWith<PlaybackTargetException> { fixture.controller.play() }
            assertEquals(PlaybackFailureKind.NETWORK, failure.kind)
            assertFalse(failure.qualifiedFormatEvidence)
        } finally { fixture.controller.disconnect() }
    }

    @Test
    fun `disconnect invalidates a pending startup timeout`() = runTest {
        val fixture = pollingFixture(initialState = "TRANSITIONING")
        fixture.playStartsPlayback = false
        fixture.controller.play()
        advanceTimeBy(10_000); runCurrent()
        fixture.controller.disconnect()
        advanceTimeBy(40_000); runCurrent()
        assertTrue(fixture.events.none { it is PlaybackTargetEvent.Error })
    }

    @Test
    fun `load preserves namespaced unsupported MIME fault even with successful HTTP status`() = runTest {
        val fixture = pollingFixture()
        try {
            fixture.setUriFaultCode = 714
            val failure = assertFailsWith<PlaybackTargetException> {
                fixture.controller.load("http://10.0.1.81/video", RendererStream("video/mp2t"), "Video", 120, 0, false)
            }
            assertEquals(PlaybackFailureKind.FORMAT, failure.kind)
            assertTrue(failure.qualifiedFormatEvidence)
        } finally { fixture.controller.disconnect() }
    }

    private class PollingFixture(val controller: DlnaTargetController) {
        var transportState: String? = "PLAYING"
        var transportStatus = "OK"
        var positionSeconds = 42L
        var failedSeeksRemaining = 0
        var playCommands = 0
        var playStartsPlayback = true
        var playFaultCode: Int? = null
        var playIoFailure = false
        var setUriFaultCode: Int? = null
        val seekTargets = mutableListOf<String>()
        val events = mutableListOf<PlaybackTargetEvent>()
    }

    private suspend fun TestScope.pollingFixture(
        startPosition: Long = 0,
        initialState: String = "PLAYING",
        initialPosition: Long = 42,
    ): PollingFixture {
        val network = mockk<NetworkInfoProvider>(relaxed = true)
        every { network.lanNetwork() } returns mockk<Network>(relaxed = true)
        every { network.lanIpv4() } returns "10.0.1.81"
        lateinit var fixture: PollingFixture
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val action = request.header("SOAPACTION")?.substringAfter('#')?.trim('"')
            var responseCode = 200
            val body = when {
                request.url.encodedPath == "/description.xml" ->
                    """<root><device><deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
                        <friendlyName>Test TV</friendlyName><UDN>uuid:test-tv</UDN><serviceList><service>
                        <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
                        <controlURL>/control</controlURL></service></serviceList></device></root>"""
                action == "GetTransportInfo" -> fixture.transportState?.let {
                    "<CurrentTransportState>$it</CurrentTransportState><CurrentTransportStatus>${fixture.transportStatus}</CurrentTransportStatus>"
                } ?: "<CurrentTransportStatus>${fixture.transportStatus}</CurrentTransportStatus>"
                action == "GetPositionInfo" -> "<RelTime>${DidlLite.formatDuration(fixture.positionSeconds)}</RelTime>"
                action == "SetAVTransportURI" && fixture.setUriFaultCode != null ->
                    "<upnp:errorCode>${fixture.setUriFaultCode}</upnp:errorCode>"
                action == "Play" -> {
                    fixture.playCommands++
                    if (fixture.playIoFailure) throw IOException("Connection lost")
                    val fault = fixture.playFaultCode
                    if (fault != null) {
                        responseCode = 500
                        "<upnp:errorCode>$fault</upnp:errorCode>"
                    } else {
                        if (fixture.playStartsPlayback) fixture.transportState = "PLAYING"
                        "<Response/>"
                    }
                }
                action == "Seek" -> {
                    val buffer = Buffer()
                    request.body!!.writeTo(buffer)
                    val target = Regex("<Target>([^<]+)</Target>").find(buffer.readUtf8())!!.groupValues[1]
                    fixture.seekTargets += target
                    if (fixture.failedSeeksRemaining-- > 0) {
                        responseCode = 500
                        "<errorCode>701</errorCode>"
                    } else {
                        fixture.positionSeconds = target.split(':').map(String::toLong).let { it[0] * 3600 + it[1] * 60 + it[2] }
                        "<Response/>"
                    }
                }
                else -> "<Response/>"
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(responseCode).message("OK")
                .body(body.toResponseBody("text/xml".toMediaType())).build()
        }.build()
        val controller = DlnaTargetController(
            mockk<DiagnosticSink>(relaxed = true), network, http,
            configuredRenderers = listOf(ConfiguredDlnaRenderer("http://10.0.1.44/description.xml", "uuid:test-tv", "10.0.1.44")),
            pollingDispatcher = StandardTestDispatcher(testScheduler),
            monotonicMillis = { testScheduler.currentTime },
        )
        fixture = PollingFixture(controller)
        fixture.transportState = initialState
        fixture.positionSeconds = initialPosition
        controller.connect(controller.discover(500).single())
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { controller.events.collect { fixture.events += it } }
        controller.load("http://10.0.1.81/video", RendererStream("video/mp2t"), "Video", 120, startPosition, false)
        runCurrent()
        return fixture
    }

    private suspend fun TestScope.poll(fixture: PollingFixture) {
        fixture.controller.seekTo(42)
        advanceTimeBy(351)
        runCurrent()
    }
}
