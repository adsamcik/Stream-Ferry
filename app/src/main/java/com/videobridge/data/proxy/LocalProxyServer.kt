package com.videobridge.data.proxy

import android.content.ContentResolver
import android.net.Uri
import com.videobridge.core.buffer.MemoryBufferPolicy
import com.videobridge.core.hls.HlsRewriter
import com.videobridge.core.hls.HlsSegmentRegistry
import com.videobridge.core.http.BoundedBody
import com.videobridge.core.http.HttpRange
import com.videobridge.core.http.HttpResponsePlan
import com.videobridge.core.http.RangeParseResult
import com.videobridge.core.net.ConnectionLimiter
import com.videobridge.core.net.TrustedMediaOriginPolicy
import com.videobridge.core.resilience.ResilientStreamPolicy
import com.videobridge.core.resilience.ThroughputWatchdog
import com.videobridge.core.resilience.UpstreamRetry
import com.videobridge.core.session.ProxySession
import com.videobridge.core.session.SessionLookup
import com.videobridge.core.session.SessionRegistry
import com.videobridge.logging.DiagnosticsLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit

/**
 * In-RAM, session-scoped HTTP proxy (§6). Binds to the phone LAN IP on an ephemeral port ONLY while
 * a playback session is active. Serves exactly one route shape: GET/HEAD /session/{id}/stream with
 * byte-range support, streaming upstream bytes through a small bounded buffer (no disk, no full-file
 * predownload, no unbounded queues). All security checks live in [SessionRegistry].
 *
 * The pure HTTP semantics (range parsing, status selection, redaction, session validation) are
 * implemented in the framework-free `core` package and exhaustively unit-tested.
 */
