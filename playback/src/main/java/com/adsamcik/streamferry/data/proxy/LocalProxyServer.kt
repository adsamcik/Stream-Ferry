package com.adsamcik.streamferry.data.proxy

import android.content.ContentResolver
import android.net.Uri
import com.adsamcik.streamferry.core.buffer.MemoryBufferPolicy
import com.adsamcik.streamferry.core.hls.HlsRewriter
import com.adsamcik.streamferry.core.hls.HlsSegmentRegistry
import com.adsamcik.streamferry.core.http.BoundedBody
import com.adsamcik.streamferry.core.http.ByteRange
import com.adsamcik.streamferry.core.http.HttpRange
import com.adsamcik.streamferry.core.http.HttpResponsePlan
import com.adsamcik.streamferry.core.http.RangeParseResult
import com.adsamcik.streamferry.core.http.UpstreamRangeVerifier
import com.adsamcik.streamferry.core.net.ConnectionLimiter
import com.adsamcik.streamferry.core.resilience.ResilientStreamPolicy
import com.adsamcik.streamferry.core.resilience.ThroughputWatchdog
import com.adsamcik.streamferry.core.resilience.UpstreamRetry
import com.adsamcik.streamferry.core.session.ProxySession
import com.adsamcik.streamferry.core.session.SessionLookup
import com.adsamcik.streamferry.core.session.SessionRegistry
import com.adsamcik.streamferry.source.api.DiagnosticSink
import com.adsamcik.streamferry.source.api.StreamLease
import com.adsamcik.streamferry.source.api.StreamResourceKind
import com.adsamcik.streamferry.source.api.StreamResourceRef
import com.adsamcik.streamferry.source.api.StreamResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
    private val logger: DiagnosticSink,
    /**
     * Resolver used ONLY to open user-picked local `content://` videos (SAF / MediaStore) as a seekable
     * file descriptor. Null in tests / when content playback isn't wired. The TV still only ever receives
     * the phone proxy URL — the content URI is opened on the phone, never shared.
     */
    private val contentResolver: ContentResolver? = null,
    /** Bounds concurrent LAN connections (global + per-IP) to resist a hostile peer flooding the proxy. */
    private val connectionLimiter: ConnectionLimiter = ConnectionLimiter(),
    /** Fresh operation-level Android local-network permission check. */
    private val requireLocalNetworkAccess: () -> Unit = {},
) {

    private var serverSocket: ServerSocket? = null
    private var scope: CoroutineScope? = null
    @Volatile private var boundAddress: String? = null
    @Volatile private var boundPort: Int = -1
    @Volatile private var stopping = false

    /** Serializes stop/start with registration of sockets and in-flight upstream calls. */
    private val lifecycleLock = Any()
    /** Active renderer sockets, closed synchronously when the session is revoked. */
    private val activeClients = ConcurrentHashMap.newKeySet<Socket>()
    /** Source-owned byte access. Provider URLs, headers, and tokens never enter this module. */
    private val remoteLeases = ConcurrentHashMap<String, StreamLease>()
    /** Open bodies are tracked so local teardown can cut renderer access before remote cleanup. */
    private val activeResponses = ConcurrentHashMap<String, MutableSet<StreamResponse>>()

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

    /** Per-session opaque resource maps for HLS playlist rewriting (cleared on stop). */
    private val hlsRegistries = ConcurrentHashMap<String, HlsSegmentRegistry>()
    private val hlsResources = ConcurrentHashMap<String, ConcurrentHashMap<String, StreamResourceRef>>()

    /** Per-session on-device transcode origins (cleared/released on stop). */
    private val clientTranscoders = ConcurrentHashMap<String, ClientTranscodeSource>()

    /** Register an on-device transcode origin for a session; its playlist + segments are served here. */
    fun registerClientTranscode(sessionId: String, source: ClientTranscodeSource) {
        clientTranscoders[sessionId] = source
    }

    fun unregisterClientTranscode(sessionId: String) {
        clientTranscoders.remove(sessionId)?.let { runCatching { it.release() } }
    }

    fun registerStreamLease(sessionId: String, lease: StreamLease) {
        require(sessions.isActive(sessionId)) { "Proxy session is not active" }
        remoteLeases[sessionId] = lease
    }

    fun unregisterStreamLease(sessionId: String) {
        remoteLeases.remove(sessionId)
        activeResponses.remove(sessionId)?.forEach { response -> runCatching { response.close() } }
        hlsRegistries.remove(sessionId)
        hlsResources.remove(sessionId)
    }

    val isRunning: Boolean get() = serverSocket != null

    /** Raw "ip:port" of the bound socket, or null when the proxy is not running. The caller is responsible for redacting before display. */
    fun boundAddressRedacted(): String? = boundAddress?.let { "$it:$boundPort" }

    /** Bind on the given LAN IP and an ephemeral port. Returns "ip:port". */
    @Synchronized
    fun start(lanIp: String): String {
        requireLocalNetworkAccess()
        if (serverSocket != null) return "$boundAddress:$boundPort"
        synchronized(lifecycleLock) { stopping = false }
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

    /**
     * Stop local exposure before any remote cleanup can run: revoke opaque URLs, cancel every
     * upstream call, close every accepted renderer socket, then close the listener and buffers.
     */
    @Synchronized
    fun stop() {
        val responses: List<StreamResponse>
        val clients: List<Socket>
        val listener: ServerSocket?
        val oldScope: CoroutineScope?
        synchronized(lifecycleLock) {
            stopping = true
            sessions.revokeAll()
            responses = activeResponses.values.flatMap { it.toList() }
            clients = activeClients.toList()
            activeResponses.clear()
            remoteLeases.clear()
            activeClients.clear()
            listener = serverSocket
            serverSocket = null
            oldScope = scope
            scope = null
        }
        responses.forEach { response -> runCatching { response.close() } }
        clients.forEach { client -> runCatching { client.close() } }
        runCatching { listener?.close() }
        oldScope?.cancel()
        hlsRegistries.clear()
        hlsResources.clear()
        clientTranscoders.values.forEach { runCatching { it.release() } }
        clientTranscoders.clear()
        boundAddress = null
        boundPort = -1
        logger.i(TAG, "Proxy stopped; sessions revoked and active relay resources closed")
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
            val allowed = runCatching { requireLocalNetworkAccess(); true }.getOrDefault(false)
            if (!allowed || !connectionLimiter.tryAcquire(ip)) {
                logger.w(TAG, "Connection limit reached; rejecting a LAN connection")
                runCatching { client.close() }
                continue
            }
            val accepted = synchronized(lifecycleLock) {
                if (stopping) false else activeClients.add(client)
            }
            if (!accepted) {
                connectionLimiter.release(ip)
                runCatching { client.close() }
                continue
            }
            s.launch {
                try {
                    handle(client)
                } finally {
                    activeClients.remove(client)
                    connectionLimiter.release(ip)
                }
            }
        }
    }

    private suspend fun handle(client: Socket) {
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

    private suspend fun serve(session: ProxySession, req: RequestLine, out: OutputStream) {
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
        // HLS sessions are served as a rewritten playlist + proxied resources so the TV never sees a
        // provider URL or token (it only ever fetches ".../stream?seg=<opaque>").
        if (session.isHls) {
            serveHls(session, req, queryParam(req.path, "seg"), out, head)
            return
        }

        // A progressive transcode whose total size is unknown is deliberately non-seekable. Returning a
        // synthetic 206 here would claim bytes we cannot verify, so answer a normal 200 without ranges.
        val lease = remoteLeases[session.id]
        if (lease == null) {
            writeStatus(out, 404, "Not Found")
            return
        }
        val totalLength = lease.descriptor.totalLength ?: session.totalLength ?: req.knownTotalLength ?: -1L
        if (totalLength < 0) {
            serveUnknownLengthUpstream(session, out, head)
            return
        }

        val rangeResult = HttpRange.parse(req.rangeHeader, totalLength)
        if (rangeResult is RangeParseResult.Unsatisfiable) {
            writeHeaders(out, HttpResponsePlan.plan(rangeResult, totalLength, session.contentType))
            return
        }
        val plan = HttpResponsePlan.plan(rangeResult, totalLength, session.contentType, head)
        val requestedRange = (rangeResult as? RangeParseResult.Satisfiable)?.range
        val rangeStart = requestedRange?.start ?: 0L
        val rangeEndInclusive = requestedRange?.endInclusive ?: totalLength - 1
        val first = runCatching {
            if (requestedRange != null) {
                openVerifiedRangeUpstream(session, lease, requestedRange, totalLength)
            } else {
                openFullUpstream(session, lease, totalLength)
            }
        }.getOrNull()
        if (first == null) {
            writeStatus(out, 502, "Bad Gateway")
            logger.w(TAG, "Upstream did not prove the requested media response")
            return
        }

        // Safety net: a non-HLS session must never relay an HLS playlist (it could contain provider
        // resource URLs). If the source unexpectedly returns one, refuse.
        if (looksLikePlaylist(first.header("Content-Type"))) {
            closeUpstream(session.id, first)
            writeStatus(out, 502, "Bad Gateway")
            logger.w(TAG, "Refusing to relay an HLS playlist on a non-HLS session")
            return
        }

        logger.trace(TAG, "TV ${req.method} direct stream: ${plan.status.code} range=${req.rangeHeader ?: "full"} total=$totalLength")
        writeHeaders(out, plan)
        if (head) {
            closeUpstream(session.id, first)
            return
        }
        streamResilient(session, lease, first, rangeStart, rangeEndInclusive, totalLength, out, canResume = true)
    }

    private suspend fun serveUnknownLengthUpstream(session: ProxySession, out: OutputStream, head: Boolean) {
        val lease = remoteLeases[session.id]
        val first = lease?.let { runCatching { openFullUpstream(session, it) }.getOrNull() }
        if (first == null) {
            writeStatus(out, 502, "Bad Gateway")
            logger.w(TAG, "Unknown-length upstream open failed")
            return
        }
        if (looksLikePlaylist(first.header("Content-Type"))) {
            closeUpstream(session.id, first)
            writeStatus(out, 502, "Bad Gateway")
            logger.w(TAG, "Refusing to relay an HLS playlist on a non-HLS session")
            return
        }
        val contentLength = first.declaredContentLength()
        writeSimpleHeaders(out, 200, "OK", session.contentType, contentLength, acceptRanges = false, extra = null)
        if (head) {
            closeUpstream(session.id, first)
            return
        }
        streamResilient(session, lease, first, rangeStart = 0, rangeEndInclusive = null, expectedTotalLength = -1, out = out, canResume = false)
    }

    /** Serve source-owned HLS resources without learning their URLs, credentials, or server type. */
    private suspend fun serveHls(
        session: ProxySession,
        req: RequestLine,
        segParam: String?,
        out: OutputStream,
        head: Boolean,
    ) {
        val lease = remoteLeases[session.id]
        if (lease == null) {
            writeStatus(out, 404, "Not Found")
            return
        }
        val registry = hlsRegistries.computeIfAbsent(session.id) { HlsSegmentRegistry() }
        val resourceMap = hlsResources.computeIfAbsent(session.id) { ConcurrentHashMap() }
        val resource = if (segParam == null) null else registry.resolve(segParam)?.let(resourceMap::get)
        if (segParam != null && resource == null) {
            writeStatus(out, 404, "Not Found")
            return
        }
        val range = if (resource == null || resource.kind == StreamResourceKind.PLAYLIST) {
            null
        } else {
            (HttpRange.parse(req.rangeHeader, -1L) as? RangeParseResult.Satisfiable)?.range
        }
        val requestRangeHeader = range?.let(::rangeHeader)
        logger.trace(TAG, "TV fetched HLS ${if (resource == null) "playlist" else "resource"}${requestRangeHeader?.let { " range=$it" } ?: ""}")
        val response = openHlsUpstream(session, lease, resource, range)
        if (response == null) {
            writeStatus(out, 502, "Bad Gateway")
            logger.w(TAG, "HLS source open failed or returned non-success")
            return
        }
        try {
            val contentType = response.header("Content-Type")
            if (response.statusCode == 416) {
                val totalLength = requestRangeHeader?.let {
                    UpstreamRangeVerifier.unsatisfiedTotal(response.statusCode, response.headers("Content-Range"))
                }
                if (totalLength == null) {
                    writeStatus(out, 502, "Bad Gateway")
                    logger.w(TAG, "HLS source returned an invalid unsatisfied range")
                    return
                }
                writeHeaders(
                    out,
                    HttpResponsePlan.plan(
                        RangeParseResult.Unsatisfiable(totalLength),
                        totalLength,
                        contentType ?: "application/octet-stream",
                        head,
                    ),
                )
                return
            }
            val playlist = resource == null ||
                resource.kind == StreamResourceKind.PLAYLIST ||
                looksLikePlaylist(contentType)
            val responseValid = when {
                playlist -> range == null && response.statusCode == 200
                range == null -> response.statusCode == 200 && response.hasValidContentLength()
                else -> response.hasValidContentLength() && UpstreamRangeVerifier.matchesRequestHeader(
                    statusCode = response.statusCode,
                    contentRanges = response.headers("Content-Range"),
                    contentLength = response.declaredContentLength(),
                    requestHeader = requireNotNull(requestRangeHeader),
                )
            }
            if (!responseValid) {
                writeStatus(out, 502, "Bad Gateway")
                logger.w(TAG, "HLS source did not prove the requested response range")
                return
            }
            if (playlist) {
                val bodyBytes = BoundedBody.readAtMost(response.body, MAX_PLAYLIST_BYTES)
                if (bodyBytes == null) {
                    writeStatus(out, 502, "Bad Gateway")
                    logger.w(TAG, "HLS playlist exceeded the size cap")
                    return
                }
                val body = bodyBytes.toString(Charsets.UTF_8)
                val proxyBase = "http://$boundAddress:$boundPort/session/${session.id}"
                val rewritten = try {
                    val rewriter = HlsRewriter(proxyBase)
                    val rawReferences = rewriter.uriReferences(body, registry.capacity)
                    val resolvedByRaw = LinkedHashMap<String, StreamResourceRef>()
                    for (uri in rawReferences) {
                        val nested = lease.resolve(uri, resource).getOrThrow()
                            ?: throw IllegalArgumentException("Untrusted HLS URI")
                        resolvedByRaw[uri] = nested
                        resourceMap[nested.opaqueId] = nested
                    }
                    val encodedById = registry.encodeBatch(resolvedByRaw.values.map { it.opaqueId })
                    rewriter.rewrite(body) { uri ->
                        encodedById.getValue(resolvedByRaw.getValue(uri).opaqueId)
                    }
                } catch (error: Exception) {
                    writeStatus(out, 502, "Bad Gateway")
                    logger.w(TAG, "Source rejected an HLS resource reference", error)
                    return
                }
                val bytes = rewritten.toByteArray(Charsets.UTF_8)
                writeSimpleHeaders(out, 200, "OK", "application/vnd.apple.mpegurl", bytes.size.toLong(), false, null)
                if (!head) {
                    out.write(bytes)
                    out.flush()
                }
            } else {
                val length = response.declaredContentLength()
                val code = if (range != null) 206 else 200
                val reason = if (code == 206) "Partial Content" else "OK"
                writeSimpleHeaders(
                    out,
                    code,
                    reason,
                    contentType ?: "application/octet-stream",
                    length,
                    acceptRanges = true,
                    extra = response.header("Content-Range")?.let { "Content-Range" to it },
                )
                if (!head) copyTo(response.body, out)
            }
        } finally {
            closeUpstream(session.id, response)
        }
    }

    /** A full response must start at byte zero; a 206 without a Range request is not safe to relay. */
    private suspend fun openFullUpstream(
        session: ProxySession,
        lease: StreamLease,
        expectedTotalLength: Long? = null,
    ): StreamResponse? {
        val response = openTracked(session, lease, null)
        val declaredLength = response.declaredContentLength()
        val valid = response.statusCode == 200 &&
            response.hasValidContentLength() &&
            (expectedTotalLength == null || declaredLength == null || declaredLength == expectedTotalLength)
        if (valid) return response
        closeUpstream(session.id, response)
        return null
    }

    /**
     * Open a byte range only when the origin proves the precise range and representation size we are
     * about to advertise downstream. Returning null denotes a protocol-invalid response; I/O failures
     * are intentionally thrown so the retry policy can distinguish them.
     */
    private suspend fun openVerifiedRangeUpstream(
        session: ProxySession,
        lease: StreamLease,
        requested: ByteRange,
        totalLength: Long,
    ): StreamResponse? {
        val response = openTracked(session, lease, requested)
        val valid = response.hasValidContentLength() && UpstreamRangeVerifier.isExact(
            statusCode = response.statusCode,
            contentRanges = response.headers("Content-Range"),
            contentLength = response.declaredContentLength(),
            requested = requested,
            expectedTotalLength = totalLength,
        )
        if (valid) return response
        closeUpstream(session.id, response)
        return null
    }

    private fun StreamResponse.header(name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    private fun StreamResponse.headers(name: String): List<String> = listOfNotNull(header(name))

    private fun StreamResponse.hasValidContentLength(): Boolean =
        header("Content-Length")?.toLongOrNull()?.let { it >= 0 } ?: true

    private fun StreamResponse.declaredContentLength(): Long? =
        header("Content-Length")?.toLongOrNull()?.takeIf { it >= 0 }

    private fun rangeHeader(range: ByteRange): String =
        if (range.endInclusive == Long.MAX_VALUE) "bytes=${range.start}-"
        else "bytes=${range.start}-${range.endInclusive}"

    private suspend fun openTracked(
        session: ProxySession,
        lease: StreamLease,
        range: ByteRange?,
    ): StreamResponse {
        val response = lease.open(range).getOrThrow()
        return trackResponse(session.id, response)
    }

    private suspend fun openTracked(
        session: ProxySession,
        lease: StreamLease,
        resource: StreamResourceRef,
        range: ByteRange?,
    ): StreamResponse {
        val response = lease.open(resource, range).getOrThrow()
        return trackResponse(session.id, response)
    }

    private fun trackResponse(sessionId: String, response: StreamResponse): StreamResponse {
        val accepted = synchronized(lifecycleLock) {
            if (stopping || !sessions.isActive(sessionId)) false
            else {
                activeResponses.computeIfAbsent(sessionId) { ConcurrentHashMap.newKeySet() }.add(response)
                true
            }
        }
        if (!accepted) {
            runCatching { response.close() }
            throw IOException("Proxy session was revoked while opening source stream")
        }
        return response
    }

    private fun closeUpstream(sessionId: String, response: StreamResponse) {
        activeResponses[sessionId]?.let { responses ->
            responses.remove(response)
            if (responses.isEmpty()) activeResponses.remove(sessionId, responses)
        }
        runCatching { response.close() }
    }

    /**
     * Open an HLS playlist/segment upstream, retrying a FAST transient failure a few times ([UpstreamRetry]
     * decides). A live transcode segment can be momentarily unavailable (a quick 5xx or a connection reset)
     * right after a re-resolve; a couple of short retries ride that out. A read timeout is NOT retried — the
     * server is just slow to produce the segment, so another 30s attempt would only stall the TV further.
     * Returns a successful (200/206) source response to stream, or null (caller sends 502).
     */
    private suspend fun openHlsUpstream(
        session: ProxySession,
        lease: StreamLease,
        resource: StreamResourceRef?,
        range: ByteRange?,
    ): StreamResponse? {
        var attempt = 0
        while (true) {
            val result = runCatching { if (resource == null) openTracked(session, lease, range) else openTracked(session, lease, resource, range) }
            val response = result.getOrNull()
            if (response != null && (
                    UpstreamRetry.isSuccess(response.statusCode) ||
                        (response.statusCode == 416 && range != null)
                    )
            ) return response
            val code = response?.statusCode
            response?.let { closeUpstream(session.id, it) }
            val timedOut = result.exceptionOrNull() is java.io.InterruptedIOException // incl. SocketTimeoutException
            if (attempt >= HLS_OPEN_MAX_RETRIES || !UpstreamRetry.shouldRetryOpen(code, timedOut)) return null
            attempt++
            runCatching { Thread.sleep(HLS_OPEN_RETRY_BACKOFF_MS * attempt) }
        }
    }

    /** Copy a (segment/key/subtitle) body downstream through the bounded buffer; abort if the TV leaves. */
    private fun copyTo(input: InputStream, out: OutputStream) {
        input.use { ins ->
            val buf = ByteArray(MemoryBufferPolicy.COPY_CHUNK_BYTES)
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
            val buf = ByteArray(MemoryBufferPolicy.COPY_CHUNK_BYTES)
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
                val buf = ByteArray(MemoryBufferPolicy.COPY_CHUNK_BYTES)
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

    private fun looksLikePlaylist(contentType: String?): Boolean =
        contentType != null &&
            (contentType.contains("mpegurl", true) || contentType.contains("vnd.apple", true))

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
    private suspend fun streamResilient(
        session: ProxySession,
        lease: StreamLease,
        initialResp: StreamResponse,
        rangeStart: Long,
        rangeEndInclusive: Long?,
        expectedTotalLength: Long,
        out: OutputStream,
        canResume: Boolean,
    ) {
        val policy = ResilientStreamPolicy(rangeStart, rangeEndInclusive)
        val watchdog = ThroughputWatchdog()
        val buf = ByteArray(MemoryBufferPolicy.COPY_CHUNK_BYTES)
        var response: StreamResponse? = initialResp
        try {
            while (true) {
                if (response == null) {
                    val rangeEnd = policy.rangeEndInclusive
                    if (!canResume || rangeEnd == null) return
                    val requested = ByteRange(policy.nextOffset, rangeEnd)
                    response = try {
                        openVerifiedRangeUpstream(session, lease, requested, expectedTotalLength)
                    } catch (_: Exception) {
                        if (!retryOrGiveUp(policy)) return else continue
                    }
                    // A protocol-invalid resume response (for example a 200 from byte zero) must never
                    // be spliced into the renderer response. End rather than retrying corrupted bytes.
                    if (response == null) {
                        logger.w(TAG, "Upstream did not honour the resume range; ending transfer")
                        return
                    }
                }
                val resp = response
                response = null
                val result = try {
                    copyStream(resp.body, out, buf, policy, watchdog)
                } catch (_: Exception) {
                    StreamResult.UPSTREAM_FAILED
                } finally {
                    closeUpstream(session.id, resp)
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
            response?.let { closeUpstream(session.id, it) }
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
        watchdog: ThroughputWatchdog,
    ): StreamResult {
        input.use { ins ->
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
                    if (sinceFlush >= MemoryBufferPolicy.FLUSH_INTERVAL_BYTES) {
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
        /** Hard cap on a source HLS playlist read into memory. */
        private const val MAX_PLAYLIST_BYTES = 4 * 1024 * 1024
    }
}
