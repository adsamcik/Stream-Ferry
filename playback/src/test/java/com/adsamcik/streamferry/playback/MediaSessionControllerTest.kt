package com.adsamcik.streamferry.playback

import android.app.Notification
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.VolumeProvider
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import io.mockk.verify
import kotlin.test.*
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaSessionControllerTest {
    private val metadataUpdates = mutableListOf<MediaMetadata>()
    private val stateUpdates = mutableListOf<PlaybackState>()
    private val callbacks = mutableListOf<MediaSession.Callback>()
    private val volumeProviders = mutableListOf<VolumeProvider>()

    @Before fun captureSessionBoundary() {
        // Robolectric's MediaController does not deliver binder commands to MediaSession or reflect
        // its metadata. Capture the framework boundary and exercise the callbacks registered there.
        mockkConstructor(MediaSession::class)
        every { anyConstructed<MediaSession>().setMetadata(capture(metadataUpdates)) } answers { callOriginal() }
        every { anyConstructed<MediaSession>().setPlaybackState(capture(stateUpdates)) } answers { callOriginal() }
        every { anyConstructed<MediaSession>().setCallback(capture(callbacks), any<Handler>()) } answers { callOriginal() }
        every { anyConstructed<MediaSession>().setPlaybackToRemote(capture(volumeProviders)) } answers { callOriginal() }
    }

    @After fun releaseSessionCapture() = unmockkConstructor(MediaSession::class)

    @Test fun `position updates reuse notification while pause and restart refresh controls`() {
        val context = RuntimeEnvironment.getApplication()
        val controller = MediaSessionController(context, mockk(relaxed = true), null)
        val status = status(protocol = "DLNA")
        try {
            controller.update(status)
            val first = controller.currentNotification()
            controller.update(status.copy(positionSeconds = 2, measuredThroughputBps = 3000))
            assertSame(first, controller.currentNotification())
            controller.update(status.copy(isPlaying = false))
            assertNotSame(first, controller.currentNotification())
            controller.update(null)
            val paused = controller.currentNotification()
            controller.update(status.copy(isPlaying = false))
            assertNotSame(paused, controller.currentNotification(), "Restart must republish even when content matches")
        } finally { controller.release() }
    }

    @Test fun `cast and dlna share an active app session and a single playback card`() {
        val context = RuntimeEnvironment.getApplication()
        val controller = MediaSessionController(context, mockk(relaxed = true), null)
        val frameworkSession = controller.frameworkSession()
        try {
            controller.update(status(protocol = "DLNA"))
            assertTrue(frameworkSession.isActive)
            assertNotNull(controller.currentNotification().mediaSessionToken())

            controller.update(status(protocol = "CAST"))
            assertTrue(frameworkSession.isActive)
            assertEquals(controller.token, controller.currentNotification().mediaSessionToken())
            val metadata = metadataUpdates.last()
            assertEquals("Movie", metadata.getString(MediaMetadata.METADATA_KEY_TITLE))
            assertEquals(3_600_000L, metadata.getLong(MediaMetadata.METADATA_KEY_DURATION))
            assertEquals(PlaybackState.STATE_PLAYING, stateUpdates.last().state)

            controller.update(status(protocol = "CAST").copy(isPlaying = false, phase = PlaybackPhase.PAUSED))
            assertTrue(frameworkSession.isActive, "Paused playback must retain notification controls")
            assertEquals(PlaybackState.STATE_PAUSED, stateUpdates.last().state)
            controller.update(null)
            assertFalse(frameworkSession.isActive)
        } finally { controller.release() }
    }

    @Test fun `registered transport and remote volume callbacks route through the app for both protocols`() {
        val context = RuntimeEnvironment.getApplication()
        for (protocol in listOf("CAST", "DLNA")) {
            val transport = mockk<MediaSessionController.Transport>(relaxed = true)
            val controller = MediaSessionController(context, transport, null)
            try {
                controller.update(status(protocol).copy(volumeSupported = true, volume = 0.5f))
                val controls = callbacks.last()
                controls.onPause()
                controls.onPlay()
                controls.onSeekTo(120_000)
                controls.onCustomAction(PlaybackNotificationFactory.ACTION_RWND, null)
                controls.onCustomAction(PlaybackNotificationFactory.ACTION_FFWD, null)
                controls.onCustomAction(PlaybackNotificationFactory.ACTION_STOP, null)
                volumeProviders.last().onAdjustVolume(AudioManager.ADJUST_RAISE)

                verify(exactly = 1) { transport.onPause() }
                verify(exactly = 1) { transport.onPlay() }
                verify(exactly = 1) { transport.onSeekTo(120) }
                verify(exactly = 1) { transport.onSkip(-30) }
                verify(exactly = 1) { transport.onSkip(30) }
                verify(exactly = 1) { transport.onStop() }
                verify(exactly = 1) { transport.onAdjustVolume(AudioManager.ADJUST_RAISE) }
            } finally { controller.release() }
        }
    }

    @Test fun `unknown duration and preparation gate unsupported system commands`() {
        val context = RuntimeEnvironment.getApplication()
        val transport = mockk<MediaSessionController.Transport>(relaxed = true)
        val controller = MediaSessionController(context, transport, null)
        try {
            controller.update(status("CAST").copy(durationSeconds = null))
            assertEquals(0L, stateUpdates.last().actions and PlaybackState.ACTION_SEEK_TO)
            assertFalse(metadataUpdates.last().containsKey(MediaMetadata.METADATA_KEY_DURATION))
            val controls = callbacks.last()
            controls.onSeekTo(120_000)
            controller.update(status("CAST").copy(phase = PlaybackPhase.LOADING, volumeSupported = false))
            val preparing = stateUpdates.last()
            assertEquals(PlaybackState.STATE_CONNECTING, preparing.state)
            assertEquals(0f, preparing.playbackSpeed)
            assertEquals(PlaybackState.ACTION_STOP, preparing.actions)
            assertEquals(listOf(PlaybackNotificationFactory.ACTION_STOP), preparing.customActions.map { it.action })
            controls.onPlay()
            controls.onPause()
            controls.onCustomAction(PlaybackNotificationFactory.ACTION_FFWD, null)
            volumeProviders.last().onAdjustVolume(AudioManager.ADJUST_RAISE)

            verify(exactly = 0) { transport.onSeekTo(any()) }
            verify(exactly = 0) { transport.onPlay() }
            verify(exactly = 0) { transport.onPause() }
            verify(exactly = 0) { transport.onSkip(any()) }
            verify(exactly = 0) { transport.onAdjustVolume(any()) }
        } finally { controller.release() }
    }

    private fun status(protocol: String) = PlaybackStatus(
        "TV", protocol, isPlaying = true, isBuffering = false, positionSeconds = 1,
        durationSeconds = 3600, streamMode = "Direct", currentBitrateBps = 1000,
        measuredThroughputBps = 2000, adaptiveNote = "", title = "Movie", phase = PlaybackPhase.PLAYING,
    )

    private fun Notification.mediaSessionToken(): MediaSession.Token? =
        extras.getParcelable(Notification.EXTRA_MEDIA_SESSION, MediaSession.Token::class.java)

    private fun MediaSessionController.frameworkSession(): MediaSession =
        MediaSessionController::class.java.getDeclaredField("session")
            .apply { isAccessible = true }
            .get(this) as MediaSession
}
