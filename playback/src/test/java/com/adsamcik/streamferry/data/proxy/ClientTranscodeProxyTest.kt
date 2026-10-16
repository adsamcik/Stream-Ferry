package com.adsamcik.streamferry.data.proxy

import com.adsamcik.streamferry.core.session.SessionRegistry
import com.adsamcik.streamferry.source.api.DiagnosticSink
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test
import java.net.Socket
import kotlin.test.assertTrue

class ClientTranscodeProxyTest {
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
