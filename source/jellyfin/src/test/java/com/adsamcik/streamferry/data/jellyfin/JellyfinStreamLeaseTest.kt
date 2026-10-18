package com.adsamcik.streamferry.data.jellyfin

import com.adsamcik.streamferry.core.http.ByteRange
import com.adsamcik.streamferry.domain.HlsSegmentFormat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class JellyfinStreamLeaseTest {
    private val upstream = JellyfinUpstreamSource(
        url = "https://example.test/private/master.m3u8?api_key=secret",
        authHeader = "Bearer private-token",
        contentType = "application/vnd.apple.mpegurl",
        outputContainer = "m3u8",
        hlsSegmentFormat = HlsSegmentFormat.MPEG2_TS,
        isHls = true,
        isTranscoding = true,
        isByteSeekable = false,
        totalLength = null,
    )

    @Test fun `equivalent playlist references have stable opaque lease scoped identities`() = runTest {
        val lease = JellyfinStreamLease(upstream, OkHttpClient())
        val first = lease.resolve("segment.ts?api_key=secret").getOrThrow()!!
        repeat(100) {
            assertEquals(first, lease.resolve("./segment.ts?api_key=secret").getOrThrow())
        }
        assertEquals(first, lease.resolve("https://example.test/private/segment.ts?api_key=secret#fragment").getOrThrow())
        assertFalse(first.opaqueId.contains("segment"))
        assertFalse(first.opaqueId.contains("secret"))
        assertNotEquals(first, JellyfinStreamLease(upstream, OkHttpClient()).resolve("segment.ts?api_key=secret").getOrThrow())
    }

    @Test fun `bounded mappings preserve retained windows and reject evicted parent resolution`() = runTest {
        val lease = JellyfinStreamLease(upstream, OkHttpClient(), resourceCapacity = 3)
        val playlist = lease.resolve("nested/child.m3u8").getOrThrow()!!
        val protectedSegment = lease.resolve("kept.ts", playlist).getOrThrow()!!
        lease.retainResources(setOf(playlist, protectedSegment))
        val expired = lease.resolve("old.ts").getOrThrow()!!
        repeat(50) { lease.resolve("new$it.ts").getOrThrow() }
        assertTrue(lease.open(expired).isFailure)
        assertEquals(protectedSegment, lease.resolve("kept.ts", playlist).getOrThrow())
        lease.retainResources(emptySet())
        repeat(4) { lease.resolve("next$it.ts").getOrThrow() }
        assertTrue(lease.resolve("wrong-relative.ts", playlist).isFailure)
        // Reintroduced references still receive their original identity after their mapping expires.
        assertEquals(expired, lease.resolve("old.ts").getOrThrow())
    }

    @Test fun `a fully retained resource table fails safely instead of revoking advertised URLs`() = runTest {
        val lease = JellyfinStreamLease(upstream, OkHttpClient(), resourceCapacity = 2)
        val refs = setOf(lease.resolve("one.ts").getOrThrow()!!, lease.resolve("two.ts").getOrThrow()!!)
        lease.retainResources(refs)
        assertTrue(lease.resolve("three.ts").isFailure)
        refs.forEach { ref ->
            assertEquals(ref, lease.resolve(if (ref == refs.first()) "one.ts" else "two.ts").getOrThrow())
        }
    }

    @Test fun `root playlist children resolve against the final redirect directory`() = runTest {
        val fixture = Fixture()
        val opening = async { fixture.lease.open() }
        runCurrent()
        val redirectBody = ClosingBody()
        fixture.calls.last().respond(redirectBody, 302, "/new/master.m3u8?api_key=secret")
        runCurrent()
        fixture.calls.last().respond(ClosingBody())
        opening.await().getOrThrow().close()
        assertEquals(1, redirectBody.closes)

        val segment = fixture.lease.resolve("segment.ts").getOrThrow()!!
        val segmentOpen = async { fixture.lease.open(segment) }
        runCurrent()
        assertEquals("https://example.test/new/segment.ts", fixture.calls.last().request.url.toString())
        assertEquals("Bearer private-token", fixture.calls.last().request.header("Authorization"))
        fixture.calls.last().respond(ClosingBody())
        segmentOpen.await().getOrThrow().close()
    }

    @Test fun `nested redirect bases survive repeated references and expire with bounded resource entries`() = runTest {
        val fixture = Fixture(resourceCapacity = 2)
        val playlist = fixture.lease.resolve("variant.m3u8").getOrThrow()!!
        fixture.lease.retainResources(setOf(playlist))
        val opening = async { fixture.lease.open(playlist) }
        runCurrent()
        fixture.calls.last().respond(ClosingBody(), 302, "/redirected/nested/variant.m3u8")
        runCurrent()
        fixture.calls.last().respond(ClosingBody())
        opening.await().getOrThrow().close()
        repeat(5) { fixture.lease.resolve("discard$it.ts").getOrThrow() }
        assertEquals(playlist, fixture.lease.resolve("./variant.m3u8").getOrThrow())
        val relative = fixture.lease.resolve("segment.ts", playlist).getOrThrow()!!
        assertEquals(relative, fixture.lease.resolve("https://example.test/redirected/nested/segment.ts").getOrThrow())

        fixture.lease.retainResources(emptySet())
        repeat(3) { fixture.lease.resolve("replacement$it.ts").getOrThrow() }
        assertTrue(fixture.lease.resolve("segment.ts", playlist).isFailure)
    }

    @Test fun `overlapping root and nested playlist responses keep their own redirect bases`() = runTest {
        for (nested in listOf(false, true)) {
            val fixture = Fixture()
            val playlist = if (nested) fixture.lease.resolve("variant.m3u8").getOrThrow()!! else null
            val responses = listOf("a", "b").map { directory ->
                val opening = async {
                    if (playlist == null) fixture.lease.open() else fixture.lease.open(playlist)
                }
                runCurrent()
                fixture.calls.last().respond(ClosingBody(), 302, "/$directory/playlist.m3u8")
                runCurrent()
                fixture.calls.last().respond(ClosingBody())
                opening.await().getOrThrow()
            }
            try {
                // Both responses remain open. Resolve the older one only after the newer open has
                // completed, exactly as concurrent gateway requests reading/re-writing bodies can do.
                for ((index, directory) in listOf("a", "b").withIndex()) {
                    val resolver = assertNotNull(responses[index].playlistResolver)
                    val segment = resolver("segment.ts").getOrThrow()!!
                    assertEquals(fixture.lease.resolve("https://example.test/$directory/segment.ts").getOrThrow(), segment)
                    assertEquals(segment, resolver("./segment.ts").getOrThrow())
                    assertEquals(null, resolver("https://other.test/segment.ts").getOrThrow())
                    val opening = async { fixture.lease.open(segment) }
                    runCurrent()
                    assertEquals("https://example.test/$directory/segment.ts", fixture.calls.last().request.url.toString())
                    fixture.calls.last().respond(ClosingBody())
                    opening.await().getOrThrow().close()
                }
            } finally {
                responses.forEach { it.close() }
            }
        }
    }

    @Test fun `cancelling a header wait cancels the upstream call promptly`() = runTest {
        val fixture = Fixture()
        val waiting = async { fixture.lease.open() }
        runCurrent()
        waiting.cancelAndJoin()
        assertTrue(waiting.isCancelled)
        verify(exactly = 1) { fixture.calls.single().call.cancel() }
    }

    @Test fun `response delivered concurrently with cancellation is closed`() = runTest {
        val fixture = Fixture()
        val waiting = async { fixture.lease.open() }
        runCurrent()
        val body = ClosingBody()
        fixture.calls.single().respond(body)
        waiting.cancelAndJoin()
        assertEquals(1, body.closes)
    }

    @Test fun `root and resolved resource ranges request identity encoding across trusted redirects`() = runTest {
        for (resolvedResource in listOf(false, true)) {
            val fixture = Fixture()
            val resource = if (resolvedResource) fixture.lease.resolve("segment.ts").getOrThrow()!! else null
            val waiting = async {
                if (resource == null) fixture.lease.open(ByteRange(2, 5))
                else fixture.lease.open(resource, ByteRange(2, 5))
            }
            runCurrent()
            val initial = fixture.calls.single()
            assertEquals("bytes=2-5", initial.request.header("Range"))
            assertEquals("identity", initial.request.header("Accept-Encoding"))
            initial.respond(ClosingBody(), 302, "/redirected/media")
            runCurrent()
            val redirected = fixture.calls.last()
            assertEquals(2, fixture.calls.size)
            assertEquals("bytes=2-5", redirected.request.header("Range"))
            assertEquals("identity", redirected.request.header("Accept-Encoding"))
            val body = ClosingBody()
            redirected.respond(body, 206)
            waiting.await().getOrThrow().close()
            assertEquals(1, body.closes)
        }
    }

    @Test fun `successful response passes safe validation headers and ownership to the stream consumer`() = runTest {
        val fixture = Fixture()
        val waiting = async { fixture.lease.open() }
        runCurrent()
        val body = ClosingBody()
        fixture.calls.single().respond(body, headers = mapOf(
            "Date" to "Sun, 18 Oct 2026 08:00:00 GMT",
            "Last-Modified" to "Sun, 18 Oct 2026 07:00:00 GMT",
            "Content-Encoding" to "identity",
            "Set-Cookie" to "session=private-token",
            "Authorization" to "Bearer private-token",
        ))
        val response = waiting.await().getOrThrow()
        assertEquals(mapOf(
            "Content-Encoding" to "identity",
            "Last-Modified" to "Sun, 18 Oct 2026 07:00:00 GMT",
            "Date" to "Sun, 18 Oct 2026 08:00:00 GMT",
        ), response.headers)
        assertEquals(0, body.closes)
        assertEquals("Bearer private-token", fixture.calls.single().request.header("Authorization"))
        response.close()
        assertEquals(1, body.closes)
    }

    @Test fun `redirect header waits remain cancellable and close earlier responses`() = runTest {
        val fixture = Fixture()
        val waiting = async { fixture.lease.open() }
        runCurrent()
        val redirectBody = ClosingBody()
        fixture.calls.single().respond(redirectBody, 302, "/private/redirected.m3u8")
        runCurrent()
        assertEquals(1, redirectBody.closes)
        assertEquals(2, fixture.calls.size)
        waiting.cancelAndJoin()
        verify(exactly = 1) { fixture.calls.last().call.cancel() }
    }

    @Test fun `upstream failures do not expose private locators or authorization`() = runTest {
        val fixture = Fixture()
        val waiting = async { fixture.lease.open() }
        runCurrent()
        val pending = fixture.calls.single()
        pending.callback.onFailure(pending.call, IOException("https://example.test/private?api_key=secret Bearer private-token"))
        val failure = waiting.await().exceptionOrNull()!!
        assertEquals("Source stream request failed", failure.message)
        assertEquals(null, failure.cause)
    }

    @Test fun `timeout classification survives both failure boundaries without exposing private diagnostics`() = runTest {
        for (callbackFailure in listOf(true, false)) {
            for (error in listOf(
                SocketTimeoutException("https://example.test/private?api_key=secret Bearer private-token"),
                InterruptedIOException("https://example.test/private?api_key=secret Bearer private-token"),
            )) {
                val fixture = Fixture()
                if (!callbackFailure) fixture.failCallCreation(error)
                val waiting = async { fixture.lease.open() }
                runCurrent()
                if (callbackFailure) {
                    val pending = fixture.calls.single()
                    pending.callback.onFailure(pending.call, error)
                }
                val failure = waiting.await().exceptionOrNull()!!
                assertTrue(failure is InterruptedIOException)
                assertEquals(error.javaClass, failure.javaClass)
                assertFalse(failure.toString().contains("secret"))
                assertFalse(failure.toString().contains("example.test"))
                assertFalse(failure.toString().contains("private-token"))
                assertEquals(null, failure.cause)
            }
        }
    }

    private inner class Fixture(resourceCapacity: Int = 16_384) {
        val calls = mutableListOf<PendingCall>()
        private val client = mockk<OkHttpClient>()
        private val builder = mockk<OkHttpClient.Builder>()
        private val streamClient = mockk<OkHttpClient>()
        val lease: JellyfinStreamLease

        fun failCallCreation(error: IOException) {
            every { streamClient.newCall(any()) } throws error
        }

        init {
            every { client.newBuilder() } returns builder
            every { builder.followRedirects(false) } returns builder
            every { builder.followSslRedirects(false) } returns builder
            every { builder.build() } returns streamClient
            every { streamClient.newCall(any()) } answers {
                PendingCall(firstArg()).also { calls += it }.call
            }
            lease = JellyfinStreamLease(upstream, client, resourceCapacity)
        }
    }

    private class PendingCall(val request: Request) {
        val call = mockk<Call>(relaxed = true)
        lateinit var callback: Callback
        init {
            every { call.enqueue(any()) } answers { callback = firstArg() }
        }
        fun respond(body: ResponseBody, code: Int = 200, redirect: String? = null, headers: Map<String, String> = emptyMap()) {
            callback.onResponse(call, Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(code).message("Response").body(body).apply {
                    redirect?.let { header("Location", it) }
                    headers.forEach { (name, value) -> header(name, value) }
                }.build())
        }
    }

    private class ClosingBody : ResponseBody() {
        var closes = 0
        private val bytes = object : ForwardingSource(Buffer()) {
            override fun close() { closes++; super.close() }
        }.buffer()
        override fun contentType() = null
        override fun contentLength() = 0L
        override fun source(): BufferedSource = bytes
    }
}
