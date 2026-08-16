package com.adsamcik.streamferry.integration

import com.adsamcik.streamferry.core.session.SessionRegistry
import com.adsamcik.streamferry.core.stream.Protocol
import com.adsamcik.streamferry.core.stream.TargetCapabilities
import com.adsamcik.streamferry.data.jellyfin.HttpJellyfinRepository
import com.adsamcik.streamferry.data.jellyfin.JellyfinClient
import com.adsamcik.streamferry.data.proxy.LocalProxyServer
import com.adsamcik.streamferry.logging.DiagnosticsLogger
import com.adsamcik.streamferry.core.logging.LogEntry
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assume.assumeTrue
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * LIVE end-to-end integration test against a real Jellyfin server (e.g. a local Docker container).
 *
 * It drives the **real** app classes — [JellyfinClient] (connect/auth/browse/playback-info),
 * [HttpJellyfinRepository], [DeviceProfiles] (via the repo), [LocalProxyServer] (incl. HLS rewriting)
 * — and simulates the TV by fetching the phone proxy URL over HTTP, asserting:
 *   1. connect + validate + login work,
 *   2. an H.264 source **direct-plays** to a Cast profile,
 *   3. an HEVC source is **transcoded server-side to HLS** for Cast and to **progressive** for DLNA
 *      (i.e. it casts in the correct format), and
 *   4. the playlist/bytes the TV receives contain **no Jellyfin URL or token** (the security invariant).
 *
 * The test SKIPS itself (JUnit Assume) when no Jellyfin is reachable, so it never breaks normal CI.
 * Configure via -Djellyfin.url / -Djellyfin.user / -Djellyfin.pass (defaults target the local Docker
 * setup in this repo's test instructions). Expects two movies named like "SampleH264" and "SampleHEVC".
 */
class JellyfinLiveIntegrationTest {

    private val base = (System.getProperty("jellyfin.url") ?: "http://127.0.0.1:8096").trimEnd('/')
    private val user = System.getProperty("jellyfin.user") ?: "admin"
    private val pass = System.getProperty("jellyfin.pass") ?: "jellyfin123"

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    private val logger: DiagnosticsLogger = NoopLogger()

    private val castCaps = TargetCapabilities(
        protocol = Protocol.CAST,
        supportedContainers = setOf("mp4"),
        supportedVideoCodecs = setOf("h264"),
        supportedAudioCodecs = setOf("aac", "mp3"),
        supportsHevc = false, supports10Bit = false, supportsHls = true,
    )
    private val dlnaCaps = TargetCapabilities(
        protocol = Protocol.DLNA,
        supportedContainers = setOf("mp4", "mkv", "ts"),
        supportedVideoCodecs = setOf("h264"),
        supportedAudioCodecs = setOf("aac", "ac3"),
        supportsHevc = false, supports10Bit = false, supportsHls = false,
    )

    /** Broad direct-play caps used to resolve the ORIGINAL file for an offline download. */
    private val DOWNLOAD_CAPS = TargetCapabilities(
        protocol = Protocol.CAST,
        supportedContainers = setOf("mp4", "mkv", "webm", "avi", "mov", "ts", "m4v"),
        supportedVideoCodecs = setOf("h264", "hevc", "h265", "vp9", "vp8", "av1", "mpeg4", "mpeg2video"),
        supportedAudioCodecs = setOf("aac", "ac3", "eac3", "mp3", "opus", "flac", "vorbis", "dts", "truehd", "pcm"),
        supportsHevc = true, supports10Bit = true, supportsHls = false,
    )

    @Test
    fun liveConnectBrowseTranscodeAndCastWithoutLeaks() = runBlocking {
        assumeTrue("No Jellyfin reachable at $base; skipping live test", reachable())

        // 1) Connect + validate + login (real JellyfinClient against the real server).
        val client = JellyfinClient(http, "it-device", "ITDevice", "1.0", logger)
        client.configureServer(base)
        val serverInfo = client.systemInfoPublic()
        assertNotNull(serverInfo, "systemInfoPublic should return a server name/version")
        println("[live] connected: $serverInfo")

        val auth = client.authenticateByName(user, pass)
        client.setAuth(auth.accessToken, auth.userId)
        val token = auth.accessToken
        println("[live] logged in (userId=${auth.userId})")

        // 2) Browse: find the movie library and the two sample items.
        val views = client.userViews()
        assertTrue(views.isNotEmpty(), "expected at least one library view")
        val items = views.flatMap { lib -> runCatching { client.itemsPage(lib.id, 0, 100).items }.getOrDefault(emptyList()) }
        val h264 = items.first { it.title.contains("H264", ignoreCase = true) }
        val hevc = items.first { it.title.contains("HEVC", ignoreCase = true) }
        println("[live] found items: '${h264.title}' and '${hevc.title}'")

        val repo = HttpJellyfinRepository(client, logger, http)
        val registry = SessionRegistry()
        val proxy = LocalProxyServer(registry, logger, http)
        val hostPort = proxy.start("127.0.0.1")
        try {
            // 3) H.264 -> Cast: should DIRECT-PLAY; the TV gets video bytes (no token).
            val di = repo.playbackInfo(h264.id, castCaps, 8_000_000, false, false, null, null, 0).getOrThrow()
            val du = repo.resolveUpstream(di)
            assertFalse(du.isHls, "H.264 to a Cast profile should direct-play (not HLS)")
            val ds = registry.create(du.url, du.authHeader, du.contentType, di.playSessionId, du.isHls)
            val directBytes = fetch("http://$hostPort/session/${ds.id}/stream", range = null)
            assertTrue(directBytes.size > 1024, "direct-play should yield video bytes (got ${directBytes.size})")
            assertNoLeak(asText(directBytes), token)
            println("[live] H.264 direct-play OK (${directBytes.size} bytes via proxy)")

            // 4) HEVC -> Cast: should TRANSCODE to HLS; the playlist the TV gets is fully rewritten.
            val ti = repo.playbackInfo(hevc.id, castCaps, 8_000_000, false, false, null, null, 0).getOrThrow()
            val tu = repo.resolveUpstream(ti)
            assertTrue(tu.isHls, "HEVC to a Cast (H.264-only) profile should transcode to HLS")
            val ts = registry.create(tu.url, tu.authHeader, tu.contentType, ti.playSessionId, tu.isHls)
            val playlist = asText(fetch("http://$hostPort/session/${ts.id}/stream", range = null))
            assertTrue(playlist.contains("#EXTM3U"), "expected an HLS playlist, got:\n${playlist.take(200)}")
            assertTrue(playlist.contains("seg="), "playlist must be rewritten to proxy ?seg= URLs")
            assertNoLeak(playlist, token)
            println("[live] HEVC -> Cast transcoded to HLS; playlist rewritten + leak-free")
            followHlsAndAssertNoLeak(playlist, token)

            // 5) HEVC -> DLNA: should TRANSCODE to a PROGRESSIVE stream (not HLS); TV gets bytes.
            val pi = repo.playbackInfo(hevc.id, dlnaCaps, null, false, false, null, null, 0).getOrThrow()
            val pu = repo.resolveUpstream(pi)
            assertFalse(pu.isHls, "HEVC to a DLNA profile should transcode to a progressive stream, not HLS")
            val ps = registry.create(pu.url, pu.authHeader, pu.contentType, pi.playSessionId, pu.isHls)
            val progBytes = fetch("http://$hostPort/session/${ps.id}/stream", range = null)
            assertTrue(progBytes.size > 1024, "progressive transcode should yield bytes (got ${progBytes.size})")
            assertNoLeak(asText(progBytes), token)
            println("[live] HEVC -> DLNA progressive transcode OK (${progBytes.size} bytes via proxy)")

            // 6) OFFLINE DOWNLOAD: resolve the original file, download it, then serve it back through the
            //    proxy as a local file (offline playback) — the TV still only gets the proxy URL.
            val ddi = repo.playbackInfo(h264.id, DOWNLOAD_CAPS, null, false, false, null, null, 0).getOrThrow()
            val ddu = repo.resolveUpstream(ddi)
            assertFalse(ddu.isHls, "a download should resolve to a direct file, not HLS")
            val tmp = File.createTempFile("jf-it-download", ".bin")
            try {
                val req = Request.Builder().url(ddu.url).get()
                    .apply { ddu.authHeader?.let { header("Authorization", it) } }.build()
                http.newCall(req).execute().use { r ->
                    assertTrue(r.isSuccessful, "download fetch failed: HTTP ${r.code}")
                    tmp.outputStream().use { o -> r.body!!.byteStream().copyTo(o) }
                }
                val savedSize = tmp.length()
                assertTrue(savedSize > 1024, "downloaded file should have bytes (got $savedSize)")

                val local = registry.create("local-file", null, ddu.contentType, null, isHls = false, localFilePath = tmp.absolutePath)
                val localUrl = "http://$hostPort/session/${local.id}/stream"
                val fullBytes = fetch(localUrl, range = null)
                assertEquals(savedSize, fullBytes.size.toLong(), "offline proxy should serve the whole local file")
                assertNoLeak(asText(fullBytes), token)
                val partial = fetch(localUrl, range = "bytes=0-1023")
                assertEquals(1024, partial.size, "offline proxy should honour byte ranges (known length)")
                println("[live] downloaded original ($savedSize bytes) + served offline via proxy (range OK, leak-free)")
            } finally {
                tmp.delete()
            }
        } finally {
            proxy.stop()
        }
    }

    @Test
    fun liveQuickConnectDeviceCodeLogin() = runBlocking {
        assumeTrue("No Jellyfin reachable at $base; skipping live test", reachable())

        // Admin session used only to approve the pending code (simulating the user's signed-in device).
        val admin = JellyfinClient(http, "it-admin", "ITAdmin", "1.0", logger)
        admin.configureServer(base)
        val adminAuth = admin.authenticateByName(user, pass)
        admin.setAuth(adminAuth.accessToken, adminAuth.userId)

        // The phone: a fresh, unauthenticated client performs the Quick Connect handshake.
        val phone = JellyfinClient(http, "it-phone", "ITPhone", "1.0", logger)
        phone.configureServer(base)
        assumeTrue("Quick Connect not enabled on server; skipping", phone.quickConnectEnabled())

        val handshake = phone.quickConnectInitiate()
        assertTrue(handshake.code.isNotBlank(), "Initiate should return a user-facing code")
        assertFalse(phone.quickConnectPoll(handshake.secret), "should be unapproved before authorization")
        println("[live] Quick Connect initiated (code=${handshake.code})")

        // The signed-in (admin) device approves the code.
        assertTrue(admin.quickConnectAuthorize(handshake.code), "admin authorize should succeed")
        assertTrue(phone.quickConnectPoll(handshake.secret), "should be approved after authorization")

        // The phone exchanges the approved secret for a session, matching the approving user.
        val session = phone.authenticateWithQuickConnect(handshake.secret)
        assertTrue(session.accessToken.isNotBlank(), "Quick Connect should yield an access token")
        assertEquals(adminAuth.userId, session.userId, "Quick Connect session should match the approver")
        println("[live] Quick Connect login OK (userId=${session.userId})")
    }

    /**
     * Verifies the contract the resumable downloader relies on: Jellyfin's static stream honours
     * `Range`, and a partial download + a ranged remainder reassemble byte-for-byte into the whole file,
     * with the `Content-Range` total matching the full length (the size guard `MediaDownloader` uses to
     * detect a file that changed mid-download).
     */
    @Test
    fun liveResumeReassemblesIdenticalBytes() = runBlocking {
        assumeTrue("No Jellyfin reachable at $base; skipping live test", reachable())

        val client = JellyfinClient(http, "it-device", "ITDevice", "1.0", logger)
        client.configureServer(base)
        val auth = client.authenticateByName(user, pass)
        client.setAuth(auth.accessToken, auth.userId)
        val repo = HttpJellyfinRepository(client, logger, http)

        val views = client.userViews()
        val items = views.flatMap { lib -> runCatching { client.itemsPage(lib.id, 0, 100).items }.getOrDefault(emptyList()) }
        val h264 = items.first { it.title.contains("H264", ignoreCase = true) }
        val info = repo.playbackInfo(h264.id, DOWNLOAD_CAPS, null, false, false, null, null, 0).getOrThrow()
        val upstream = repo.resolveUpstream(info)
        assertFalse(upstream.isHls, "download source should be a direct file")

        // 1) Full download (the reference bytes).
        val full = rangedGet(upstream.url, upstream.authHeader, range = null)
        assertTrue(full.body.size > 4096, "expected a non-trivial file (got ${full.body.size})")
        val total = full.body.size.toLong()
        val half = (total / 2)

        // 2) Partial (first half) + 3) ranged remainder (simulating a resume).
        val firstHalf = rangedGet(upstream.url, upstream.authHeader, range = "bytes=0-${half - 1}")
        val remainder = rangedGet(upstream.url, upstream.authHeader, range = "bytes=$half-")
        assertEquals(206, remainder.code, "a ranged resume should yield 206 Partial Content")
        val crTotal = remainder.contentRange?.substringAfterLast('/')?.trim()?.toLongOrNull() ?: -1L
        assertEquals(total, crTotal, "Content-Range total must match the full length (the resume size guard)")

        // 4) Reassemble and assert byte-for-byte equality with the full download.
        val reassembled = firstHalf.body + remainder.body
        assertEquals(total, reassembled.size.toLong(), "reassembled size must equal the full size")
        assertTrue(reassembled.contentEquals(full.body), "resumed bytes must match a full download exactly")
        println("[live] resume reassembly OK ($total bytes; half+remainder == full, Content-Range total matched)")
    }

    private class RangedResponse(val code: Int, val body: ByteArray, val contentRange: String?)

    private fun rangedGet(url: String, authHeader: String?, range: String?): RangedResponse {
        val b = Request.Builder().url(url).get()
        authHeader?.let { b.header("Authorization", it) }
        range?.let { b.header("Range", it) }
        http.newCall(b.build()).execute().use { r ->
            return RangedResponse(r.code, r.body?.bytes() ?: ByteArray(0), r.header("Content-Range"))
        }
    }

    /** Follow the rewritten playlist's proxy ?seg= URLs, asserting no leak at every nested level. */
    private fun followHlsAndAssertNoLeak(masterPlaylist: String, token: String) {
        var next = firstSegUrl(masterPlaylist)
        var level = 0
        while (next != null && level < 4) {
            val bytes = fetch(next, range = null)
            val text = asText(bytes)
            assertNoLeak(text, token)
            if (text.contains("#EXTM3U") || text.contains("#EXTINF")) {
                assertTrue(text.contains("seg="), "nested media playlist must also be rewritten")
                println("[live]   followed nested media playlist (rewritten + leak-free)")
                next = firstSegUrl(text)
                level++
            } else {
                assertTrue(bytes.isNotEmpty(), "expected segment bytes")
                println("[live]   fetched a transcoded segment via proxy (${bytes.size} bytes)")
                return
            }
        }
    }

    private fun firstSegUrl(playlist: String): String? = playlist.lineSequence()
        .map { it.trim() }
        .firstOrNull { it.startsWith("http") && it.contains("seg=") }

    private fun assertNoLeak(text: String, token: String) {
        val probe = text.take(16_384)
        assertFalse(probe.contains(token), "ACCESS TOKEN leaked to the TV")
        assertFalse(probe.contains(":8096"), "Jellyfin host:port leaked to the TV")
        assertFalse(probe.contains("api_key", ignoreCase = true), "api_key leaked to the TV")
        assertFalse(probe.contains("X-Emby-Token", ignoreCase = true), "Emby token header leaked to the TV")
        assertFalse(probe.contains("MediaBrowser Token", ignoreCase = true), "MediaBrowser auth leaked to the TV")
    }

    private fun fetch(url: String, range: String?): ByteArray {
        val b = Request.Builder().url(url).get()
        range?.let { b.header("Range", it) }
        http.newCall(b.build()).execute().use { r -> return r.body?.bytes() ?: ByteArray(0) }
    }

    private fun asText(bytes: ByteArray): String = String(bytes.copyOf(minOf(bytes.size, 16_384)), Charsets.ISO_8859_1)

    private fun reachable(): Boolean = runCatching {
        OkHttpClient.Builder().connectTimeout(2, TimeUnit.SECONDS).readTimeout(2, TimeUnit.SECONDS).build()
            .newCall(Request.Builder().url("$base/System/Info/Public").build()).execute().use { it.isSuccessful }
    }.getOrDefault(false)

    private class NoopLogger : DiagnosticsLogger {
        override fun d(tag: String, message: String) {}
        override fun i(tag: String, message: String) {}
        override fun w(tag: String, message: String, t: Throwable?) {}
        override fun e(tag: String, message: String, t: Throwable?) {}
        override fun event(category: String, message: String) {}
        override fun trace(tag: String, message: String) {}
        override var traceEnabled: Boolean = false
        override fun exportRedacted(): List<String> = emptyList()
        override fun entries(): List<LogEntry> = emptyList()
        override fun clear() {}
    }
}