class LocalProxyServer(
    private val sessions: SessionRegistry,
    private val logger: DiagnosticsLogger,
    private val httpClient: OkHttpClient = defaultClient(),
    /**
     * Resolver used ONLY to open user-picked local `content://` videos (SAF / MediaStore) as a seekable
     * file descriptor. Null in tests / when content playback isn't wired. The TV still only ever receives
     * the phone proxy URL — the content URI is opened on the phone, never shared.
     */
    private val contentResolver: ContentResolver? = null,
    /** Bounds concurrent LAN connections (global + per-IP) to resist a hostile peer flooding the proxy. */
    private val connectionLimiter: ConnectionLimiter = ConnectionLimiter(),
) {
    /**
     * Redirects are handled below so a Jellyfin Authorization header is never automatically followed
     * to another origin. This also applies when callers inject an otherwise default OkHttp client.
     */
    private val upstreamHttpClient = httpClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private var serverSocket: ServerSocket? = null
    private var scope: CoroutineScope? = null
    @Volatile private var boundAddress: String? = null
    @Volatile private var boundPort: Int = -1

    /**
     * Optional sink notified of each chunk of bytes successfully delivered downstream (to the TV).
     * Used by the adaptive-bitrate controller to measure real throughput. Receives only a byte count
     * (no URL/token), so it is safe. Set while a session is active; cleared on stop.
     */
    @Volatile
    var byteListener: ((Long) -> Unit)? = null

    /**
     * Invoked when a downstream (TV) media connection closes, with the bytes delivered, how long it
     * lived, and whether the response completed normally (vs. the TV aborting mid-stream). Lets the engine
     * detect a TV that connected, read a little, then *bailed* — a fast "can't play this" signal. A fully
     * delivered range/segment (completedNormally = true) is NOT a bail-out. Byte count only; no URL/token.
     */
    @Volatile
    var onDownstreamClosed: ((bytesServed: Long, durationMs: Long, completedNormally: Boolean) -> Unit)? = null

    /** Per-session opaque<->upstream-URL maps for HLS playlist rewriting (cleared on stop). */
    private val hlsRegistries = ConcurrentHashMap<String, HlsSegmentRegistry>()

    /** Per-session on-device transcode origins (cleared/released on stop). */
    private val clientTranscoders = ConcurrentHashMap<String, ClientTranscodeSource>()

    /** Register an on-device transcode origin for a session; its playlist + segments are served here. */
    fun registerClientTranscode(sessionId: String, source: ClientTranscodeSource) {
        clientTranscoders[sessionId] = source
    }

    fun unregisterClientTranscode(sessionId: String) {
        clientTranscoders.remove(sessionId)?.let { runCatching { it.release() } }
    }

    val isRunning: Boolean get() = serverSocket != null

    /** Raw "ip:port" of the bound socket, or null when the proxy is not running. The caller is responsible for redacting before display. */
    fun boundAddressRedacted(): String? = boundAddress?.let { "$it:$boundPort" }

    /** Bind on the given LAN IP and an ephemeral port. Returns "ip:port". */
    @Synchronized
    fun start(lanIp: String): String {
        if (serverSocket != null) return "$boundAddress:$boundPort"
        val socket = ServerSocket()
        socket.reuseAddress = true
        // Ephemeral port (0) bound only to the LAN interface — not 0.0.0.0 unless required.
        socket.bind(InetSocketAddress(InetAddress.getByName(lanIp), 0))
        serverSocket = socket
        boundAddress = lanIp
        boundPort = socket.localPort
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = s
        s.launch { acceptLoop(socket) }
        logger.i(TAG, "Proxy bound on LAN (ephemeral port)")
        return "$lanIp:$boundPort"
    }

    /** Stop accepting, close sockets, revoke all sessions and clear buffers (§6 cleanup). */
    @Synchronized
    fun stop() {
        scope?.cancel()
        scope = null
        runCatching { serverSocket?.close() }
        serverSocket = null
        sessions.revokeAll()
        hlsRegistries.clear()
        clientTranscoders.values.forEach { runCatching { it.release() } }
        clientTranscoders.clear()
        boundAddress = null
        boundPort = -1
        logger.i(TAG, "Proxy stopped; sessions revoked, buffers cleared")
    }

    private suspend fun acceptLoop(socket: ServerSocket) {
        val s = scope ?: return
        while (s.isActive && !socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (e: Exception) {
                if (socket.isClosed) break else continue
            }
            // Bound concurrency (§16 DoS): a hostile LAN peer must not be able to open unbounded
            // connections and exhaust coroutines / file descriptors / upstream sockets.
            val ip = client.inetAddress?.hostAddress ?: "unknown"
            if (!connectionLimiter.tryAcquire(ip)) {
                logger.w(TAG, "Connection limit reached; rejecting a LAN connection")
                runCatching { client.close() }
                continue
            }
            s.launch {
                try {
                    handle(client)
                } finally {
                    connectionLimiter.release(ip)
                }
            }
        }
    }

    private fun handle(client: Socket) {
        client.soTimeout = SOCKET_TIMEOUT_MS
        client.use { sock ->
            val counting = CountingOutputStream(BufferedOutputStream(sock.getOutputStream()))
            val out: OutputStream = counting
            val startMs = System.currentTimeMillis()
            var servedMedia = false
            try {
                val req = RequestLine.read(sock.getInputStream()) ?: run {
                    writeStatus(out, 400, "Bad Request"); return
                }
                // CORS preflight: a Cast receiver sends OPTIONS before a cross-origin ranged XHR for
                // HLS playlists/segments. Answer it (with CORS headers) instead of 405, or the GET that
                // follows never happens. No session lookup needed — preflight exposes no data.
                if (req.method == "OPTIONS") {
                    writeStatus(out, 204, "No Content"); return
                }
                if (req.method != "GET" && req.method != "HEAD") {
                    writeStatus(out, 405, "Method Not Allowed"); return
                }
                when (val lookup = sessions.resolve(req.path)) {
                    is SessionLookup.NotFound -> writeStatus(out, 404, "Not Found")
                    is SessionLookup.Expired -> writeStatus(out, 410, "Gone")
                    is SessionLookup.Forbidden -> writeStatus(out, 403, "Forbidden")
                    is SessionLookup.Ok -> {
                        servedMedia = req.method == "GET" // a real playback fetch, not a HEAD probe
                        serve(lookup.session, req, out)
                    }
                }
            } catch (e: Exception) {
                logger.w(TAG, "Request handling error", e)
            } finally {
                runCatching { out.flush() }
                // Report the outcome of a real media fetch so the engine can spot a TV that bailed early.
                if (servedMedia) {
                    val elapsedMs = System.currentTimeMillis() - startMs
                    // Always-on connection summary: a shared report can distinguish a TV that never fetched
                    // (no such line) from one that fetched N bytes then closed (couldn't decode / stalled).
                    logger.trace(TAG, "TV connection closed: ${counting.count} bytes in ${elapsedMs}ms (clean=${!counting.writeFailed})")
                    runCatching {
                        onDownstreamClosed?.invoke(counting.count, elapsedMs, !counting.writeFailed)
                    }
                }
            }
        }
    }

    private fun serve(session: ProxySession, req: RequestLine, out: OutputStream) {
        val head = req.method == "HEAD"
        // On-device transcode: serve the phone-hosted HLS/CMAF playlist + on-demand segments.
        clientTranscoders[session.id]?.let {
            serveClientTranscode(session, it, req, out, head)
            return
        }
        // Offline download: serve the app-private local file (the TV still only gets the proxy URL).
        if (session.localFilePath != null) {
            serveLocalFile(session, req, out, head)
            return
        }
        // HLS transcode sessions are served as a rewritten playlist + proxied segments so the TV never
        // sees a Jellyfin URL or token (it only ever fetches ".../stream?seg=<opaque>").
        if (session.isHls) {
            serveHls(session, req, queryParam(req.path, "seg"), out, head)
            return
        }
        // The upstream entity length: known for a direct-play static file (so we advertise Content-Length
        // + a proper Content-Range and the renderer can byte-range SEEK), unknown (-1) for a live transcode
        // (seeked server-side instead). Resolved on the session from Jellyfin's MediaSource size.
        val totalLength = session.totalLength ?: req.knownTotalLength ?: -1L
        val rangeResult = HttpRange.parse(req.rangeHeader, totalLength)

        if (rangeResult is RangeParseResult.Unsatisfiable) {
            val plan = HttpResponsePlan.plan(rangeResult, totalLength, session.contentType)
            writeHeaders(out, plan)
            return
        }

        val plan = HttpResponsePlan.plan(rangeResult, totalLength, session.contentType, head)

        // Resolve the absolute byte window we must deliver, and the initial upstream Range header.
        val rangeStart: Long
        val rangeEndInclusive: Long?
        val initialRangeHeader: String?
        if (rangeResult is RangeParseResult.Satisfiable) {
            val r = rangeResult.range
            rangeStart = r.start
            rangeEndInclusive = if (r.endInclusive == Long.MAX_VALUE) null else r.endInclusive
            initialRangeHeader = "bytes=$rangeStart-${rangeEndInclusive?.toString() ?: ""}"
        } else {
            // No / malformed Range -> full entity (200).
            rangeStart = 0
            rangeEndInclusive = if (totalLength >= 0) totalLength - 1 else null
            initialRangeHeader = null
        }

        // First upstream fetch. Validate BEFORE writing headers so we can return 502 cleanly.
        val first = runCatching { openUpstream(session, initialRangeHeader) }.getOrNull()
        if (first == null || !UpstreamRetry.isSuccess(first.code)) {
            runCatching { first?.close() }
            writeStatus(out, 502, "Bad Gateway")
            logger.w(TAG, "Upstream open failed or returned non-success")
            return
        }

        // Safety net: a non-HLS session must never relay an HLS playlist (it would contain Jellyfin
        // segment URLs + the token). If the upstream unexpectedly returns one, refuse.
        if (looksLikePlaylist(first.request.url.toString(), first.header("Content-Type"))) {
            runCatching { first.close() }
            writeStatus(out, 502, "Bad Gateway")
            logger.w(TAG, "Refusing to relay an HLS playlist on a non-HLS session")
            return
        }

        logger.trace(TAG, "TV ${req.method} direct stream: ${plan.status.code} range=${req.rangeHeader ?: "full"} total=${if (totalLength >= 0) totalLength.toString() else "unknown"}")
        writeHeaders(out, plan)
        if (head) {
            runCatching { first.close() }
            return
        }
        streamResilient(session, first, rangeStart, rangeEndInclusive, out)
    }

    /**
     * Serve an HLS resource for an HLS session. With no `seg` param this is the master playlist
     * (`session.upstreamUrl`); a `seg` param resolves to a nested media playlist, segment, key, or
     * subtitle. Playlists are rewritten so every URI becomes a phone proxy `?seg=<opaque>` URL (via
     * [HlsRewriter] + the per-session [HlsSegmentRegistry]); other resources are streamed through. The
     * Jellyfin URL/token never reaches the TV.
     */
    private fun serveHls(session: ProxySession, req: RequestLine, segParam: String?, out: OutputStream, head: Boolean) {
        val originPolicy = TrustedMediaOriginPolicy.fromBaseUrl(session.upstreamUrl)
        if (originPolicy == null) {
            writeStatus(out, 502, "Bad Gateway")
            logger.w(TAG, "Refusing HLS request with an invalid upstream origin")
            return
        }
        val registry = hlsRegistries.computeIfAbsent(session.id) { _ -> HlsSegmentRegistry() }
        val resourceUrl = if (segParam == null) session.upstreamUrl else registry.resolve(segParam)
        val trustedResourceUrl = resourceUrl?.let(originPolicy::trustedAbsolute)
        if (trustedResourceUrl == null) {
            writeStatus(out, 404, "Not Found"); return
        }
        // The top-level playlist is always fetched whole; only `?seg=` resources (segments) honour a
        // client Range, so we never request a partial playlist.
        val upstreamRange = if (segParam == null) null else req.rangeHeader
        logger.trace(TAG, "TV fetched HLS ${if (segParam == null) "master/media playlist" else "segment"}${req.rangeHeader?.let { " range=$it" } ?: ""}")
        val resp = openHlsUpstream(trustedResourceUrl.toString(), session.upstreamAuthHeader, upstreamRange, originPolicy)
        if (resp == null) {
            writeStatus(out, 502, "Bad Gateway")
            logger.w(TAG, "HLS upstream open failed or returned non-success")
            return
        }
        resp.use { r ->
            val resolvedResourceUrl = r.request.url
            if (!originPolicy.isTrusted(resolvedResourceUrl)) {
                writeStatus(out, 502, "Bad Gateway")
                logger.w(TAG, "Refusing an HLS redirect outside the trusted upstream origin")
                return
            }
            val contentType = r.header("Content-Type")
            if (looksLikePlaylist(resolvedResourceUrl.toString(), contentType)) {
                // Bound the playlist read (§16): a semi-trusted Jellyfin server must not be able to
                // return an unbounded "playlist" and exhaust the phone heap. Oversized/missing -> 502.
                val bodyBytes = r.body?.byteStream()?.let { BoundedBody.readAtMost(it, MAX_PLAYLIST_BYTES) }
                if (bodyBytes == null) {
                    writeStatus(out, 502, "Bad Gateway")
                    logger.w(TAG, "HLS playlist missing or exceeded the size cap")
                    return
                }
                val body = bodyBytes.toString(Charsets.UTF_8)
                val proxyBase = "http://$boundAddress:$boundPort/session/${session.id}"
                val rewritten = runCatching {
                    HlsRewriter(proxyBase).rewrite(body) { uri ->
                        val nestedUrl = originPolicy.resolve(uri, resolvedResourceUrl)
                            ?: throw IllegalArgumentException("Untrusted HLS URI")
                        registry.encode(nestedUrl.toString())
                    }
                }.getOrElse {
                    writeStatus(out, 502, "Bad Gateway")
                    logger.w(TAG, "Rejected an HLS URI outside the trusted upstream origin")
                    return
                }
                val bytes = rewritten.toByteArray(Charsets.UTF_8)
                writeSimpleHeaders(out, 200, "OK", "application/vnd.apple.mpegurl", bytes.size.toLong(), acceptRanges = false, extra = null)
                if (!head) { out.write(bytes); out.flush() }
            } else {
                val mime = contentType ?: guessSegmentMime(resolvedResourceUrl.toString())
                val length = r.body?.contentLength()?.takeIf { it >= 0 }
                val code = if (r.code == 206) 206 else 200
                val reason = if (code == 206) "Partial Content" else "OK"
                writeSimpleHeaders(out, code, reason, mime, length, acceptRanges = true, extra = r.header("Content-Range")?.let { "Content-Range" to it })
                if (!head) r.body?.byteStream()?.let { copyTo(it, out) }
            }
        }
    }

    private fun openUpstream(session: ProxySession, rangeHeaderValue: String?): Response =
        openUpstreamUrl(
            session.upstreamUrl,
            session.upstreamAuthHeader,
            rangeHeaderValue,
            TrustedMediaOriginPolicy.fromBaseUrl(session.upstreamUrl)
                ?: throw IOException("Refusing an invalid upstream origin"),
        )

    /**
     * Open an authenticated upstream request, following only a small number of redirects that remain
     * on the already-pinned origin. An origin change never receives an Authorization header because no
     * request is created for it.
     */
    private fun openUpstreamUrl(
        url: String,
        authHeader: String?,
        rangeHeaderValue: String?,
        originPolicy: TrustedMediaOriginPolicy,
    ): Response {
        var target = originPolicy.trustedAbsolute(url)
            ?: throw IOException("Refusing an upstream URL outside the trusted origin")
        repeat(MAX_UPSTREAM_REDIRECTS + 1) {
            val builder = Request.Builder().url(target).get()
            authHeader?.let { builder.header("Authorization", it) }
            rangeHeaderValue?.let { builder.header("Range", it) }
            val response = upstreamHttpClient.newCall(builder.build()).execute()
            if (!response.isRedirect) return response

            val redirect = response.header("Location")?.let { originPolicy.resolve(it, target) }
            response.close()
            target = redirect ?: throw IOException("Refusing an upstream redirect outside the trusted origin")
        }
        throw IOException("Too many upstream redirects")
    }

    /**
     * Open an HLS playlist/segment upstream, retrying a FAST transient failure a few times ([UpstreamRetry]
     * decides). A live transcode segment can be momentarily unavailable (a quick 5xx or a connection reset)
     * right after a re-resolve; a couple of short retries ride that out. A read timeout is NOT retried — the
     * server is just slow to produce the segment, so another 30s attempt would only stall the TV further.
     * Returns a successful (200/206) [Response] to stream, or null (caller sends 502).
     */
    private fun openHlsUpstream(
        url: String,
        authHeader: String?,
        rangeHeaderValue: String?,
        originPolicy: TrustedMediaOriginPolicy,
    ): Response? {
        var attempt = 0
        while (true) {
            val result = runCatching { openUpstreamUrl(url, authHeader, rangeHeaderValue, originPolicy) }
            val resp = result.getOrNull()
            if (resp != null && UpstreamRetry.isSuccess(resp.code)) return resp
            val code = resp?.code
            runCatching { resp?.close() }
            val timedOut = result.exceptionOrNull() is java.io.InterruptedIOException // incl. SocketTimeoutException
            if (attempt >= HLS_OPEN_MAX_RETRIES || !UpstreamRetry.shouldRetryOpen(code, timedOut)) return null
            attempt++
            runCatching { Thread.sleep(HLS_OPEN_RETRY_BACKOFF_MS * attempt) }
        }
    }

    /** Copy a (segment/key/subtitle) body downstream through the bounded buffer; abort if the TV leaves. */
    private fun copyTo(input: InputStream, out: OutputStream) {
        input.use { ins ->
            val buf = ByteArray(MemoryBufferPolicy.PASS_THROUGH_CHUNK_BYTES)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                try {
                    out.write(buf, 0, n)
                    byteListener?.invoke(n.toLong())
                } catch (e: Exception) {
                    return // downstream (TV) disconnected
                }
            }
            runCatching { out.flush() }
        }
    }

    /**
     * Serve an app-private downloaded file (offline playback). The file length is known, so full HTTP
     * byte-range / seek support is provided via [HttpResponsePlan] + a [RandomAccessFile]. The file path
     * is fixed on the session (never taken from the request), so there is no path-traversal surface.
     */
    private fun serveLocalFile(session: ProxySession, req: RequestLine, out: OutputStream, head: Boolean) {
        val path = session.localFilePath!!
        if (path.startsWith("content://")) {
            serveContentUri(session, path, req, out, head)
            return
        }
        val file = File(path)
        if (!file.isFile) {
            writeStatus(out, 404, "Not Found"); return
        }
        val total = file.length()
        val rangeResult = HttpRange.parse(req.rangeHeader, total)
        if (rangeResult is RangeParseResult.Unsatisfiable) {
            writeHeaders(out, HttpResponsePlan.plan(rangeResult, total, session.contentType))
            return
        }
        val plan = HttpResponsePlan.plan(rangeResult, total, session.contentType, head)
        val start: Long
        val endInclusive: Long
        if (rangeResult is RangeParseResult.Satisfiable) {
            val r = rangeResult.range
            start = r.start
            endInclusive = if (r.endInclusive == Long.MAX_VALUE) total - 1 else r.endInclusive
        } else {
            start = 0
            endInclusive = total - 1
        }
        writeHeaders(out, plan)
        if (head) return
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(start)
            val buf = ByteArray(MemoryBufferPolicy.PASS_THROUGH_CHUNK_BYTES)
            var remaining = endInclusive - start + 1
            while (remaining > 0) {
                val toRead = minOf(remaining, buf.size.toLong()).toInt()
                val n = raf.read(buf, 0, toRead)
                if (n < 0) break
                try {
                    out.write(buf, 0, n)
                    byteListener?.invoke(n.toLong())
                } catch (e: Exception) {
                    return // downstream (TV) disconnected
                }
                remaining -= n
            }
            runCatching { out.flush() }
        }
    }

    /**
     * Serve a user-picked local `content://` video (SAF / MediaStore) with full byte-range / seek
     * support, by opening it as a seekable file descriptor. The URI is fixed on the session (never taken
     * from the request) and is opened on the phone only — the TV receives just the proxy URL.
     */
    private fun serveContentUri(session: ProxySession, uriString: String, req: RequestLine, out: OutputStream, head: Boolean) {
        val resolver = contentResolver
        if (resolver == null) {
            writeStatus(out, 500, "Internal Server Error")
            logger.w(TAG, "No content resolver to open a local content URI")
            return
        }
        val pfd = runCatching { resolver.openFileDescriptor(Uri.parse(uriString), "r") }.getOrNull()
        if (pfd == null) {
            writeStatus(out, 404, "Not Found"); return
        }
        pfd.use {
            val total = pfd.statSize
            if (total < 0) {
                // Length unknown: can't satisfy ranges, stream the whole entity (no seek).
                writeSimpleHeaders(out, 200, "OK", session.contentType, null, acceptRanges = false, extra = null)
                if (!head) FileInputStream(pfd.fileDescriptor).use { copyTo(it, out) }
                return
            }
            val rangeResult = HttpRange.parse(req.rangeHeader, total)
            if (rangeResult is RangeParseResult.Unsatisfiable) {
                writeHeaders(out, HttpResponsePlan.plan(rangeResult, total, session.contentType))
                return
            }
            val plan = HttpResponsePlan.plan(rangeResult, total, session.contentType, head)
            val start: Long
            val endInclusive: Long
            if (rangeResult is RangeParseResult.Satisfiable) {
                val r = rangeResult.range
                start = r.start
                endInclusive = if (r.endInclusive == Long.MAX_VALUE) total - 1 else r.endInclusive
            } else {
                start = 0
                endInclusive = total - 1
            }
            writeHeaders(out, plan)
            if (head) return
            FileInputStream(pfd.fileDescriptor).channel.use { channel ->
                channel.position(start)
                val buf = ByteArray(MemoryBufferPolicy.PASS_THROUGH_CHUNK_BYTES)
                val bb = ByteBuffer.wrap(buf)
                var remaining = endInclusive - start + 1
                while (remaining > 0) {
                    bb.clear()
                    if (remaining < buf.size) bb.limit(remaining.toInt())
                    val n = channel.read(bb)
                    if (n < 0) break
                    try {
                        out.write(buf, 0, n)
                        byteListener?.invoke(n.toLong())
                    } catch (e: Exception) {
                        return // downstream (TV) disconnected
                    }
                    remaining -= n
                }
                runCatching { out.flush() }
            }
        }
    }

    /**
     * Serve an on-device transcode session: with no `seg` param the VOD HLS playlist; `seg=init` the
     * shared CMAF init segment; `seg=<index>` an on-demand transcoded media segment (full seek). The TV
     * only ever fetches these phone proxy URLs — never the source.
     */
    private fun serveClientTranscode(session: ProxySession, source: ClientTranscodeSource, req: RequestLine, out: OutputStream, head: Boolean) {
        when (val seg = queryParam(req.path, "seg")) {
            null -> {
                val base = "http://$boundAddress:$boundPort/session/${session.id}"
                val body = source.playlist(base, allowExport = !head).toByteArray(Charsets.UTF_8)
                // Always-on event (redacted) so a shared report shows EXACTLY what the TV was served —
                // the master playlist + its CODECS declaration is the #1 reason a Cast fMP4 stream won't start.
                logger.event("transcode", "Served TV master playlist (${body.size} B): ${playlistHead(body)}")
                writeSimpleHeaders(out, 200, "OK", "application/vnd.apple.mpegurl", body.size.toLong(), acceptRanges = false, extra = null)
                if (!head) { out.write(body); runCatching { out.flush() } }
            }
            "media" -> {
                val base = "http://$boundAddress:$boundPort/session/${session.id}"
                val body = source.mediaPlaylist(base).toByteArray(Charsets.UTF_8)
                logger.trace(TAG, "TV fetched on-device transcode media playlist (${body.size} B)")
                writeSimpleHeaders(out, 200, "OK", "application/vnd.apple.mpegurl", body.size.toLong(), acceptRanges = false, extra = null)
                if (!head) { out.write(body); runCatching { out.flush() } }
            }
            "init" -> {
                logger.trace(TAG, "TV fetched on-device transcode init segment")
                serveSegmentResource(source.initSegment(allowExport = !head), out, head)
            }
            else -> {
                val index = seg.toIntOrNull()
                if (index == null) { writeStatus(out, 404, "Not Found"); return }
                logger.trace(TAG, "TV fetched on-device transcode segment #$index")
                serveSegmentResource(source.mediaSegment(index, allowExport = !head), out, head)
            }
        }
    }

    /** First line or two of a served playlist for diagnostics (redaction still applies to the whole line). */
    private fun playlistHead(body: ByteArray): String =
        String(body, Charsets.UTF_8).replace("\n", " | ").take(300)

    private fun serveSegmentResource(resource: ClientTranscodeSource.Resource, out: OutputStream, head: Boolean) {
        when (resource) {
            is ClientTranscodeSource.Resource.Ready -> serveSegmentBytes(resource.bytes, out, head)
            ClientTranscodeSource.Resource.NotFound -> writeStatus(out, 404, "Not Found")
            // An export failure/cancellation is retryable by the receiver, but must not masquerade as a
            // successful zero-byte fMP4 response. HEAD follows the same state mapping without exporting.
            ClientTranscodeSource.Resource.Unavailable -> writeStatus(out, 503, "Service Unavailable")
            ClientTranscodeSource.Resource.TimedOut -> writeStatus(out, 504, "Gateway Timeout")
        }
    }

    private fun serveSegmentBytes(bytes: ByteArray, out: OutputStream, head: Boolean) {
        writeSimpleHeaders(out, 200, "OK", "video/mp4", bytes.size.toLong(), acceptRanges = false, extra = null)
        if (!head) { out.write(bytes); runCatching { out.flush() } }
    }

    private fun looksLikePlaylist(url: String, contentType: String?): Boolean {
        if (contentType != null && (contentType.contains("mpegurl", true) || contentType.contains("vnd.apple", true))) return true
        return url.substringBefore('?').endsWith(".m3u8", true)
    }

    private fun guessSegmentMime(url: String): String = when {
        url.substringBefore('?').endsWith(".ts", true) -> "video/mp2t"
        url.substringBefore('?').endsWith(".m4s", true) || url.substringBefore('?').endsWith(".mp4", true) -> "video/mp4"
        url.substringBefore('?').endsWith(".vtt", true) -> "text/vtt"
        url.substringBefore('?').endsWith(".aac", true) -> "audio/aac"
        else -> "application/octet-stream"
    }

    private fun queryParam(path: String, key: String): String? {
        val query = path.substringAfter('?', "")
        if (query.isEmpty()) return null
        for (pair in query.split('&')) {
            if (pair.substringBefore('=') == key) {
                return runCatching { java.net.URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8") }.getOrNull()
            }
        }
        return null
    }

    private enum class StreamResult { COMPLETE, DOWNSTREAM_GONE, UPSTREAM_FAILED, UPSTREAM_STALLED }

    /**
     * Counts bytes written downstream, and records whether a write ever FAILED — i.e. the TV closed the
     * connection mid-response (an abort) rather than us finishing normally. Lets [onDownstreamClosed]
     * distinguish "TV bailed" from "response fully delivered" (a completed range/segment isn't a bail-out).
     */
    private class CountingOutputStream(private val out: OutputStream) : OutputStream() {
        var count = 0L
            private set
        var writeFailed = false
            private set

        override fun write(b: Int) = track { out.write(b); count++ }
        override fun write(b: ByteArray, off: Int, len: Int) = track { out.write(b, off, len); count += len }
        override fun flush() = out.flush()
        override fun close() = out.close()

        private inline fun track(block: () -> Unit) {
            try {
                block()
            } catch (e: IOException) {
                writeFailed = true
                throw e
            }
        }
    }

    /**
     * Stream upstream -> downstream, transparently reconnecting to the upstream on a mid-transfer
     * stall/reset and resuming from the exact next byte (an HTTP Range resume). The TV's connection
     * stays open across the dip so it never observes the failure. All retry/backoff/offset decisions
     * come from the pure-JVM [ResilientStreamPolicy]; a downstream (TV) failure aborts immediately.
     */
    private fun streamResilient(
        session: ProxySession,
        initialResp: Response,
        rangeStart: Long,
        rangeEndInclusive: Long?,
        out: OutputStream,
    ) {
        val policy = ResilientStreamPolicy(rangeStart, rangeEndInclusive)
        val watchdog = ThroughputWatchdog()
        val buf = ByteArray(MemoryBufferPolicy.PASS_THROUGH_CHUNK_BYTES)
        var response: Response? = initialResp
        try {
            while (true) {
                if (response == null) {
                    response = runCatching { openUpstream(session, policy.resumeRangeHeader()) }
                        .getOrNull()?.takeIf { UpstreamRetry.isSuccess(it.code) }
                    if (response == null) {
                        if (!retryOrGiveUp(policy)) return else continue
                    }
                }
                val resp = response
                response = null
                // If a resume request was answered with 200 (Range ignored), discard the prefix we
                // already delivered so the downstream byte stream stays contiguous.
                val skip = if (policy.bytesForwarded > 0 && !UpstreamRetry.rangeHonoured(resp.code)) {
                    policy.nextOffset
                } else {
                    0L
                }
                val result = try {
                    val body = resp.body
                    if (body == null) StreamResult.UPSTREAM_FAILED
                    else copyStream(body.byteStream(), out, buf, policy, skip, watchdog)
                } catch (e: Exception) {
                    StreamResult.UPSTREAM_FAILED
                } finally {
                    runCatching { resp.close() }
                }
                when (result) {
                    StreamResult.COMPLETE, StreamResult.DOWNSTREAM_GONE -> return
                    StreamResult.UPSTREAM_STALLED -> {
                        logger.w(TAG, "Upstream throughput collapsed (slow-trickle); ending transfer")
                        return
                    }
                    StreamResult.UPSTREAM_FAILED -> if (!retryOrGiveUp(policy)) return
                }
            }
        } finally {
            runCatching { response?.close() }
            runCatching { out.flush() }
        }
    }

    /** Apply the resilience policy after an upstream failure; sleeps the backoff. @return continue? */
    private fun retryOrGiveUp(policy: ResilientStreamPolicy): Boolean {
        val roll = ThreadLocalRandom.current().nextDouble()
        return when (val decision = policy.onRecoverableFailure(roll)) {
            is ResilientStreamPolicy.Decision.GiveUp -> {
                logger.w(TAG, "Upstream unrecoverable after ${policy.consecutiveFailures} attempts; ending transfer")
                false
            }
            is ResilientStreamPolicy.Decision.Retry -> {
                if (decision.delayMillis > 0) runCatching { Thread.sleep(decision.delayMillis) }
                // Offset is logged as a plain number; no URL/token is ever emitted here.
                logger.d(TAG, "Reconnecting upstream (attempt ${decision.attempt}) at offset ${decision.resumeFromOffset}")
                true
            }
        }
    }

    /**
     * Copy one upstream response body downstream through the fixed [buf], never accumulating the
     * whole file. Honours the requested end bound and records progress against [policy] so a resume
     * knows the next byte. Returns how the copy ended.
     */
    private fun copyStream(
        input: InputStream,
        out: OutputStream,
        buf: ByteArray,
        policy: ResilientStreamPolicy,
        skipBytes: Long,
        watchdog: ThroughputWatchdog,
    ): StreamResult {
        input.use { ins ->
            var toSkip = skipBytes
            while (toSkip > 0) {
                val skipped = ins.skip(toSkip)
                if (skipped <= 0) {
                    val n = ins.read(buf, 0, minOf(buf.size.toLong(), toSkip).toInt())
                    if (n < 0) return StreamResult.UPSTREAM_FAILED // premature EOF while skipping
                    toSkip -= n
                } else {
                    toSkip -= skipped
                }
            }

            var sinceFlush = 0L
            while (true) {
                val n = ins.read(buf)
                if (n < 0) {
                    // Clean EOF only counts as complete when we have delivered the whole window
                    // (or the length is unknown, e.g. a transcode that is not byte-resumable).
                    return if (policy.rangeEndInclusive == null || policy.isComplete) {
                        StreamResult.COMPLETE
                    } else {
                        StreamResult.UPSTREAM_FAILED
                    }
                }
                // Never forward beyond the client's requested end.
                val remaining = policy.rangeEndInclusive?.let { it - policy.nextOffset + 1 } ?: Long.MAX_VALUE
                val writable = minOf(n.toLong(), remaining).toInt()
                try {
                    out.write(buf, 0, writable)
                    policy.recordProgress(writable.toLong())
                    byteListener?.invoke(writable.toLong())
                    if (watchdog.record(writable.toLong(), System.currentTimeMillis())) {
                        return StreamResult.UPSTREAM_STALLED
                    }
                    sinceFlush += writable
                    if (sinceFlush >= MemoryBufferPolicy.PASS_THROUGH_DEFAULT_BUFFER_BYTES) {
                        out.flush() // apply backpressure; downstream (TV) paces us
                        sinceFlush = 0
                    }
                } catch (e: Exception) {
                    // TV/client disconnected: stop pulling from upstream immediately.
                    logger.d(TAG, "Downstream disconnected; aborting transfer")
                    return StreamResult.DOWNSTREAM_GONE
                }
                if (policy.isComplete || writable < n) {
                    runCatching { out.flush() }
                    return StreamResult.COMPLETE
                }
            }
        }
    }

    private fun writeHeaders(out: OutputStream, plan: HttpResponsePlan.Plan) {
        val sb = StringBuilder()
        sb.append("HTTP/1.1 ${plan.status.code} ${plan.status.reason}\r\n")
        plan.headers.forEach { (k, v) -> sb.append("$k: $v\r\n") }
        sb.append("Connection: close\r\n\r\n")
        out.write(sb.toString().toByteArray(Charsets.US_ASCII))
        out.flush()
    }

    private fun writeStatus(out: OutputStream, code: Int, reason: String) {
        val sb = StringBuilder("HTTP/1.1 $code $reason\r\nContent-Length: 0\r\n")
        HttpResponsePlan.CORS_HEADERS.forEach { (k, v) -> sb.append("$k: $v\r\n") }
        sb.append("Connection: close\r\n\r\n")
        out.write(sb.toString().toByteArray(Charsets.US_ASCII))
        out.flush()
    }

    /** Minimal response headers for HLS playlist/segment responses. */
    private fun writeSimpleHeaders(
        out: OutputStream,
        code: Int,
        reason: String,
        contentType: String,
        contentLength: Long?,
        acceptRanges: Boolean,
        extra: Pair<String, String>?,
    ) {
        val sb = StringBuilder()
        sb.append("HTTP/1.1 $code $reason\r\n")
        sb.append("Content-Type: $contentType\r\n")
        contentLength?.let { sb.append("Content-Length: $it\r\n") }
        if (acceptRanges) sb.append("Accept-Ranges: bytes\r\n")
        extra?.let { sb.append("${it.first}: ${it.second}\r\n") }
        HttpResponsePlan.CORS_HEADERS.forEach { (k, v) -> sb.append("$k: $v\r\n") }
        sb.append("Connection: close\r\n\r\n")
        out.write(sb.toString().toByteArray(Charsets.US_ASCII))
        out.flush()
    }

    companion object {
        private const val TAG = "LocalProxyServer"
        private const val SOCKET_TIMEOUT_MS = 30_000
        // HLS segment/playlist upstream open: a couple of quick retries for a fast transient failure (a
        // live transcode segment briefly not ready). Small + short so the TV isn't held waiting; a slow
        // read timeout is not retried (see openHlsUpstream / UpstreamRetry.shouldRetryOpen).
        private const val HLS_OPEN_MAX_RETRIES = 2
        private const val HLS_OPEN_RETRY_BACKOFF_MS = 250L
        /** Same-origin redirects are handled manually; this bounds loops without exposing Location. */
        private const val MAX_UPSTREAM_REDIRECTS = 3

        /** Hard cap on an upstream HLS playlist read into memory (a semi-trusted Jellyfin response). */
        private const val MAX_PLAYLIST_BYTES = 4 * 1024 * 1024

        /**
         * Upstream client tuned for spotty links. `readTimeout` bounds how long a stalled upstream
         * read may hang before it throws — that exception is what triggers the resilient Range-resume
         * in [streamResilient], so it is kept moderate (detect a real stall, not a brief dip). No
         * `callTimeout` is set (default 0): a single transfer can legitimately run for a whole movie,
         * and an overall cap would kill long playbacks. `retryOnConnectionFailure` lets OkHttp retry
         * connection-establishment hiccups, complementing our higher-level read-resume.
         */
        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
