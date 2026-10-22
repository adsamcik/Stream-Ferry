package com.adsamcik.streamferry.playback.proxy

import android.content.ContentResolver
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import com.adsamcik.streamferry.core.session.SessionRegistry
import com.adsamcik.streamferry.data.proxy.LocalProxyServer
import com.adsamcik.streamferry.source.api.DiagnosticSink
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class LocalContentProxyCancellationTest {
    @Test fun `stop cancels blocked provider open and closes a descriptor delivered afterwards`() {
        val opening = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val deliver = CountDownLatch(1)
        val descriptorClosed = CountDownLatch(1)
        val descriptor = mockk<ParcelFileDescriptor>(relaxed = true)
        every { descriptor.close() } answers { descriptorClosed.countDown() }
        val resolver = mockk<ContentResolver>()
        every { resolver.openFileDescriptor(any(), "r", any()) } answers {
            arg<CancellationSignal>(2).setOnCancelListener { cancelled.countDown() }
            opening.countDown()
            check(deliver.await(3, TimeUnit.SECONDS))
            descriptor
        }
        val sessions = SessionRegistry()
        val session = sessions.create("video/mp4", localFilePath = "content://selected/video")
        val proxy = LocalProxyServer(sessions, mockk<DiagnosticSink>(relaxed = true), resolver)
        try {
            val port = proxy.start("127.0.0.1").substringAfter(':').toInt()
            Socket("127.0.0.1", port).use { socket ->
                socket.getOutputStream().write("GET /session/${session.id}/stream HTTP/1.1\r\nHost: localhost\r\n\r\n".toByteArray())
                socket.getOutputStream().flush()
                assertTrue(opening.await(2, TimeUnit.SECONDS))
                proxy.stop()
                assertTrue(cancelled.await(2, TimeUnit.SECONDS))
                deliver.countDown()
                assertTrue(descriptorClosed.await(2, TimeUnit.SECONDS))
                verify(exactly = 1) { descriptor.close() }
            }
        } finally { deliver.countDown(); proxy.stop() }
    }
}
