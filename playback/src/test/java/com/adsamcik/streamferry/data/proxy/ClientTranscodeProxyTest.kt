package com.adsamcik.streamferry.data.proxy

import com.adsamcik.streamferry.core.session.SessionRegistry
import com.adsamcik.streamferry.source.api.DiagnosticSink
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test
import java.net.Socket
import java.io.PipedInputStream
import java.io.PipedOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClientTranscodeProxyTest {
    @Test
    fun `DLNA receives bytes before encode EOF and HEAD never opens or drains output`() {
        val sessions = SessionRegistry()
        val proxy = LocalProxyServer(sessions, mockk<DiagnosticSink>(relaxed = true))
        val session = sessions.create("video/mp2t", isHls = false)
        val encoded = PipedInputStream(188 * 16)
        val encoder = PipedOutputStream(encoded)
        val source = mockk<ClientTranscodeSource>(relaxed = true)
        every { source.isProgressive } returns true
        every { source.openProgressive() } returns encoded
        every { source.release() } answers { encoder.close(); encoded.close() }
        proxy.registerClientTranscode(session.id, source)
        val port = proxy.start("127.0.0.1").substringAfter(':').toInt()
        fun connect(method: String) = Socket("127.0.0.1", port).apply {
            soTimeout = 3_000
            getOutputStream().write("$method /session/${session.id}/stream HTTP/1.1\r\nHost: localhost\r\nRange: bytes=0-\r\n\r\n".toByteArray())
            getOutputStream().flush()
        }
        try {
            connect("HEAD").use {
                val response = it.getInputStream().readBytes().decodeToString()
                assertTrue(response.startsWith("HTTP/1.1 200"))
                assertTrue(response.contains("Content-Type: video/mp2t"))
                assertTrue(response.contains("Accept-Ranges: none"))
                assertFalse(response.contains("Content-Length:"))
            }
            verify(exactly = 0) { source.openProgressive() }
            val first = ByteArray(188) { 0x47 }
            encoder.write(first); encoder.flush()
            connect("GET").use {
                val input = it.getInputStream()
                val headers = StringBuilder()
                while (!headers.endsWith("\r\n\r\n")) headers.append(input.read().also { value -> assertTrue(value >= 0) }.toChar())
                assertTrue(headers.startsWith("HTTP/1.1 200"))
                assertFalse(headers.contains("Content-Length:"))
                assertTrue(first.contentEquals(input.readNBytes(first.size)))
                // The producer remains open until after the first bytes have reached the receiver.
                val second = ByteArray(188) { 0x11 }
                encoder.write(second); encoder.flush()
                encoder.close()
                assertTrue(second.contentEquals(input.readBytes()))
            }
            verify(exactly = 1) { source.openProgressive() }
        } finally { proxy.stop() }
    }

    @Test
    fun `indexed init requests select the matching clip and HEAD cannot start export`() {
        val sessions = SessionRegistry()
        val proxy = LocalProxyServer(sessions, mockk<DiagnosticSink>(relaxed = true))
        val session = sessions.create("application/vnd.apple.mpegurl", isHls = true)
        val source = mockk<ClientTranscodeSource>(relaxed = true)
        every { source.initSegment(2, any()) } returns ClientTranscodeSource.Resource.Ready("init-two".toByteArray())
        proxy.registerClientTranscode(session.id, source)
        val port = proxy.start("127.0.0.1").substringAfter(':').toInt()
        fun request(method: String, segment: String): String = Socket("127.0.0.1", port).use {
            it.soTimeout = 3_000
            it.getOutputStream().write("$method /session/${session.id}/stream?seg=$segment HTTP/1.1\r\nHost: localhost\r\n\r\n".toByteArray())
            it.getOutputStream().flush()
            it.getInputStream().readBytes().decodeToString()
        }
        try {
            val get = request("GET", "init_2")
            assertTrue(get.startsWith("HTTP/1.1 200"))
            assertTrue(get.endsWith("init-two"))
            val head = request("HEAD", "init_2")
            assertTrue(head.startsWith("HTTP/1.1 200"))
            assertTrue(head.endsWith("\r\n\r\n"))
            for (malformed in listOf("init_", "init_-1", "init_2147483648", "init_2junk")) {
                assertTrue(request("GET", malformed).startsWith("HTTP/1.1 404"))
            }
            verify(exactly = 1) { source.initSegment(2, allowExport = true) }
            verify(exactly = 1) { source.initSegment(2, allowExport = false) }
            verify(exactly = 2) { source.initSegment(any<Int>(), any()) }
        } finally {
            proxy.stop()
        }
    }
}
