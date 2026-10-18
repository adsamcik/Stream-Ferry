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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

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
    /** Encoder input is source traffic, never renderer delivery or adaptive-bitrate evidence. */
    private val transcodeInputs = ConcurrentHashMap.newKeySet<String>()
    /** Open bodies are tracked so local teardown can cut renderer access before remote cleanup. */
    private val activeResponses = ConcurrentHashMap<String, MutableSet<StreamResponse>>()

    /**
     * Optional sink notified of each chunk of bytes successfully delivered downstream (to the TV).
     * Used by the adaptive-bitrate controller to measure real throughput. Receives only a byte count
     * (no URL/token), so it is safe. Set while a session is active; cleared on stop.
     */
    @Volatile
    var byteListener: ((Long) -> Unit)? = null

    /** Completed playable media only; scoped to the exact proxy session and seek epoch. */
    @Volatile var mediaDeliveryListener: ((MediaDelivery) -> Unit)? = null
    private val deliveryTrackers = ConcurrentHashMap<String, ProxyDeliveryTracker>()

    fun renewDeliveryEpoch(sessionId: String): Long =
        deliveryTrackers.computeIfAbsent(sessionId) { ProxyDeliveryTracker() }.renew()

    private fun deliveryEpoch(session: ProxySession) = deliveryTrackers.computeIfAbsent(session.id) { ProxyDeliveryTracker() }.epoch()

    private fun noteFileBytes(session: ProxySession, epoch: Long, offset: Long, bytes: ByteArray, length: Int, out: OutputStream) {
        if (session.id in transcodeInputs) return
        if (session.isHls || session.contentType.substringBefore(';').lowercase() !in
            setOf("video/mp4", "audio/mp4", "video/quicktime", "application/octet-stream")) return
        // Small final writes may still be in BufferedOutputStream. Failed flushes cannot count.
        out.flush()
        deliveryTrackers[session.id]?.written(offset, bytes, length, epoch)?.forEach {
            mediaDeliveryListener?.invoke(MediaDelivery(session.id, epoch, it.startMs, it.endMs))
        }
    }

    private fun noteMediaDelivered(session: ProxySession, resourceId: String, range: ByteRange?, epoch: Long) {
        if (session.id in transcodeInputs) return
        val tracker = deliveryTrackers[session.id] ?: return
        tracker.completed(resourceId, range, epoch).forEach {
            mediaDeliveryListener?.invoke(MediaDelivery(session.id, epoch, it.startMs, it.endMs))
        }
    }

    /**
     * Invoked when a downstream (TV) media connection closes, with the bytes delivered, how long it
     * lived, and whether the response completed normally (vs. the TV aborting mid-stream). Lets the engine
     * detect a TV that connected, read a little, then *bailed* — a fast "can't play this" signal. A fully
     * delivered range/segment (completedNormally = true) is NOT a bail-out. Byte count only; no URL/token.
     */
    @Volatile
    var onDownstreamClosed: ((bytesServed: Long, durationMs: Long, completedNormally: Boolean) -> Unit)? = null

    /** Per-session opaque resource maps for HLS playlist rewriting (cleared on stop). */
    private val hlsStates = ConcurrentHashMap<String, HlsState>()

    private data class PlaylistWindow(
        val current: Set<StreamResourceRef>,
        val previous: Set<StreamResourceRef> = emptySet(),
    ) {
        val references: Set<StreamResourceRef> get() = current + previous
    }

    private class HlsState {
        val registry = HlsSegmentRegistry()
        val resources = ConcurrentHashMap<String, StreamResourceRef>()
        val windows = HashMap<String, PlaylistWindow>()
        private val active = HashMap<StreamResourceRef, Int>()
        // Resolving a full playlist is one transaction: concurrent variant refreshes must not evict
        // each other's provisional provider references before their advertised tokens are retained.
        val rewriteMutex = Mutex()

        @Synchronized
        fun pin(token: String): StreamResourceRef? = registry.resolve(token)?.let(resources::get)?.also {
            active[it] = (active[it] ?: 0) + 1
        }

        @Synchronized
        fun unpin(resource: StreamResourceRef) {
            val count = active[resource] ?: return
            if (count == 1) active.remove(resource) else active[resource] = count - 1
        }

        @Synchronized
        fun retainPlaylist(parent: String?, references: Set<StreamResourceRef>, lease: StreamLease): Map<String, String> {
            val key = parent.orEmpty()
            val candidates = HashMap(windows)
            val existing = windows[key]
            candidates[key] = if (existing?.current == references) existing
                else PlaylistWindow(references, existing?.current.orEmpty())
            val reachable = LinkedHashSet<String>()
            val pending = ArrayDeque<String>().apply { add("") }
            val retained = LinkedHashSet<StreamResourceRef>().apply { addAll(active.keys) }
            while (pending.isNotEmpty()) {
                val next = pending.removeFirst()
                if (!reachable.add(next)) continue
                candidates[next]?.references?.forEach { reference ->
                    retained += reference
                    if (reference.opaqueId in candidates) pending.add(reference.opaqueId)
                }
            }
            // Reject over-capacity playlists before changing any mapping. Preserve the latest and
            // previous windows of every reachable rendition, rather than expiring an advertised
            // audio/subtitle/video URL when a sibling playlist refreshes.
            val encoded = registry.encodeBatch(references.map { it.opaqueId }, retained.map { it.opaqueId })
            references.forEach { resources[it.opaqueId] = it }
            resources.keys.retainAll(registry.resources())
            lease.retainResources(resources.values.toSet())
            windows.clear()
            candidates.filterKeys { it in reachable }.forEach { (id, window) -> windows[id] = window }
            return encoded
        }
    }

    /** Per-session on-device transcode origins (cleared/released on stop). */
    private val clientTranscoders = ConcurrentHashMap<String, ClientTranscodeSource>()

    /** Register an on-device transcode origin for a session; its playlist + segments are served here. */
    fun registerClientTranscode(sessionId: String, source: ClientTranscodeSource) {
        clientTranscoders[sessionId] = source
    }

    fun unregisterClientTranscode(sessionId: String) {
        clientTranscoders.remove(sessionId)?.let { runCatching { it.release() } }
    }

    fun registerStreamLease(sessionId: String, lease: StreamLease, rendererOutput: Boolean = true) {
        require(sessions.isActive(sessionId)) { "Proxy session is not active" }
        if (!rendererOutput) transcodeInputs.add(sessionId)
        remoteLeases[sessionId] = lease
    }

    fun unregisterStreamLease(sessionId: String) {
        transcodeInputs.remove(sessionId)
        deliveryTrackers.remove(sessionId)
        remoteLeases.remove(sessionId)
        val responses = synchronized(lifecycleLock) {
            activeResponses.remove(sessionId)?.toList().orEmpty()
        }
        responses.forEach { response -> runCatching { response.close() } }
        hlsStates.remove(sessionId)
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
            transcodeInputs.clear()
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
        hlsStates.clear()
        deliveryTrackers.clear()
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
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: IOException) {
                    // Stop can close an accepted socket before its handler obtains either stream.
                    if (s.isActive && !stopping) logger.w(TAG, "Relay socket unavailable", error)
                } finally {
                    runCatching { client.close() }
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
            var sessionId: String? = null
            var completed = false
            val downstreamClosed = onDownstreamClosed
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
                        sessionId = lookup.session.id
                        servedMedia = req.method == "GET" && lookup.session.id !in transcodeInputs
                        serve(lookup.session, req, out)
                        out.flush()
                        completed = true
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                if (!currentCoroutineContext().isActive || stopping || sessionId?.let { !sessions.isActive(it) } == true) {
                    logger.trace(TAG, "Relay closed after session teardown")
                } else {
                    logger.w(TAG, "Request handling error", e)
                }
            } finally {
                runCatching { out.flush() }
                // Report the outcome of a real media fetch so the engine can spot a TV that bailed early.
                if (servedMedia) {
                    val elapsedMs = System.currentTimeMillis() - startMs
                    // Always-on connection summary: a shared report can distinguish a TV that never fetched
                    // (no such line) from one that fetched N bytes then closed (couldn't decode / stalled).
                    val cancelled = !currentCoroutineContext().isActive || stopping || sessionId?.let { !sessions.isActive(it) } == true
                    val outcome = when {
                        cancelled -> "cancelled"
                        counting.writeFailed -> "downstream_closed"
                        completed -> "complete"
                        else -> "upstream_failed"
                    }
                    val clean = outcome == "complete"
                    logger.trace(TAG, "TV connection closed: ${counting.count} bytes in ${elapsedMs}ms (clean=$clean outcome=$outcome)")
                    // An upstream failure is not evidence that the TV cannot decode the format.
                    // Never deliver a revoked stream's outcome to the replacement playback session.
                    if (!cancelled && (completed || counting.writeFailed)) runCatching {
                        downstreamClosed?.invoke(counting.count, elapsedMs, clean)
                    }
                }
            }
        }
    }

    private suspend fun serve(session: ProxySession, req: RequestLine, out: OutputStream) {
        val head = req.method == "HEAD"
        val epoch = deliveryEpoch(session)
        // On-device transcode: serve the phone-hosted HLS/CMAF playlist + on-demand segments.
        clientTranscoders[session.id]?.let {
            serveClientTranscode(session, it, req, out, head)
            return
        }
        // Offline download: serve the app-private local file (the TV still only gets the proxy URL).
        if (session.localFilePath != null) {
            serveLocalFile(session, req, out, head, epoch)
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
            serveUnknownLengthUpstream(session, out, head, epoch)
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
        val first = catchingNonCancel {
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
        streamResilient(session, lease, first, rangeStart, rangeEndInclusive, totalLength, out, canResume = true, epoch = epoch)
    }

    private suspend fun serveUnknownLengthUpstream(session: ProxySession, out: OutputStream, head: Boolean, epoch: Long) {
        val lease = remoteLeases[session.id]
        val first = lease?.let { catchingNonCancel { openFullUpstream(session, it) }.getOrNull() }
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
        streamResilient(session, lease, first, rangeStart = 0, rangeEndInclusive = contentLength?.minus(1), expectedTotalLength = -1, out = out, canResume = false, epoch = epoch)
    }

    /** Serve source-owned HLS resources without learning their URLs, credentials, or server type. */
    private suspend fun serveHls(
        session: ProxySession,
        req: RequestLine,
        segParam: String?,
        out: OutputStream,
        head: Boolean,
    ) {
        val delivery = deliveryTrackers.computeIfAbsent(session.id) { ProxyDeliveryTracker() }
        val deliveryEpoch = delivery.epoch()
        val lease = remoteLeases[session.id]
        if (lease == null) {
            writeStatus(out, 404, "Not Found")
            return
        }
        val state = hlsStates.computeIfAbsent(session.id) { HlsState() }
        val registry = state.registry
        val resource = segParam?.let(state::pin)
        if (segParam != null && resource == null) {
            writeStatus(out, 404, "Not Found")
            return
        }
        try {
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
                val responseValid = response.hasIdentityContentEncoding() && when {
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
                    logger.w(TAG, "HLS source did not prove the requested range or identity encoding")
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
                        state.rewriteMutex.withLock {
                            val rewriter = HlsRewriter(proxyBase)
                            val rawReferences = rewriter.uriReferences(body, registry.capacity)
                            val resolvedByRaw = LinkedHashMap<String, StreamResourceRef>()
                            for (uri in rawReferences) {
                                ensureSessionActive(session)
                                val nested = (response.playlistResolver?.invoke(uri) ?: lease.resolve(uri, resource)).getOrThrow()
                                    ?: throw IllegalArgumentException("Untrusted HLS URI")
                                resolvedByRaw[uri] = nested
                            }
                            ensureSessionActive(session)
                            val encodedById = state.retainPlaylist(resource?.opaqueId, resolvedByRaw.values.toSet(), lease)
                            delivery.playlist(resource?.opaqueId, body, resolvedByRaw.mapValues { it.value.opaqueId })
                            rewriter.rewrite(body) { uri ->
                                encodedById.getValue(resolvedByRaw.getValue(uri).opaqueId)
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
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
                    if (!head && copyHlsResource(session, lease, requireNotNull(resource), response, out)) {
                        ensureSessionActive(session)
                        noteMediaDelivered(session, resource.opaqueId, range, deliveryEpoch)
                    }
                }
            } finally {
                closeUpstream(session.id, response)
            }
        } finally {
            resource?.let(state::unpin)
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
            response.hasIdentityContentEncoding() && response.hasValidContentLength() &&
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
        val valid = response.hasIdentityContentEncoding() && response.hasValidContentLength() && UpstreamRangeVerifier.isExact(
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

    // Downstream byte offsets and lengths describe raw media. A still-encoded representation cannot
    // be relayed or resumed under those offsets; a partially received gzip entity cannot be decoded
    // safely here either. Sources may transparently decode full responses before returning them.
    private fun StreamResponse.hasIdentityContentEncoding(): Boolean =
        header("Content-Encoding")?.trim()?.equals("identity", ignoreCase = true) ?: true

    private fun StreamResponse.hasValidContentLength(): Boolean =
        header("Content-Length")?.let { it.toLongOrNull()?.let { length -> length >= 0 } ?: false } ?: true

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
        ensureSessionActive(session)
        val response = lease.open(range).getOrThrow()
        return trackResponse(session.id, response)
    }

    private suspend fun openTracked(
        session: ProxySession,
        lease: StreamLease,
        resource: StreamResourceRef,
        range: ByteRange?,
    ): StreamResponse {
        ensureSessionActive(session)
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
            throw CancellationException("Proxy session was revoked while opening source stream")
        }
        return response
    }

    private suspend fun ensureSessionActive(session: ProxySession) {
        currentCoroutineContext().ensureActive()
        if (stopping || !sessions.isActive(session.id)) {
            throw CancellationException("Proxy session revoked")
        }
    }

    private inline fun <T> catchingNonCancel(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Result.failure(error)
    }

    private fun closeUpstream(sessionId: String, response: StreamResponse) {
        synchronized(lifecycleLock) {
            // Registration and empty-set removal must be one ownership transaction. Otherwise a
            // concurrently opened body can enter this set just before its map entry is discarded,
            // leaving Stop unable to find and close that body.
            activeResponses[sessionId]?.let { responses ->
                responses.remove(response)
                if (responses.isEmpty()) activeResponses.remove(sessionId, responses)
            }
        }
        // Closing a provider body can block; keep it outside the registration/teardown lock.
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
            ensureSessionActive(session)
            val result = catchingNonCancel { if (resource == null) openTracked(session, lease, range) else openTracked(session, lease, resource, range) }
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
            delay(HLS_OPEN_RETRY_BACKOFF_MS * attempt)
        }
    }

    /**
     * Continue a truncated HLS entity only when both its representation identity and exact byte
     * window can be re-established. Never splice a newly generated segment (or an ignored Range)
     * onto bytes already sent to the TV. A missing validator/length fails the existing response
     * honestly; the renderer can then retry the resource as a new response.
     */
    private suspend fun copyHlsResource(
        session: ProxySession,
        lease: StreamLease,
        resource: StreamResourceRef,
        initial: StreamResponse,
        out: OutputStream,
    ): Boolean {
        val initialRange = UpstreamRangeVerifier.verifiedContentRange(
            initial.statusCode, initial.headers("Content-Range"), initial.declaredContentLength(),
        )
        val length = initial.declaredContentLength() ?: initialRange?.let { it.endInclusive - it.start + 1 }
        val start = initialRange?.start ?: 0L
        val total = initialRange?.totalLength ?: length
        val end = length?.let { start + it - 1 }
        val validator = representationValidator(initial)
        val buffer = ByteArray(MemoryBufferPolicy.COPY_CHUNK_BYTES)
        var copied = 0L
        var attempts = 0
        var response = initial
        try {
            while (true) {
                ensureSessionActive(session)
                var failure: IOException? = null
                try {
                    while (length == null || copied < length) {
                        ensureSessionActive(session)
                        val remaining = length?.minus(copied) ?: Long.MAX_VALUE
                        val count = response.body.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                        if (count < 0) {
                            if (length != null && copied < length) throw IOException("HLS body ended before declared length")
                            break
                        }
                        if (count == 0) continue
                        try {
                            out.write(buffer, 0, count)
                            copied += count
                            // Forward each bounded chunk immediately. Do not hold startup bytes while
                            // waiting for an upstream retry or for a whole segment to finish.
                            out.flush()
                            if (session.id !in transcodeInputs) byteListener?.invoke(count.toLong())
                        } catch (_: IOException) {
                            return false // A downstream disconnect must never reconnect upstream.
                        }
                    }
                    return copied > 0
                } catch (error: IOException) {
                    failure = error
                }
                closeUpstream(session.id, response)
                ensureSessionActive(session)
                if (validator == null || total == null || end == null || attempts >= HLS_BODY_MAX_RETRIES) {
                    throw requireNotNull(failure)
                }
                attempts++
                delay(HLS_OPEN_RETRY_BACKOFF_MS * attempts)
                ensureSessionActive(session)
                val requested = ByteRange(start + copied, end)
                val replacement = openHlsUpstream(session, lease, resource, requested)
                    ?: throw IOException("HLS body resume open failed", failure)
                response = replacement
                val valid = representationValidator(replacement) == validator &&
                    replacement.hasIdentityContentEncoding() &&
                    replacement.hasValidContentLength() &&
                    UpstreamRangeVerifier.isExact(
                        replacement.statusCode, replacement.headers("Content-Range"),
                        replacement.declaredContentLength(), requested, total,
                    )
                if (!valid) throw IOException("HLS source changed representation or did not honour resume range")
            }
        } finally {
            if (response !== initial) closeUpstream(session.id, response)
        }
    }

    private data class RepresentationValidator(val header: String, val value: String)

    private fun representationValidator(response: StreamResponse): RepresentationValidator? {
        val etag = response.header("ETag")?.trim()
        if (etag != null && etag.length >= 2 && etag.first() == '"' && etag.last() == '"' &&
            etag.substring(1, etag.lastIndex).all { it.code == 0x21 || it.code in 0x23..0x7e || it.code in 0x80..0xff }) {
            return RepresentationValidator("ETag", etag)
        }
        // Use a conservative one-minute Date/Last-Modified margin when inferring a strong timestamp
        // validator, allowing for clock differences (RFC 9110 section 8.8.2.2).
        fun date(name: String): Long? = response.header(name)?.let { value ->
            runCatching { ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toEpochSecond() }.getOrNull()
        }
        val modified = date("Last-Modified") ?: return null
        val observed = date("Date") ?: return null
        return if (observed - modified >= 60) RepresentationValidator("Last-Modified", modified.toString()) else null
    }

    /** Copy a (segment/key/subtitle) body downstream through the bounded buffer; abort if the TV leaves. */
    private suspend fun copyTo(session: ProxySession, input: InputStream, out: OutputStream, expectedLength: Long? = null): Boolean =
        input.use { ins ->
            val buf = ByteArray(MemoryBufferPolicy.COPY_CHUNK_BYTES)
            var copied = 0L
            while (true) {
                ensureSessionActive(session)
                val remaining = expectedLength?.let { it - copied } ?: Long.MAX_VALUE
                if (remaining == 0L) break
                val n = ins.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                if (n < 0) {
                    if (expectedLength != null && copied < expectedLength) throw IOException("Upstream body ended before declared length")
                    break
                }
                try {
                    out.write(buf, 0, n)
                    copied += n
                    if (session.id !in transcodeInputs) byteListener?.invoke(n.toLong())
                } catch (e: Exception) {
                    return false // downstream (TV) disconnected
                }
            }
            copied > 0 && runCatching { out.flush() }.isSuccess
        }

    /**
     * Serve an app-private downloaded file (offline playback). The file length is known, so full HTTP
     * byte-range / seek support is provided via [HttpResponsePlan] + a [RandomAccessFile]. The file path
     * is fixed on the session (never taken from the request), so there is no path-traversal surface.
     */
    private suspend fun serveLocalFile(session: ProxySession, req: RequestLine, out: OutputStream, head: Boolean, epoch: Long) {
        val path = session.localFilePath!!
        if (path.startsWith("content://")) {
            serveContentUri(session, path, req, out, head, epoch)
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
                ensureSessionActive(session)
                val toRead = minOf(remaining, buf.size.toLong()).toInt()
                val n = raf.read(buf, 0, toRead)
                if (n < 0) throw IOException("Local file ended before declared length")
                try {
                    out.write(buf, 0, n)
                    noteFileBytes(session, epoch, endInclusive - remaining + 1, buf, n, out)
                    if (session.id !in transcodeInputs) byteListener?.invoke(n.toLong())
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
    private suspend fun serveContentUri(session: ProxySession, uriString: String, req: RequestLine, out: OutputStream, head: Boolean, epoch: Long) {
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
                if (!head) FileInputStream(pfd.fileDescriptor).use { copyTo(session, it, out) }
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
                    ensureSessionActive(session)
                    bb.clear()
                    if (remaining < buf.size) bb.limit(remaining.toInt())
                    val n = channel.read(bb)
                    if (n < 0) throw IOException("Local content ended before declared length")
                    try {
                        out.write(buf, 0, n)
                        noteFileBytes(session, epoch, endInclusive - remaining + 1, buf, n, out)
                        if (session.id !in transcodeInputs) byteListener?.invoke(n.toLong())
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
     * Serve an on-device transcode session: no `seg` selects the master HLS playlist; `seg=init_N`
     * selects clip N's CMAF init; `seg=<index>` selects an on-demand media segment (full seek). The TV
     * only ever fetches these phone proxy URLs — never the source.
     */
    private fun serveClientTranscode(session: ProxySession, source: ClientTranscodeSource, req: RequestLine, out: OutputStream, head: Boolean) {
        if (source.isProgressive) {
            // A live TS entity has no final byte length and cannot honour byte ranges. Close-delimited
            // HTTP lets a renderer start on the first packets while the encoder continues producing.
            if (head) {
                writeSimpleHeaders(out, 200, "OK", "video/mp2t", null, false, "Accept-Ranges" to "none")
                return
            }
            val input = source.openProgressive()
            if (input == null) { writeStatus(out, 503, "Service Unavailable"); return }
            input.use {
                // Wait for actual output before promising a successful media response.
                val bytes = ByteArray(188 * 32)
                var count = it.read(bytes)
                if (count < 0) { writeStatus(out, 503, "Service Unavailable"); return }
                writeSimpleHeaders(out, 200, "OK", "video/mp2t", null, false, "Accept-Ranges" to "none")
                while (count >= 0 && sessions.isActive(session.id) && !stopping) {
                    if (count > 0) {
                        out.write(bytes, 0, count)
                        out.flush()
                        byteListener?.invoke(count.toLong())
                    }
                    count = it.read(bytes)
                }
            }
            return
        }
        val delivery = deliveryTrackers.computeIfAbsent(session.id) { ProxyDeliveryTracker() }
        val deliveryEpoch = delivery.epoch()
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
                val playlist = body.toString(Charsets.UTF_8)
                delivery.playlist(null, playlist, playlist.lineSequence().map { it.trim() }
                    .filter { it.isNotEmpty() && !it.startsWith('#') }
                    .associateWith { queryParam(it, "seg") ?: it })
                logger.trace(TAG, "TV fetched on-device transcode media playlist (${body.size} B)")
                writeSimpleHeaders(out, 200, "OK", "application/vnd.apple.mpegurl", body.size.toLong(), acceptRanges = false, extra = null)
                if (!head) { out.write(body); runCatching { out.flush() } }
            }
            "init" -> {
                logger.trace(TAG, "TV fetched on-device transcode init segment")
                serveSegmentResource(source.initSegment(allowExport = !head), out, head, source.segmentContentType)
            }
            else -> {
                if (seg.startsWith("init_")) {
                    val rawIndex = seg.removePrefix("init_")
                    val index = rawIndex.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.toIntOrNull()
                    if (index == null || index < 0) { writeStatus(out, 404, "Not Found"); return }
                    serveSegmentResource(source.initSegment(index, allowExport = !head), out, head, source.segmentContentType)
                    return
                }
                val index = seg.toIntOrNull()
                if (index == null) { writeStatus(out, 404, "Not Found"); return }
                logger.trace(TAG, "TV fetched on-device transcode segment #$index")
                if (serveSegmentResource(source.mediaSegment(index, allowExport = !head), out, head, source.segmentContentType)) {
                    noteMediaDelivered(session, seg, null, deliveryEpoch)
                }
            }
        }
    }

    /** First line or two of a served playlist for diagnostics (redaction still applies to the whole line). */
    private fun playlistHead(body: ByteArray): String =
        String(body, Charsets.UTF_8).replace("\n", " | ").take(300)

    private fun serveSegmentResource(resource: ClientTranscodeSource.Resource, out: OutputStream, head: Boolean, mimeType: String = "video/mp4"): Boolean =
        when (resource) {
            is ClientTranscodeSource.Resource.Ready -> serveSegmentBytes(resource.bytes, out, head, mimeType)
            ClientTranscodeSource.Resource.NotFound -> { writeStatus(out, 404, "Not Found"); false }
            // An export failure/cancellation is retryable by the receiver, but must not masquerade as a
            // successful zero-byte fMP4 response. HEAD follows the same state mapping without exporting.
            ClientTranscodeSource.Resource.Unavailable -> { writeStatus(out, 503, "Service Unavailable"); false }
            ClientTranscodeSource.Resource.TimedOut -> { writeStatus(out, 504, "Gateway Timeout"); false }
        }

    private fun serveSegmentBytes(bytes: ByteArray, out: OutputStream, head: Boolean, mimeType: String = "video/mp4"): Boolean {
        writeSimpleHeaders(out, 200, "OK", mimeType, bytes.size.toLong(), acceptRanges = false, extra = null)
        if (head || bytes.isEmpty()) return false
        out.write(bytes)
        return runCatching { out.flush() }.isSuccess
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
        override fun flush() = track { out.flush() }
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
        epoch: Long,
    ) {
        val policy = ResilientStreamPolicy(rangeStart, rangeEndInclusive)
        val watchdog = ThroughputWatchdog()
        val buf = ByteArray(MemoryBufferPolicy.COPY_CHUNK_BYTES)
        val validator = representationValidator(initialResp)
        var response: StreamResponse? = initialResp
        try {
            while (true) {
                ensureSessionActive(session)
                if (response == null) {
                    val rangeEnd = policy.rangeEndInclusive
                    if (!canResume || rangeEnd == null || validator == null) throw IOException("Upstream transfer ended before completion")
                    val requested = ByteRange(policy.nextOffset, rangeEnd)
                    response = try {
                        openVerifiedRangeUpstream(session, lease, requested, expectedTotalLength)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        ensureSessionActive(session)
                        if (!retryOrGiveUp(policy)) throw IOException("Upstream resume attempts exhausted") else continue
                    }
                    // A protocol-invalid resume response (for example a 200 from byte zero) must never
                    // be spliced into the renderer response. End rather than retrying corrupted bytes.
                    if (response == null) {
                        logger.w(TAG, "Upstream did not honour the resume range; ending transfer")
                        throw IOException("Upstream did not honour the resume range")
                    }
                    if (representationValidator(response) != validator ||
                        !response.hasIdentityContentEncoding()) {
                        throw IOException("Upstream changed representation during resume")
                    }
                }
                val resp = response
                response = null
                val result = try {
                    copyStream(session, resp.body, out, buf, policy, watchdog, epoch)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    StreamResult.UPSTREAM_FAILED
                } finally {
                    closeUpstream(session.id, resp)
                }
                ensureSessionActive(session)
                when (result) {
                    StreamResult.COMPLETE, StreamResult.DOWNSTREAM_GONE -> return
                    StreamResult.UPSTREAM_STALLED -> {
                        logger.w(TAG, "Upstream throughput collapsed (slow-trickle); ending transfer")
                        throw IOException("Upstream throughput stalled")
                    }
                    StreamResult.UPSTREAM_FAILED -> {
                        if (!canResume || validator == null || !retryOrGiveUp(policy)) throw IOException("Upstream transfer failed")
                    }
                }
            }
        } finally {
            response?.let { closeUpstream(session.id, it) }
            runCatching { out.flush() }
        }
    }

    /** Apply the resilience policy after an upstream failure; cancellation interrupts the backoff. */
    private suspend fun retryOrGiveUp(policy: ResilientStreamPolicy): Boolean {
        val roll = ThreadLocalRandom.current().nextDouble()
        return when (val decision = policy.onRecoverableFailure(roll)) {
            is ResilientStreamPolicy.Decision.GiveUp -> {
                logger.w(TAG, "Upstream unrecoverable after ${policy.consecutiveFailures} attempts; ending transfer")
                false
            }
            is ResilientStreamPolicy.Decision.Retry -> {
                if (decision.delayMillis > 0) delay(decision.delayMillis)
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
    private suspend fun copyStream(
        session: ProxySession,
        input: InputStream,
        out: OutputStream,
        buf: ByteArray,
        policy: ResilientStreamPolicy,
        watchdog: ThroughputWatchdog,
        epoch: Long,
    ): StreamResult {
        input.use { ins ->
            var sinceFlush = 0L
            while (true) {
                ensureSessionActive(session)
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
                    noteFileBytes(session, epoch, policy.nextOffset, buf, writable, out)
                    policy.recordProgress(writable.toLong())
                    if (session.id !in transcodeInputs) byteListener?.invoke(writable.toLong())
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
        private const val HLS_BODY_MAX_RETRIES = 2
        private const val HLS_OPEN_MAX_RETRIES = 2
        private const val HLS_OPEN_RETRY_BACKOFF_MS = 250L
        /** Hard cap on a source HLS playlist read into memory. */
        private const val MAX_PLAYLIST_BYTES = 4 * 1024 * 1024
    }
}
