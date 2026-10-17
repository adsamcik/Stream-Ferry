package com.adsamcik.streamferry.data.cast

import androidx.mediarouter.media.MediaRouter
import com.adsamcik.streamferry.core.stream.Protocol
import com.adsamcik.streamferry.domain.DiscoveredTarget
import com.adsamcik.streamferry.domain.PlaybackTargetEvent
import com.adsamcik.streamferry.domain.RendererStream
import com.google.android.gms.cast.CastDevice
import com.google.android.gms.cast.MediaError
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManager
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.gms.common.api.PendingResult
import com.google.android.gms.common.api.ResultCallback
import com.google.android.gms.common.api.Status
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CastProgressTest {
    private val dispatcher = StandardTestDispatcher()
    private val client = mockk<RemoteMediaClient>(relaxed = true)
    private val session = mockk<CastSession>(relaxed = true)
    private val mediaCallback = slot<RemoteMediaClient.Callback>()
    private val sessionListener = slot<SessionManagerListener<CastSession>>()
    private val pending = mockk<PendingResult<RemoteMediaClient.MediaChannelResult>>(relaxed = true)
    private val resultCallback = slot<ResultCallback<RemoteMediaClient.MediaChannelResult>>()
    private lateinit var controller: CastTargetController
    private var elapsedMs = 1_000L
    private var cachedPositionMs = 120_000L
    private var receiverPositionMs = 120_000L
    private var cachedState = MediaStatus.PLAYER_STATE_PLAYING
    private var receiverState = MediaStatus.PLAYER_STATE_PLAYING
    private var cachedMediaUrl = "http://phone/previous/index.m3u8"
    private var loadStatus = Status.RESULT_SUCCESS
    private var connected = true
    private var completeRefresh = true
    private var refreshStatus = Status.RESULT_SUCCESS
    private val events = mutableListOf<PlaybackTargetEvent>()

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        val context = mockk<CastContext>(relaxed = true)
        val manager = mockk<SessionManager>(relaxed = true)
        val router = mockk<MediaRouter>(relaxed = true)
        val route = mockk<MediaRouter.RouteInfo>(relaxed = true)
        val device = mockk<CastDevice>(relaxed = true)
        val status = mockk<MediaStatus>(relaxed = true)
        val mediaInfo = mockk<MediaInfo>(relaxed = true)
        val loadPending = mockk<PendingResult<RemoteMediaClient.MediaChannelResult>>(relaxed = true)
        mockkStatic(MediaRouter::class, CastDevice::class)
        every { MediaRouter.getInstance(any()) } returns router
        every { CastDevice.getFromBundle(any()) } returns device
        every { router.routes } returns listOf(route)
        every { route.id } returns "route"
        every { device.deviceId } returns "device"
        every { context.sessionManager } returns manager
        every { manager.currentCastSession } returns session
        every { manager.addSessionManagerListener(capture(sessionListener), CastSession::class.java) } just Runs
        every { session.castDevice } returns device
        every { session.sessionId } returns "session"
        every { session.isConnected } answers { connected }
        every { session.remoteMediaClient } returns client
        every { client.registerCallback(capture(mediaCallback)) } just Runs
        every { client.mediaStatus } returns status
        every { status.playerState } answers { cachedState }
        every { status.mediaInfo } returns mediaInfo
        every { mediaInfo.contentId } answers { cachedMediaUrl }
        every { client.approximateStreamPosition } answers { cachedPositionMs }
        every { client.requestStatus() } returns pending
        every { client.load(any<MediaLoadRequestData>()) } returns loadPending
        every { loadPending.setResultCallback(any<ResultCallback<RemoteMediaClient.MediaChannelResult>>()) } answers {
            val result = mockk<RemoteMediaClient.MediaChannelResult>()
            every { result.status } returns loadStatus
            firstArg<ResultCallback<RemoteMediaClient.MediaChannelResult>>().onResult(result)
        }
        every { pending.setResultCallback(capture(resultCallback)) } answers {
            if (completeRefresh) finishRefresh()
        }
        controller = CastTargetController(RuntimeEnvironment.getApplication(), { context }, mockk(relaxed = true)) {
            elapsedMs
        }
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    private fun progressTest(block: suspend TestScope.() -> Unit) = runTest(dispatcher) {
        try {
            block()
        } finally {
            // Stop the production-owned heartbeat before runTest drains scheduled foreground work.
            controller.disconnect()
        }
    }

    private fun finishRefresh() {
        if (refreshStatus.isSuccess) {
            cachedPositionMs = receiverPositionMs
            cachedState = receiverState
        }
        val result = mockk<RemoteMediaClient.MediaChannelResult>()
        every { result.status } returns refreshStatus
        resultCallback.captured.onResult(result)
        // Deliberately no onStatusUpdated: recovery must also work when the SDK stays quiet.
    }

    private suspend fun TestScope.startPlaying() {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { controller.events.collect { events += it } }
        controller.connect(DiscoveredTarget("route", "TV", Protocol.CAST, mockk(relaxed = true), null))
        mediaCallback.captured.onStatusUpdated()
        runCurrent()
    }

    private fun TestScope.tick(ms: Long = 5_000L) {
        elapsedMs += ms
        advanceTimeBy(ms)
        runCurrent()
    }

    private fun latestPosition() = events.filterIsInstance<PlaybackTargetEvent.StatusChanged>().last()

    @Test fun `first heartbeat after deep sleep reconciles a stale estimate with TV progress`() = progressTest {
        startPlaying()
        // The phone's coroutine did not run for ten minutes, while the TV continued independently.
        elapsedMs += 600_000L
        receiverPositionMs = 720_000L
        tick()
        verify(exactly = 1) { client.requestStatus() }
        assertEquals(720L, latestPosition().positionSeconds)
        tick()
        verify(exactly = 1) { client.requestStatus() }
    }

    @Test fun `quiet playback periodically refreshes receiver status without polling every heartbeat`() = progressTest {
        startPlaying()
        repeat(5) { tick() }
        verify(exactly = 0) { client.requestStatus() }
        receiverPositionMs = 150_000L
        tick()
        verify(exactly = 1) { client.requestStatus() }
        assertEquals(150L, latestPosition().positionSeconds)
        repeat(6) { tick() }
        verify(exactly = 2) { client.requestStatus() }
    }

    @Test fun `foreground refresh corrects stale paused status and restarts checkpoints immediately`() = progressTest {
        startPlaying()
        cachedState = MediaStatus.PLAYER_STATE_PAUSED
        mediaCallback.captured.onStatusUpdated()
        receiverPositionMs = 720_000L
        controller.refreshPlaybackStatus()
        runCurrent()
        assertEquals(720L, latestPosition().positionSeconds)
        assertTrue(latestPosition().isPlaying)
        cachedPositionMs = 725_000L
        tick()
        assertEquals(725L, latestPosition().positionSeconds)
        verify(exactly = 1) { client.requestStatus() }
    }

    @Test fun `session recovery restarts progress without a new SDK status callback`() = progressTest {
        startPlaying()
        connected = false
        sessionListener.captured.onSessionSuspended(session, 1)
        val countBeforeSuspend = events.size
        tick()
        assertEquals(countBeforeSuspend, events.size)
        connected = true
        receiverPositionMs = 730_000L
        sessionListener.captured.onSessionResumed(session, true)
        runCurrent()
        assertEquals(730L, latestPosition().positionSeconds)
        cachedPositionMs = 735_000L
        tick()
        assertEquals(735L, latestPosition().positionSeconds)
    }

    @Test fun `refreshed paused and buffering positions stay fixed without continued polling`() = progressTest {
        startPlaying()
        for (state in listOf(MediaStatus.PLAYER_STATE_PAUSED, MediaStatus.PLAYER_STATE_BUFFERING)) {
            receiverState = state
            receiverPositionMs = 700_000L
            controller.refreshPlaybackStatus()
            runCurrent()
            assertFalse(latestPosition().isPlaying)
            assertEquals(700L, latestPosition().positionSeconds)
            val countAfterRefresh = events.size
            repeat(12) { tick() }
            assertEquals(countAfterRefresh, events.size)
        }
        verify(exactly = 2) { client.requestStatus() }
    }

    @Test fun `overlapping foreground refreshes share one request and timeout permits a later retry`() = progressTest {
        startPlaying()
        completeRefresh = false
        repeat(3) { controller.refreshPlaybackStatus() }
        runCurrent()
        verify(exactly = 1) { client.requestStatus() }
        repeat(4) { tick() }
        verify(exactly = 1) { pending.cancel() }
        verify(exactly = 1) { client.requestStatus() }
        completeRefresh = true
        receiverPositionMs = 750_000L
        repeat(2) { tick() }
        verify(exactly = 2) { client.requestStatus() }
        assertEquals(750L, latestPosition().positionSeconds)
        assertTrue(events.none { it is PlaybackTargetEvent.Disconnected || it is PlaybackTargetEvent.Error })
    }

    @Test fun `failed status refresh retains playback and retries on the bounded cadence`() = progressTest {
        startPlaying()
        refreshStatus = Status.RESULT_INTERNAL_ERROR
        controller.refreshPlaybackStatus()
        runCurrent()
        repeat(5) { tick() }
        verify(exactly = 1) { client.requestStatus() }
        refreshStatus = Status.RESULT_SUCCESS
        receiverPositionMs = 150_000L
        tick()
        verify(exactly = 2) { client.requestStatus() }
        assertEquals(150L, latestPosition().positionSeconds)
        assertTrue(events.none { it is PlaybackTargetEvent.Disconnected || it is PlaybackTargetEvent.Error })
    }

    @Test fun `disconnect cancels outstanding refresh and rejects late results and callbacks`() = progressTest {
        startPlaying()
        completeRefresh = false
        controller.refreshPlaybackStatus()
        runCurrent()
        controller.disconnect()
        verify(exactly = 1) { pending.cancel() }
        val countAfterDisconnect = events.size
        receiverPositionMs = 999_000L
        finishRefresh()
        mediaCallback.captured.onStatusUpdated()
        sessionListener.captured.onSessionResumed(session, true)
        controller.refreshPlaybackStatus()
        repeat(6) { tick() }
        assertEquals(countAfterDisconnect, events.size)
        verify(exactly = 1) { client.requestStatus() }
    }

    @Test fun `reload rejects prior timeline before and after load while current pause resume remains live`() = progressTest {
        startPlaying()
        val previousCallback = mediaCallback.captured
        val beforeReload = events.size
        controller.prepareReload()
        cachedPositionMs = 201_000L
        previousCallback.onStatusUpdated()
        controller.refreshPlaybackStatus()
        runCurrent()
        tick(30_000L)
        assertEquals(beforeReload, events.size)
        verify(exactly = 0) { client.requestStatus() }

        val newUrl = "http://phone/replacement/index.m3u8"
        controller.load(newUrl, RendererStream("application/vnd.apple.mpegurl"), "Movie", 400, 0, false)
        val currentCallback = mediaCallback.captured
        // A fresh callback can still observe the previous media cached by the shared SDK client.
        currentCallback.onStatusUpdated()
        assertEquals(beforeReload, events.size)
        cachedMediaUrl = newUrl
        cachedPositionMs = 1_000L
        cachedState = MediaStatus.PLAYER_STATE_PAUSED
        // A callback already queued for the previous registration remains stale even after the SDK
        // cache has switched to the new URI.
        previousCallback.onStatusUpdated()
        assertEquals(beforeReload, events.size)
        currentCallback.onStatusUpdated()
        assertEquals(1L, latestPosition().positionSeconds)
        assertFalse(latestPosition().isPlaying)

        cachedMediaUrl = "http://phone/previous/index.m3u8"
        cachedPositionMs = 202_000L
        cachedState = MediaStatus.PLAYER_STATE_PLAYING
        val pausedCount = events.size
        currentCallback.onStatusUpdated()
        assertEquals(pausedCount, events.size)
        cachedMediaUrl = newUrl
        cachedPositionMs = 2_000L
        currentCallback.onStatusUpdated()
        assertEquals(2L, latestPosition().positionSeconds)
        assertTrue(latestPosition().isPlaying)
        cachedPositionMs = 7_000L
        tick()
        assertEquals(7L, latestPosition().positionSeconds)
    }

    @Test fun `reload ignores old media errors but preserves current failures and load rejection`() = progressTest {
        startPlaying()
        val previousCallback = mediaCallback.captured
        controller.prepareReload()
        val newUrl = "http://phone/replacement/index.m3u8"
        controller.load(newUrl, RendererStream("video/mp4"), "Movie", 400, 0, true)
        val error = mockk<MediaError>(relaxed = true)
        every { error.detailedErrorCode } returns MediaError.DetailedErrorCode.MEDIA_DECODE
        previousCallback.onMediaError(error)
        mediaCallback.captured.onMediaError(error)
        assertTrue(events.none { it is PlaybackTargetEvent.Error })
        cachedMediaUrl = newUrl
        mediaCallback.captured.onMediaError(error)
        assertEquals(1, events.filterIsInstance<PlaybackTargetEvent.Error>().size)
        loadStatus = Status.RESULT_INTERNAL_ERROR
        assertFailsWith<IllegalStateException> {
            controller.load("http://phone/rejected/video", RendererStream("video/mp4"), "Movie", 400, 0, true)
        }
    }
}
