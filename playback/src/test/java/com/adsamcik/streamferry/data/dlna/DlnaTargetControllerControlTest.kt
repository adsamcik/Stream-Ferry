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
import kotlinx.coroutines.CompletableDeferred
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
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
                """<response><CurrentTransportState>$transportState</CurrentTransportState><CurrentTransportStatus>OK</CurrentTransportStatus></response>"""
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
                assertEquals(2, events.size)
                assertEquals(PlaybackTargetEvent.BufferingChanged(false), events[0])
                // A pre-seek position sample can be unconfirmed while its transport state stays valid.
                val status = assertIs<PlaybackTargetEvent.StatusChanged>(events[1])
                assertEquals(42L, status.positionSeconds)
                assertTrue(status.isPlaying)
            }
        } finally {
            fixture.controller.disconnect()
        }
    }

    @Test
    fun `playing without relative time confirms transport but never confirms a fabricated position`() = runTest {
        for (reply in listOf("<RelTime>NOT_IMPLEMENTED</RelTime>", "<Track>1</Track>")) {
            val fixture = pollingFixture()
            try {
                fixture.positionReply = reply
                fixture.events.clear()
                val polled = async(UnconfinedTestDispatcher(testScheduler)) {
                    // A poll already in flight may precede the accepted Seek. Await its retained
                    // target rather than treating that older transport report as the seek result.
                    fixture.controller.events.first {
                        it is PlaybackTargetEvent.StatusChanged && it.positionSeconds == 47L
                    }
                        as PlaybackTargetEvent.StatusChanged
                }
                fixture.controller.seekTo(47)
                val status = polled.await()
                assertTrue(status.isPlaying)
                assertFalse(status.isPositionConfirmed)
                assertEquals(47L, status.positionSeconds)
                assertTrue(fixture.events.none { it is PlaybackTargetEvent.Error || it is PlaybackTargetEvent.Disconnected })
                fixture.positionReply = null
                val confirmed = async(UnconfinedTestDispatcher(testScheduler)) {
                    fixture.controller.events.first {
                        it is PlaybackTargetEvent.StatusChanged && it.isPositionConfirmed && it.positionSeconds == 48L
                    }
                }
                fixture.controller.seekTo(48)
                confirmed.await()
            } finally { fixture.controller.disconnect() }
        }
    }

    @Test
    fun `paused zero load retains the latest scrub until Play without an unnecessary initial zero seek`() = runTest {
        val fixture = pollingFixture(startPosition = 0, initialState = "STOPPED", initialPosition = 0)
        try {
            fixture.controller.seekTo(60)
            fixture.controller.seekTo(87)
            assertTrue(fixture.seekTargets.isEmpty(), "The renderer must remain STOPPED until Resume")
            assertEquals(0, fixture.playCommands)
            fixture.failedSeeksRemaining = 1
            val confirmed = async(UnconfinedTestDispatcher(testScheduler)) {
                fixture.controller.events.first {
                    it is PlaybackTargetEvent.StatusChanged && it.isPositionConfirmed && it.positionSeconds == 87L
                }
            }
            fixture.controller.play()
            confirmed.await()
            assertEquals(listOf("0:01:27", "0:01:27"), fixture.seekTargets)
            assertEquals(1, fixture.playCommands)
            assertTrue(fixture.events.none { it is PlaybackTargetEvent.ControlError })
        } finally { fixture.controller.disconnect() }

        val zero = pollingFixture(startPosition = 0, initialState = "STOPPED", initialPosition = 0)
        try {
            val playing = async(UnconfinedTestDispatcher(testScheduler)) {
                zero.controller.events.first { it is PlaybackTargetEvent.StatusChanged && it.isPlaying }
            }
            zero.controller.play()
            playing.await()
            assertTrue(zero.seekTargets.isEmpty(), "Loading at zero must not add an unsupported Seek command")
        } finally { zero.controller.disconnect() }
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

    @Test
    fun `delayed position poll cannot overwrite a newer seek when later positions are unavailable`() = runTest {
        val fixture = pollingFixture()
        val responseReady = CompletableDeferred<Unit>()
        val releaseResponse = CountDownLatch(1)
        try {
            fixture.beforePositionResponse = {
                responseReady.complete(Unit)
                check(releaseResponse.await(10, TimeUnit.SECONDS)) { "Timed out releasing position response" }
            }
            fixture.controller.seekTo(42)
            advanceTimeBy(351)
            runCurrent()
            responseReady.await()
            fixture.controller.seekTo(47)
            fixture.positionReply = "<RelTime>NOT_IMPLEMENTED</RelTime>"
            val retainedStatus = async(UnconfinedTestDispatcher(testScheduler)) {
                fixture.controller.events.first {
                    it is PlaybackTargetEvent.StatusChanged && !it.isPositionConfirmed
                } as PlaybackTargetEvent.StatusChanged
            }
            releaseResponse.countDown()
            assertEquals(47L, retainedStatus.await().positionSeconds)
            // Missing position support on later polls must retain the accepted scrub too.
            val subsequentStatus = async(UnconfinedTestDispatcher(testScheduler)) {
                fixture.controller.events.first { it is PlaybackTargetEvent.StatusChanged } as PlaybackTargetEvent.StatusChanged
            }
            advanceTimeBy(2_001)
            runCurrent()
            val status = subsequentStatus.await()
            assertEquals(47L, status.positionSeconds)
            assertFalse(status.isPositionConfirmed)
            assertTrue(status.isPlaying)
        } finally {
            releaseResponse.countDown()
            fixture.controller.disconnect()
        }
    }

    @Test
    fun `accepted transport commands discard older playing paused and terminal poll snapshots`() = runTest {
        // The controller is shared by every local/download/provider stream; URI and output format
        // do not participate in transport ordering.
        for ((sampledState, resume) in listOf("PLAYING" to false, "PAUSED_PLAYBACK" to true, "STOPPED" to false)) {
            val fixture = pollingFixture(pollingDispatcher = kotlinx.coroutines.Dispatchers.IO)
            val responseReady = CompletableDeferred<Unit>()
            val releaseResponse = CountDownLatch(1)
            try {
                fixture.transportState = sampledState
                fixture.beforePositionResponse = {
                    responseReady.complete(Unit)
                    check(releaseResponse.await(10, TimeUnit.SECONDS)) { "Timed out releasing position response" }
                }
                fixture.controller.seekTo(42)
                responseReady.await()
                if (resume) fixture.controller.play() else fixture.controller.pause()
                fixture.events.clear()
                val nextStatus = async(UnconfinedTestDispatcher(testScheduler)) {
                    fixture.controller.events.first { it is PlaybackTargetEvent.StatusChanged } as PlaybackTargetEvent.StatusChanged
                }
                releaseResponse.countDown()
                val status = nextStatus.await()
                assertEquals(resume, status.isPlaying, "The delayed $sampledState sample must not undo the accepted command")
                assertEquals(42L, status.positionSeconds)
                assertTrue(fixture.events.none { it is PlaybackTargetEvent.Ended || it is PlaybackTargetEvent.Stopped })
            } finally {
                releaseResponse.countDown()
                fixture.controller.disconnect()
            }
        }
    }

    @Test
    fun `backward seek resets completion evidence while an accepted seek to end still completes`() = runTest {
        for ((target, expectedCompleted) in listOf(10L to false, 120L to true)) {
            val fixture = pollingFixture(initialPosition = 119)
            try {
                val seekStatus = async(UnconfinedTestDispatcher(testScheduler)) {
                    fixture.controller.events.first {
                        it is PlaybackTargetEvent.StatusChanged && it.isPositionConfirmed && it.positionSeconds == target
                    }
                }
                fixture.controller.seekTo(target)
                seekStatus.await()
                fixture.transportState = "STOPPED"
                // Some renderers reset RelTime when stopped; completion uses the accepted seek window.
                fixture.positionSeconds = 0
                val terminal = async(UnconfinedTestDispatcher(testScheduler)) {
                    fixture.controller.events.first { it is PlaybackTargetEvent.Ended || it is PlaybackTargetEvent.Stopped }
                }
                val event = terminal.await()
                if (expectedCompleted) assertIs<PlaybackTargetEvent.Ended>(event)
                else assertIs<PlaybackTargetEvent.Stopped>(event)
            } finally { fixture.controller.disconnect() }
        }
    }

    @Test
    fun `confirmed TV remote backward seek resets completion evidence during playing and pause`() = runTest {
        // All DLNA sources share this renderer timeline; no local Seek command is issued here.
        for (state in listOf("PLAYING", "PAUSED_PLAYBACK")) {
            val fixture = pollingFixture(initialPosition = 119)
            try {
                if (state == "PAUSED_PLAYBACK") {
                    // A receiver may briefly reset its clock while applying a remote seek.
                    fixture.transportState = "TRANSITIONING"
                    fixture.positionSeconds = 0
                    val transition = async(UnconfinedTestDispatcher(testScheduler)) {
                        fixture.controller.events.first {
                            it is PlaybackTargetEvent.StatusChanged && it.isPositionConfirmed && it.positionSeconds == 0L
                        }
                    }
                    transition.await()
                }
                fixture.transportState = state
                fixture.positionSeconds = 10
                val remoteSeekStatus = async(UnconfinedTestDispatcher(testScheduler)) {
                    fixture.controller.events.first {
                        it is PlaybackTargetEvent.StatusChanged && it.isPositionConfirmed &&
                            it.positionSeconds == 10L && it.isPlaying == (state == "PLAYING")
                    }
                }
                remoteSeekStatus.await()
                assertTrue(fixture.seekTargets.isEmpty(), "The TV remote seek must not use the app's local revision")
                fixture.transportState = "STOPPED"
                fixture.positionSeconds = 0
                val terminal = async(UnconfinedTestDispatcher(testScheduler)) {
                    fixture.controller.events.first { it is PlaybackTargetEvent.Ended || it is PlaybackTargetEvent.Stopped }
                }
                assertIs<PlaybackTargetEvent.Stopped>(terminal.await())
            } finally { fixture.controller.disconnect() }
        }
    }

    @Test
    fun `stopped and transitioning position resets preserve genuine near-end completion`() = runTest {
        for (intermediateTransition in listOf(false, true)) {
            val fixture = pollingFixture(initialPosition = 119)
            try {
                fixture.positionSeconds = 0
                if (intermediateTransition) {
                    fixture.transportState = "TRANSITIONING"
                    val buffering = async(UnconfinedTestDispatcher(testScheduler)) {
                        fixture.controller.events.first {
                            it is PlaybackTargetEvent.StatusChanged && it.isPositionConfirmed && it.positionSeconds == 0L
                        }
                    }
                    buffering.await()
                }
                fixture.transportState = "STOPPED"
                val terminal = async(UnconfinedTestDispatcher(testScheduler)) {
                    fixture.controller.events.first { it is PlaybackTargetEvent.Ended || it is PlaybackTargetEvent.Stopped }
                }
                assertIs<PlaybackTargetEvent.Ended>(terminal.await())
            } finally { fixture.controller.disconnect() }
        }
    }

    private class PollingFixture(val controller: DlnaTargetController) {
        var transportState: String? = "PLAYING"
        var transportStatus = "OK"
        var positionSeconds = 42L
        var positionReply: String? = null
        @Volatile var beforePositionResponse: (() -> Unit)? = null
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
        pollingDispatcher: kotlinx.coroutines.CoroutineDispatcher = StandardTestDispatcher(testScheduler),
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
                    "<response><CurrentTransportState>$it</CurrentTransportState><CurrentTransportStatus>${fixture.transportStatus}</CurrentTransportStatus></response>"
                } ?: "<CurrentTransportStatus>${fixture.transportStatus}</CurrentTransportStatus>"
                action == "GetPositionInfo" -> {
                    val reply = fixture.positionReply ?: "<RelTime>${DidlLite.formatDuration(fixture.positionSeconds)}</RelTime>"
                    fixture.beforePositionResponse?.also { fixture.beforePositionResponse = null }?.invoke()
                    reply
                }
                action == "SetAVTransportURI" && fixture.setUriFaultCode != null ->
                    "<upnp:errorCode xmlns:upnp=\"urn:schemas-upnp-org:control-1-0\">${fixture.setUriFaultCode}</upnp:errorCode>"
                action == "Play" -> {
                    fixture.playCommands++
                    if (fixture.playIoFailure) throw IOException("Connection lost")
                    val fault = fixture.playFaultCode
                    if (fault != null) {
                        responseCode = 500
                        "<upnp:errorCode xmlns:upnp=\"urn:schemas-upnp-org:control-1-0\">$fault</upnp:errorCode>"
                    } else {
                        if (fixture.playStartsPlayback) fixture.transportState = "PLAYING"
                        "<Response/>"
                    }
                }
                action == "Pause" -> {
                    fixture.transportState = "PAUSED_PLAYBACK"
                    "<Response/>"
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
            pollingDispatcher = pollingDispatcher,
            monotonicMillis = { testScheduler.currentTime },
        )
        fixture = PollingFixture(controller)
        fixture.transportState = initialState
        fixture.positionSeconds = initialPosition
        controller.connect(controller.discover(500).single())
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { controller.events.collect { fixture.events += it } }
        val initialStatus = async(UnconfinedTestDispatcher(testScheduler)) {
            controller.events.first { it is PlaybackTargetEvent.StatusChanged }
        }
        controller.load("http://10.0.1.81/video", RendererStream("video/mp2t"), "Video", 120, startPosition, false)
        initialStatus.await()
        runCurrent()
        return fixture
    }

    private suspend fun TestScope.poll(fixture: PollingFixture) {
        fixture.controller.seekTo(42)
        advanceTimeBy(351)
        runCurrent()
    }
}
