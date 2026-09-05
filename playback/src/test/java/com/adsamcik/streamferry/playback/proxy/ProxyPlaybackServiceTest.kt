package com.adsamcik.streamferry.playback.proxy

import android.app.Application
import android.content.Context
import android.content.Intent
import com.adsamcik.streamferry.playback.MediaSessionController
import com.adsamcik.streamferry.source.api.DiagnosticSink
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = ProxyPlaybackServiceTest.TestApplication::class)
class ProxyPlaybackServiceTest {
    class TestApplication : Application(), PlaybackServiceOwner {
        override fun playbackServiceDependencies() = PlaybackServiceDependencies(
            mockk<MediaSessionController>(relaxed = true), mockk<DiagnosticSink>(relaxed = true),
        )
    }

    @Test fun `stop revokes service directly and never creates a queued stop command`() {
        val context = mockk<Context>(relaxed = true)
        every { context.stopService(any()) } returns false
        ProxyPlaybackService.stop(context)
        verify(exactly = 1) { context.stopService(match { it.component?.className == ProxyPlaybackService::class.java.name }) }
        verify(exactly = 0) { context.startService(any()) }
    }

    @Test fun `destroying an old instance cannot invalidate replacement foreground ownership`() {
        val old = Robolectric.buildService(ProxyPlaybackService::class.java).create()
        val replacement = Robolectric.buildService(ProxyPlaybackService::class.java).create()
        val start = Intent().setAction(ProxyPlaybackService.ACTION_START)
        try {
            old.get().onStartCommand(start, 0, 1)
            assertTrue(ProxyPlaybackService.isForegroundActive())
            replacement.get().onStartCommand(start, 0, 2)
            old.destroy()
            assertTrue(ProxyPlaybackService.isForegroundActive())
        } finally {
            replacement.destroy()
        }
        assertFalse(ProxyPlaybackService.isForegroundActive())
    }
}
