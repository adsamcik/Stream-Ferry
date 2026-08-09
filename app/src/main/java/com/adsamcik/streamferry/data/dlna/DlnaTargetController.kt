package com.adsamcik.streamferry.data.dlna

import com.adsamcik.streamferry.core.dlna.DidlLite
import com.adsamcik.streamferry.core.dlna.DlnaTerminalStatePolicy
import com.adsamcik.streamferry.core.http.BoundedBody
import com.adsamcik.streamferry.core.dlna.SecureXml
import com.adsamcik.streamferry.core.dlna.RendererDescriptionParser
import com.adsamcik.streamferry.core.dlna.RendererServiceEndpoint
import com.adsamcik.streamferry.core.dlna.SsdpCandidateRegistry
import com.adsamcik.streamferry.core.dlna.SsdpCandidateRejection
import com.adsamcik.streamferry.core.dlna.SsdpDiscoveryLimiter
import com.adsamcik.streamferry.core.dlna.SsdpParser
import com.adsamcik.streamferry.core.redaction.LogRedactor
import com.adsamcik.streamferry.core.stream.Protocol
import com.adsamcik.streamferry.core.stream.TargetCapabilities
import com.adsamcik.streamferry.diagnostics.NetworkInfoProvider
import com.adsamcik.streamferry.domain.DiscoveredTarget
import com.adsamcik.streamferry.domain.TargetDiscoveryMetadata
import com.adsamcik.streamferry.domain.PlaybackFailureKind
import com.adsamcik.streamferry.domain.PlaybackTargetController
import com.adsamcik.streamferry.domain.PlaybackTargetEvent
import com.adsamcik.streamferry.domain.RendererStream
import com.adsamcik.streamferry.logging.DiagnosticsLogger
import com.adsamcik.streamferry.permissions.LocalNetworkAccessDeniedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.util.Locale

/**
 * DLNA / UPnP control point (Digital Media Controller) (§11). The Android app discovers MediaRenderers
 * via SSDP, controls them via AVTransport SOAP, and tells the TV to fetch media from the phone proxy
 * URL only (never Jellyfin). All XML is parsed with [SecureXml] (XXE-hardened) off the main thread.
 *
 * No third-party UPnP library is used (dependency rule §11): platform multicast + OkHttp SOAP only.
 */
class DlnaTargetController(
    private val logger: DiagnosticsLogger,
    private val network: NetworkInfoProvider,
    private val httpClient: OkHttpClient = OkHttpClient(),
    /** Fresh local-network permission check; no cached UI decision is trusted. */
    private val requireLocalNetworkAccess: () -> Unit = {},
) : PlaybackTargetController {

    override val protocol = Protocol.DLNA

    private val _events = MutableSharedFlow<PlaybackTargetEvent>(extraBufferCapacity = 16)
    override val events = _events.asSharedFlow()

    private data class ControlEndpoint(
        val service: RendererServiceEndpoint,
        val route: RendererEndpointPolicy.Endpoint,
    ) {
        val controlUrl: String get() = service.controlUrl
        val serviceType: String get() = service.serviceType
    }

    private data class Renderer(
        val avTransport: ControlEndpoint,
        val renderingControl: ControlEndpoint?,
    )
    private data class DescribedRenderer(
        val target: DiscoveredTarget,
        val renderer: Renderer,
    )

    private var connected: Renderer? = null
    private val endpointPolicy = RendererEndpointPolicy(network)
    @Volatile private var rendererEndpoints: Map<String, Renderer> = emptyMap()

    // A resume/reload start position (seconds) requested by [load]. DLNA has no "start position" in
    // SetAVTransportURI, so we issue a REL_TIME Seek — but only once the renderer is actually PLAYING (the
    // poll loop applies it), because a Seek before the transport has started is widely rejected/ignored.
    // Cleared after it is applied so we never re-seek a later user scrub.
    @Volatile private var pendingResumeSeconds: Long = 0L
    /** Duration of the stream whose URI was most recently accepted by the renderer. */
    @Volatile private var loadedDurationSeconds: Long? = null
    /** Last AVTransport state seen by the poll loop (PLAYING/TRANSITIONING/STOPPED/…), for diagnostics. */
    @Volatile private var lastTransportState: String? = null

    private val pollScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null
    // Signalled after a local control (seek/play/pause) so the status poll wakes early instead of waiting a
    // full interval — the phone then reflects the change within ~a SOAP round-trip. CONFLATED: a burst of
    // controls collapses to a single wake.
    private val pollWake = Channel<Unit>(Channel.CONFLATED)

    override suspend fun discover(timeoutMillis: Long): List<DiscoveredTarget> = withContext(Dispatchers.IO) {
        requireLocalNetworkAccess()
        val lock = network.multicastLock("dlna-discovery")?.also { it.setReferenceCounted(false); it.acquire() }
        try {
            ssdpSearch(timeoutMillis)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.w(TAG, "SSDP discovery failed (multicast may be blocked)", e)
            logger.event("discovery", "DLNA scan failed before completion")
            throw e
        } finally {
            runCatching { if (lock?.isHeld == true) lock.release() }
        }
    }

    /**
     * Collect SSDP replies and resolve descriptions concurrently until one absolute deadline. A slow
     * renderer therefore cannot block later replies, and no request is started once the scan budget ends.
     */
    private suspend fun ssdpSearch(timeoutMillis: Long): List<DiscoveredTarget> = coroutineScope {
        val deadlineNanos = System.nanoTime() + timeoutMillis.coerceAtLeast(0) * NANOS_PER_MILLISECOND
        fun remainingMillis(): Long = ((deadlineNanos - System.nanoTime()) / NANOS_PER_MILLISECOND).coerceAtLeast(0)

        val candidates = SsdpCandidateRegistry()
        val descriptionJobs = mutableListOf<Deferred<DescribedRenderer?>>()
        val limiter = SsdpDiscoveryLimiter()
        val semaphore = Semaphore(MAX_CONCURRENT_DESCRIPTIONS)
        val rejectionCounts = mutableMapOf<SsdpCandidateRejection, Int>()
        var receivedPackets = 0
        var parsedPackets = 0
        var truncatedPackets = 0
        var malformedPackets = 0
        var duplicateCandidates = 0
        var rendererLimitedCandidates = 0
        var floodLimitedCandidates = 0
        val socket = openSsdpSocket() ?: throw IOException("No usable physical LAN socket for SSDP discovery")
        socket.use { s ->
            val msearch = (
                "M-SEARCH * HTTP/1.1\r\n" +
                    "HOST: $SSDP_ADDR:$SSDP_PORT\r\n" +
                    "MAN: \"ssdp:discover\"\r\n" +
                    "MX: 2\r\n" +
                    "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n"
                ).toByteArray()
            val group = InetAddress.getByName(SSDP_ADDR)
            val datagram = DatagramPacket(msearch, msearch.size, InetSocketAddress(group, SSDP_PORT))
            var probesSent = 0
            var lastSendFailure: Exception? = null
            repeat(SSDP_PROBES) { index ->
                if (remainingMillis() <= 0) return@repeat
                try {
                    s.send(datagram)
                    probesSent += 1
                } catch (error: Exception) {
                    lastSendFailure = error
                }
                if (index < SSDP_PROBES - 1 && remainingMillis() > 0) delay(minOf(SSDP_PROBE_SPACING_MS, remainingMillis()))
            }
            if (probesSent == 0) {
                throw IOException("All SSDP M-SEARCH sends failed on the selected LAN", lastSendFailure)
            }
            logger.trace(
                TAG,
                "SSDP M-SEARCH sent on the selected LAN network (" + probesSent + "/" + SSDP_PROBES + " probes)",
            )
            logger.trace(
                TAG,
                "DLNA discovery context: lanIp=${network.lanIpv4() ?: "none"} vpnActive=${network.isVpnActive()} " +
                    "lanNetwork=${if (network.lanNetwork() != null) "available" else "NONE"}",
            )

            val buf = ByteArray(SsdpParser.MAX_MESSAGE_LEN + 1)
            val tracedUsns = HashSet<String>()
            val tracedRejections = HashSet<String>()
            while (remainingMillis() > 0) {
                s.soTimeout = minOf(remainingMillis(), SSDP_RECEIVE_SLICE_MS).coerceAtLeast(1).toInt()
                val packet = DatagramPacket(buf, buf.size)
                try {
                    s.receive(packet)
                } catch (_: SocketTimeoutException) {
                    continue // slice timeout; retain the absolute scan deadline
                } catch (error: Exception) {
                    throw IOException("SSDP receive failed on the selected LAN", error)
                }
                receivedPackets += 1
                // DatagramPacket does not expose a truncation flag. A full buffer is unsafe because it
                // may be a longer message silently truncated by the socket; reject it before parsing.
                if (packet.length >= buf.size) {
                    truncatedPackets += 1
                    continue
                }
                val msg = SsdpParser.parse(String(packet.data, 0, packet.length, Charsets.UTF_8))
                if (msg == null) {
                    malformedPackets += 1
                    continue
                }
                parsedPackets += 1
                if (tracedUsns.add(msg.usn ?: msg.location ?: "?")) {
                    logger.trace(TAG, "SSDP <- mediaRenderer=${msg.isMediaRenderer()} usn=${msg.usn} loc=${msg.location}")
                }
                val rejection = msg.candidateRejection()
                if (rejection != null) {
                    rejectionCounts[rejection] = rejectionCounts.getOrDefault(rejection, 0) + 1
                    val rejectionKey = (msg.usn ?: msg.location ?: msg.startLine) + "|" + rejection.name
                    if (tracedRejections.add(rejectionKey)) {
                        logger.trace(TAG, "SSDP candidate rejected: " + rejection.diagnostic)
                    }
                    continue
                }
                val sourceAddress = packet.address ?: continue
                val sourceIp = sourceAddress.hostAddress ?: "unknown"
                val identity = msg.usn?.trim().orEmpty()
                val location = msg.location?.trim().orEmpty()
                when (candidates.register(identity, location, sourceIp)) {
                    SsdpCandidateRegistry.Decision.DUPLICATE -> {
                        duplicateCandidates += 1
                        continue
                    }
                    SsdpCandidateRegistry.Decision.RENDERER_LIMIT -> {
                        rendererLimitedCandidates += 1
                        if (tracedRejections.add(identity + "|candidate-limit")) {
                            logger.trace(TAG, "SSDP renderer candidate limit reached")
                        }
                        continue
                    }
                    SsdpCandidateRegistry.Decision.ACCEPT -> Unit
                }
                if (!limiter.allowDescribe(sourceIp)) {
                    floodLimitedCandidates += 1
                    logger.trace(TAG, "SSDP describe rate-limited (flood guard)")
                    continue
                }
                descriptionJobs += async {
                    semaphore.withPermit {
                        val remaining = remainingMillis()
                        if (remaining <= 0) {
                            null
                        } else {
                            try {
                                describe(location, identity, sourceAddress, remaining)
                            } catch (error: CancellationException) {
                                throw error
                            } catch (error: LocalNetworkAccessDeniedException) {
                                throw error
                            } catch (error: Exception) {
                                logger.w(
                                    TAG,
                                    "Renderer description candidate failed unexpectedly from " + sourceIp +
                                        " (" + error.javaClass.simpleName + ")",
                                )
                                null
                            }
                        }
                    }
                }
            }
        }

        val selected = LinkedHashMap<String, DescribedRenderer>()
        descriptionJobs.awaitAll().filterNotNull().forEach { described ->
            selected.putIfAbsent(described.target.id.trim().lowercase(Locale.ROOT), described)
        }
        rendererEndpoints = selected.values.associate { it.target.id to it.renderer }
        val targets = selected.values.map { it.target }
        val rejected = rejectionCounts.entries
            .sortedBy { it.key.name }
            .joinToString { it.key.name.lowercase(Locale.ROOT) + "=" + it.value }
            .ifEmpty { "none" }
        logger.trace(
            TAG,
            "SSDP discovery finished: " + targets.size + " renderer(s); packets=" + receivedPackets +
                " parsed=" + parsedPackets + " malformed=" + malformedPackets +
                " truncated=" + truncatedPackets + " describes=" + descriptionJobs.size +
                " duplicates=" + duplicateCandidates + " rendererLimited=" + rendererLimitedCandidates +
                " floodLimited=" + floodLimitedCandidates + " rejected=[" + rejected + "]",
        )
        logger.event("discovery", "DLNA scan: " + targets.size + " renderer(s)")
        targets
    }

    /**
     * Open SSDP on the selected physical LAN. The socket starts unbound so Android can attach it to
     * the Wi-Fi [android.net.Network] before a local port is allocated. Some Android 16 vendor kernels
     * reject `Network.bindSocket` for an already-bound UDP socket with EPERM while a VPN is active.
     *
     * If the platform still rejects network attachment, bind explicitly to the same LAN address and
     * network interface. That fallback remains pinned to Wi-Fi/Ethernet and cannot silently use the VPN.
     */
    private fun openSsdpSocket(): MulticastSocket? {
        val lanNetwork = network.lanNetwork() ?: run {
            logger.trace(TAG, "No selected physical LAN network for SSDP discovery")
            return null
        }
        val lanAddress = network.lanIpv4()?.let { runCatching { InetAddress.getByName(it) }.getOrNull() } ?: run {
            logger.trace(TAG, "No LAN IPv4 address for SSDP discovery")
            return null
        }
        val lanInterface = runCatching { NetworkInterface.getByInetAddress(lanAddress) }.getOrNull()

        val networkBound = MulticastSocket(null)
        runCatching {
            networkBound.reuseAddress = true
            lanNetwork.bindSocket(networkBound)
            networkBound.bind(InetSocketAddress(lanAddress, 0))
            lanInterface?.let { networkBound.networkInterface = it }
            networkBound.timeToLive = SSDP_TTL
            return networkBound
        }.onFailure { error ->
            runCatching { networkBound.close() }
            logger.trace(
                TAG,
                "Android rejected SSDP Network binding; trying the physical LAN interface " +
                    "(${error.javaClass.simpleName}: ${error.message ?: "no detail"})",
            )
        }

        val interfaceBound = MulticastSocket(null)
        return runCatching {
            interfaceBound.reuseAddress = true
            lanInterface?.let { interfaceBound.networkInterface = it }
            interfaceBound.bind(InetSocketAddress(lanAddress, 0))
            interfaceBound.timeToLive = SSDP_TTL
            logger.trace(TAG, "SSDP socket pinned directly to the physical LAN interface")
            interfaceBound
        }.onFailure {
            runCatching { interfaceBound.close() }
            logger.w(TAG, "Couldn't bind SSDP to the physical LAN; discovery skipped", it)
        }.getOrNull()
    }

    private fun describe(
        location: String,
        usn: String,
        sourceAddress: InetAddress,
        remainingMillis: Long,
    ): DescribedRenderer? {
        val locationEndpoint = endpointPolicy.location(location, sourceAddress) ?: run {
            logger.trace(TAG, "Rejected SSDP LOCATION outside the selected renderer network")
            return null
        }
        val xmlBytes = fetch(locationEndpoint, remainingMillis)
        if (xmlBytes == null) {
            logger.w("dlna", "Device description fetch failed on the approved LAN route")
            return null
        }
        val doc = runCatching { SecureXml.parse(xmlBytes) }.getOrElse {
            logger.w("dlna", "Device description XML parse failed: ${it.javaClass.simpleName}")
            return null
        }
        val description = RendererDescriptionParser.parse(doc, locationEndpoint.url, usn) ?: run {
            logger.event("discovery", "Description has no unambiguous MediaRenderer AVTransport service — skipped")
            return null
        }
        val avRoute = endpointPolicy.service(description.avTransport.controlUrl, locationEndpoint) ?: run {
            logger.trace(TAG, "Rejected AVTransport URL outside the discovered renderer endpoint")
            return null
        }
        val renderingService = description.renderingControl
        val renderingRoute = renderingService?.let { endpointPolicy.service(it.controlUrl, locationEndpoint) }
        val renderer = Renderer(
            avTransport = ControlEndpoint(description.avTransport, avRoute),
            renderingControl = if (renderingService != null && renderingRoute != null) {
                ControlEndpoint(renderingService, renderingRoute)
            } else {
                null
            },
        )
        val friendly = description.friendlyName.take(64)
        logger.trace(TAG, "describe: '$friendly' ready (MediaRenderer + AVTransport resolved)")
        return DescribedRenderer(
            target = DiscoveredTarget(
                id = usn,
                // Compose renders plain text; XML-escaping here displays entities such as &amp; literally.
                displayName = friendly,
                protocol = Protocol.DLNA,
                capabilities = DLNA_BASELINE.copy(modelName = (description.modelName ?: friendly).take(64)),
                lastTestedStatus = null,
                discoveryMetadata = TargetDiscoveryMetadata(
                    dlnaUdn = description.udn,
                    dlnaUsn = usn,
                    // location() pins LOCATION to this SSDP source on the selected LAN.
                    validatedSourceHost = sourceAddress.hostAddress,
                    validatedDescriptionHost = locationEndpoint.host,
                    manufacturer = description.manufacturer,
                    modelName = description.modelName,
                    // Advertise volume only after its separately validated control URL resolved.
                    volumeControlAvailable = renderingService != null && renderingRoute != null,
                ),
            ),
            renderer = renderer,
        )
    }

    override suspend fun connect(target: DiscoveredTarget) {
        requireLocalNetworkAccess()
        val renderer = rendererEndpoints[target.id] ?: run {
            logger.w("connect", "DLNA connect failed — no renderer descriptor for '${target.displayName}'")
            error("Unknown renderer")
        }
        logger.event("connect", "DLNA connect -> ${target.displayName}")
        logger.trace(TAG, "DLNA connect -> ${target.displayName} @ ${renderer.avTransport.controlUrl}")
        connected = renderer
        _events.tryEmit(PlaybackTargetEvent.Connected)
    }

    override suspend fun load(proxyUrl: String, stream: RendererStream, title: String, durationSeconds: Long?, startPositionSeconds: Long) =
        withContext(Dispatchers.IO) {
            requireLocalNetworkAccess()
            val r = connected ?: error("Not connected")
            val didl = DidlLite.build(
                proxyUrl = proxyUrl,
                title = title,
                mimeType = stream.mimeType,
                byteSeekable = stream.isByteSeekable,
                durationSecs = durationSeconds,
            )
            // Remember any resume position; the poll loop issues the Seek once the renderer reports PLAYING.
            pendingResumeSeconds = startPositionSeconds.coerceAtLeast(0)
            val setUri = {
                soap(r.avTransport, "SetAVTransportURI", buildString {
                    append("<InstanceID>0</InstanceID>")
                    append("<CurrentURI>").append(DidlLite.escape(proxyUrl)).append("</CurrentURI>")
                    append("<CurrentURIMetaData>").append(DidlLite.escape(didl)).append("</CurrentURIMetaData>")
                })
            }
            try {
                setUri()
            } catch (e: Exception) {
                // A renderer still TRANSITIONING from the previous stop rejects the new URI with UPnP 701
                // ("Transition not available"); a transient timeout can also occur. Give it a moment to
                // settle and retry ONCE — this is what happens when the user switches videos quickly. A hard
                // rejection (any other fault) is surfaced immediately so recovery/transcode fallback runs.
                val transient = (e is SoapFaultException && e.upnpErrorCode == UPNP_TRANSITION_NOT_AVAILABLE) ||
                    e is java.io.IOException
                if (!transient) {
                    logger.e("dlna", "DLNA SetAVTransportURI failed (${stream.mimeType})", e)
                    throw e
                }
                logger.w("dlna", "DLNA SetAVTransportURI transient failure (${e.message}); settling ${SET_URI_RETRY_DELAY_MS}ms and retrying once")
                delay(SET_URI_RETRY_DELAY_MS)
                try {
                    setUri()
                } catch (e2: Exception) {
                    logger.e("dlna", "DLNA SetAVTransportURI failed after retry (${stream.mimeType})", e2)
                    throw e2
                }
            }
            logger.event(
                "dlna",
                "DLNA SetAVTransportURI sent (${stream.mimeType}, byteSeek=${stream.isByteSeekable}, proxy URL only)",
            )
            loadedDurationSeconds = durationSeconds?.takeIf { it > 0 }
            // A new stream was loaded (initial play OR a seek/bitrate-switch reload). Restart the poll
            // loop so position + end-of-media tracking continues for the new stream — the previous loop
            // breaks when the old stream goes STOPPED during the reload teardown.
            startPolling()
        }

    override suspend fun play() = controlThenPoll("Play", "<InstanceID>0</InstanceID><Speed>1</Speed>")
    override suspend fun pause() = controlThenPoll("Pause", "<InstanceID>0</InstanceID>")
    override suspend fun stop() = avAction("Stop", "<InstanceID>0</InstanceID>")

    override suspend fun diagnosticStatus(): String =
        "dlna transport=${lastTransportState ?: "?"} connected=${connected != null}"
    override suspend fun seekTo(positionSeconds: Long) = controlThenPoll(
        "Seek",
        "<InstanceID>0</InstanceID><Unit>REL_TIME</Unit><Target>${DidlLite.formatDuration(positionSeconds)}</Target>",
    )

    override suspend fun readCurrentVolume(): Float? = withContext(Dispatchers.IO) {
        val endpoint = connected?.renderingControl ?: return@withContext null
        runCatching {
            val reported = TAG_CURRENT_VOLUME.find(
                soap(endpoint, "GetVolume", "<InstanceID>0</InstanceID><Channel>Master</Channel>"),
            )?.groupValues?.get(1)?.trim()?.toIntOrNull() ?: return@runCatching null
            reported.takeIf { it in 0..100 }?.div(100f)
        }.onFailure { logger.w(TAG, "DLNA GetVolume failed", it) }.getOrNull()
    }

    /**
     * Issue an AVTransport control action, then wake the status poll so the phone reflects the change (new
     * position after a seek / transport state after play/pause) within ~a SOAP round-trip instead of up to
     * a full poll interval. The engine already shows the target optimistically; this makes the renderer's
     * authoritative confirmation arrive promptly so the two stay in sync.
     */
    private suspend fun controlThenPoll(action: String, body: String) {
        avAction(action, body)
        pollWake.trySend(Unit)
    }

    override suspend fun setVolume(level: Float) = withContext(Dispatchers.IO) {
        val renderer = checkNotNull(connected) { "Not connected to a DLNA renderer" }
        val endpoint = checkNotNull(renderer.renderingControl) { "DLNA renderer does not support volume control" }
        val percent = (level.coerceIn(0f, 1f) * 100).toInt()
        try {
            soap(
                endpoint,
                "SetVolume",
                "<InstanceID>0</InstanceID><Channel>Master</Channel><DesiredVolume>$percent</DesiredVolume>"
            )
        } catch (e: Exception) {
            logger.w(TAG, "DLNA SetVolume failed", e)
            throw e
        }
        Unit
    }

    override suspend fun disconnect() {
        stopPolling()
        loadedDurationSeconds = null
        connected = null
        _events.tryEmit(PlaybackTargetEvent.Disconnected)
    }

    /**
     * Prepare the renderer for the engine to set a NEW stream URI (a transcode seek, an adaptive bitrate
     * switch, or a recovery reload). Two ordered steps:
     *  1. Stop the status poll so a transient "finished"/position from the torn-down old stream can't be
     *     mistaken for end-of-media (which would stop the freshly-reloaded stream).
     *  2. Stop the renderer. LG webOS and many renderers reject a `SetAVTransportURI` issued while still
     *     PLAYING (UPnP 701 "Transition not available"), so without a clean Stop first the new URI silently
     *     doesn't take and the old stream keeps playing at the old position — i.e. a transcode seek appears
     *     to do nothing. Best-effort: a Stop on an already-idle renderer is harmless (avAction swallows
     *     failures). The poll restarts in [load].
     */
    override suspend fun prepareReload() {
        stopPolling()
        avAction("Stop", "<InstanceID>0</InstanceID>")
    }

    /**
     * Poll AVTransport `GetPositionInfo` / `GetTransportInfo` so the engine receives position + ended
     * events from DLNA renderers (which, unlike Cast, push no status updates). Best-effort: a renderer
     * that doesn't answer simply yields no updates and adaptation falls back to proxy throughput only.
     */
    private fun startPolling() {
        stopPolling()
        pollJob = pollScope.launch {
            var everPlayed = false
            var furthestPositionSeconds = 0L
            var consecutiveFailures = 0
            // Idea 8: poll fast while actively playing (need timely position + end/error detection), and
            // back off while paused/idle (position isn't moving) to cut SOAP chatter + battery.
            var intervalMs = POLL_INTERVAL_MS
            var lastState: String? = null
            while (isActive) {
                // Wait for the poll interval, but wake early when a local control (seek/play/pause) was
                // issued so the phone reflects it promptly. After an early wake, a short settle lets the
                // renderer apply the action before we read its (now-updated) position.
                val wokenByControl = withTimeoutOrNull(intervalMs) { pollWake.receive() } != null
                if (wokenByControl) delay(POST_ACTION_SETTLE_MS)
                val r = connected ?: break
                val info = runCatching { transportInfo(r) }
                    .onFailure { logger.trace(TAG, "GetTransportInfo failed: ${it.javaClass.simpleName}") }
                    .getOrNull()
                val state = info?.state
                val position = runCatching { positionSeconds(r) }
                    .onFailure { logger.trace(TAG, "GetPositionInfo failed: ${it.javaClass.simpleName}") }
                    .getOrNull()
                if (state == null && position == null) {
                    // The renderer became unreachable (network blip / TV powered off). After a few
                    // consecutive failures treat it as an UNEXPECTED disconnect so the app auto-reconnects
                    // (a single failure can be a transient SOAP timeout, hence the threshold). Guarded by
                    // isActive so a teardown-cancelled poll doesn't emit a stale event.
                    if (++consecutiveFailures >= MAX_POLL_FAILURES) {
                        logger.w("dlna", "DLNA renderer unreachable after $consecutiveFailures polls — treating as a disconnect")
                        if (isActive) _events.tryEmit(PlaybackTargetEvent.Disconnected)
                        break
                    }
                    continue
                }
                consecutiveFailures = 0
                if (state != null && state != lastState) {
                    logger.event("dlna", "DLNA transport state ${lastState ?: "?"} -> $state")
                    lastState = state
                    lastTransportState = state
                }
                intervalMs = if (state == "PLAYING" || state == "TRANSITIONING") POLL_INTERVAL_MS else POLL_IDLE_INTERVAL_MS
                // A direct-play resume/reload asked to start at a position: now that the renderer is
                // actually PLAYING (transport ready + the proxy advertises the entity length so a byte
                // seek is honoured), issue the REL_TIME Seek exactly once.
                if (state == "PLAYING" && pendingResumeSeconds > 0) {
                    val target = pendingResumeSeconds
                    pendingResumeSeconds = 0
                    logger.event("dlna", "DLNA resume: seeking to ${target}s now that playback started")
                    runCatching { seekTo(target) }.onFailure { logger.w("dlna", "DLNA resume seek failed", it) }
                }
                if (position != null) {
                    furthestPositionSeconds = maxOf(furthestPositionSeconds, position)
                    _events.tryEmit(PlaybackTargetEvent.StatusChanged(position, isPlaying = state == "PLAYING"))
                }
                // The renderer couldn't decode the media: UPnP reports CurrentTransportStatus=ERROR_OCCURRED
                // (often while parked in STOPPED, with no push callback of its own). Surface it so the engine
                // can fall back to a server transcode + retry — DLNA renderers vary wildly in codec/container
                // support, so an optimistic direct-play can fail here just as it can on Cast.
                if (info?.status == "ERROR_OCCURRED") {
                    logger.w("dlna", "DLNA transport status=ERROR_OCCURRED — treating as a FORMAT failure")
                    if (isActive) _events.tryEmit(PlaybackTargetEvent.Error(PlaybackFailureKind.FORMAT, "DLNA playback error"))
                    break
                }
                if (state == "PLAYING" || state == "TRANSITIONING") everPlayed = true
                val terminalOutcome = DlnaTerminalStatePolicy.classify(
                    everPlayed = everPlayed,
                    transportState = state,
                    furthestPositionSeconds = furthestPositionSeconds,
                    durationSeconds = loadedDurationSeconds,
                )
                if (terminalOutcome != DlnaTerminalStatePolicy.Outcome.NONE) {
                    // Don't emit end-of-media if this poll has been cancelled (a reload is tearing the
                    // old stream down) — the STOPPED is the teardown, not a real finish. tryEmit isn't a
                    // suspension point, so without this guard a cancelled mid-iteration poll could still
                    // emit a stale Ended that stops the freshly-reloaded stream.
                    if (isActive) {
                        when (terminalOutcome) {
                            DlnaTerminalStatePolicy.Outcome.COMPLETED -> {
                                logger.event("dlna", "DLNA end-of-media near ${loadedDurationSeconds}s")
                                _events.tryEmit(PlaybackTargetEvent.Ended)
                            }
                            DlnaTerminalStatePolicy.Outcome.STOPPED -> {
                                logger.event("dlna", "DLNA renderer stopped at ${furthestPositionSeconds}s")
                                _events.tryEmit(PlaybackTargetEvent.Stopped)
                            }
                            DlnaTerminalStatePolicy.Outcome.NONE -> Unit
                        }
                    }
                    break
                }
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    private fun positionSeconds(r: Renderer): Long? {
        val resp = soap(r.avTransport, "GetPositionInfo", "<InstanceID>0</InstanceID>")
        val rel = TAG_REL_TIME.find(resp)?.groupValues?.get(1)?.trim() ?: return null
        return parseClock(rel)
    }

    private fun transportInfo(r: Renderer): AVTransport.TransportInfo {
        val resp = soap(r.avTransport, "GetTransportInfo", "<InstanceID>0</InstanceID>")
        return AVTransport.parseTransportInfo(resp)
    }

    /** Parse a UPnP clock value "H:MM:SS(.ms)" into whole seconds. */
    private fun parseClock(value: String): Long? {
        val parts = value.substringBefore('.').split(':')
        if (parts.size != 3) return null
        val h = parts[0].toLongOrNull() ?: return null
        val m = parts[1].toLongOrNull() ?: return null
        val s = parts[2].toLongOrNull() ?: return null
        return h * 3600 + m * 60 + s
    }

    private suspend fun avAction(action: String, body: String) = withContext(Dispatchers.IO) {
        val renderer = checkNotNull(connected) { "Not connected to a DLNA renderer" }
        try {
            soap(renderer.avTransport, action, body)
        } catch (e: Exception) {
            logger.w(TAG, "DLNA $action failed", e)
            throw e
        }
        Unit
    }

    private fun soap(endpoint: ControlEndpoint, action: String, innerXml: String): String {
        requireLocalNetworkAccess()
        val controlUrl = endpoint.controlUrl
        val serviceType = endpoint.serviceType
        val envelope =
            """<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" """ +
                """s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body>""" +
                """<u:$action xmlns:u="$serviceType">$innerXml</u:$action></s:Body></s:Envelope>"""
        val request = Request.Builder()
            .url(controlUrl)
            .addHeader("SOAPACTION", "\"$serviceType#$action\"")
            .post(envelope.toRequestBody("text/xml; charset=\"utf-8\"".toMediaType()))
            .build()
        logger.trace(TAG, "SOAP -> $controlUrl $action: ${LogRedactor.redact(innerXml).take(800)}")
        // SOAP stays on the endpoint's selected physical LAN. A failed LAN bind is a routing failure,
        // not permission to disclose the phone proxy URL through the system default/VPN route.
        return executeSoap(endpointPolicy.client(httpClient, endpoint.route, FETCH_TIMEOUT_MS.toLong()), request, action)
    }

    private fun executeSoap(httpClient: OkHttpClient, request: Request, action: String): String {
        httpClient.newCall(request).execute().use { resp ->
            // Read max+1 and reject overflow. peekBody silently truncates, which can turn a malformed
            // SOAP fault into a misleading successful parse.
            val bytes = BoundedBody.readAtMost(resp.body.byteStream(), MAX_SOAP_BYTES + 1)
                ?: throw IOException("Renderer SOAP body exceeds the size limit")
            if (bytes.size > MAX_SOAP_BYTES) {
                throw IOException("Renderer SOAP body exceeds the size limit")
            }
            val text = bytes.toString(Charsets.UTF_8)
            logger.trace(TAG, "SOAP <- $action ${resp.code}: ${LogRedactor.redact(text).take(1200)}")
            if (!resp.isSuccessful) {
                val upnp = TAG_UPNP_ERROR.find(text)?.groupValues?.get(1)?.toIntOrNull()
                throw SoapFaultException(action, resp.code, upnp)
            }
            return text
        }
    }

    /** A SOAP control call rejected by the renderer, carrying the UPnP [upnpErrorCode] (e.g. 701) if present. */
    private class SoapFaultException(action: String, httpCode: Int, val upnpErrorCode: Int?) :
        IllegalStateException("SOAP $action failed: $httpCode${upnpErrorCode?.let { " (UPnP $it)" } ?: ""}")

    /**
     * Fetch a device description through the selected LAN client, bounded by the scan's remaining
     * wall-clock budget. There is deliberately no fallback to the system default route: a VPN/default
     * route must not receive a renderer control request or the phone proxy URL later carried by SOAP.
     */
    private fun fetch(endpoint: RendererEndpointPolicy.Endpoint, remainingMillis: Long): ByteArray? {
        requireLocalNetworkAccess()
        if (remainingMillis <= 0) return null
        return fetchViaOkHttp(endpointPolicy.client(httpClient, endpoint, remainingMillis), endpoint.url, remainingMillis)
    }

    private fun fetchViaOkHttp(c: OkHttpClient, url: String, remainingMillis: Long): ByteArray? = runCatching {
        val call = c.newCall(Request.Builder().url(url).build())
        call.timeout().timeout(remainingMillis, java.util.concurrent.TimeUnit.MILLISECONDS)
        call.execute().use { resp ->
            val bytes = if (resp.isSuccessful) {
                BoundedBody.readAtMost(resp.body.byteStream(), MAX_DESCRIPTION_BYTES + 1)
            } else {
                null
            }
            val body = bytes?.takeIf { it.size <= MAX_DESCRIPTION_BYTES }
            logger.trace(
                TAG,
                "describe fetch via selected LAN: HTTP " + resp.code + ", " +
                    (body?.size ?: 0) + " bytes from " + url,
            )
            body?.takeIf { it.isNotEmpty() }
        }
    }.onFailure {
        logger.trace(TAG, "describe fetch via selected LAN failed for $url: ${it.javaClass.simpleName} ${it.message ?: ""}")
    }.getOrNull()

    companion object {
        private const val TAG = "DlnaTargetController"
        private const val SSDP_ADDR = "239.255.255.250"
        private const val SSDP_PORT = 1900
        private const val SSDP_TTL = 2
        private const val SSDP_PROBES = 3
        private const val SSDP_PROBE_SPACING_MS = 150L
        private const val SSDP_RECEIVE_SLICE_MS = 250L
        private const val MAX_CONCURRENT_DESCRIPTIONS = 4
        private const val NANOS_PER_MILLISECOND = 1_000_000L
        private const val FETCH_TIMEOUT_MS = 5000
        private const val MAX_DESCRIPTION_BYTES = 512 * 1024 // matches SecureXml.MAX_XML_BYTES
        private const val MAX_SOAP_BYTES = 256 * 1024 // cap a renderer's SOAP response (we only parse a few small fields)
        // Poll cadence while PLAYING. Kept fairly tight so the phone tracks the TV closely (and reflects a
        // seek/pause done on the TV's own remote quickly); the position is interpolated locally between
        // polls for a smooth scrubber. The selected-LAN client keeps SOAP traffic off the default route.
        // Full push updates would need GENA SUBSCRIBE/NOTIFY, which renderers implement poorly.
        private const val POLL_INTERVAL_MS = 2000L
        // Slower cadence while paused/idle (position isn't advancing).
        private const val POLL_IDLE_INTERVAL_MS = 8000L
        // After a seek/play/pause wakes the poll early, wait this long before reading so the renderer has
        // applied the action and reports its new position (rather than the pre-action one).
        private const val POST_ACTION_SETTLE_MS = 350L
        // Consecutive failed status polls (renderer unreachable) before declaring an unexpected
        // disconnect. >1 avoids reacting to a single transient SOAP timeout.
        private const val MAX_POLL_FAILURES = 3
        // Starting a new stream while the renderer is still TRANSITIONING from the previous one fails with
        // UPnP 701 "Transition not available"; retry SetAVTransportURI once after a short settle delay so
        // switching videos quickly doesn't drop to a "TV won't play it" error.
        private const val UPNP_TRANSITION_NOT_AVAILABLE = 701
        private const val SET_URI_RETRY_DELAY_MS = 800L
        private val TAG_REL_TIME = Regex("<(?:[\\w-]+:)?RelTime>([^<]*)</", RegexOption.IGNORE_CASE)
        private val TAG_CURRENT_VOLUME = Regex("<(?:[\\w-]+:)?CurrentVolume>\\s*([^<]+?)\\s*</", RegexOption.IGNORE_CASE)
        private val TAG_UPNP_ERROR = Regex("<errorCode>\\s*(\\d+)\\s*</errorCode>", RegexOption.IGNORE_CASE)

        /**
         * Conservative capability baseline for an unknown DLNA renderer: assume common H.264 + AAC/AC3
         * in MP4/MKV/TS and NO HLS, so incompatible sources are transcoded server-side to a
         * **progressive** MPEG-TS H.264/AAC stream (DLNA renderers generally don't speak HLS).
         */
        private val DLNA_BASELINE = TargetCapabilities(
            protocol = Protocol.DLNA,
            supportedContainers = setOf("mp4", "mkv", "ts", "avi", "mov"),
            supportedVideoCodecs = setOf("h264", "mpeg4", "mpeg2video"),
            supportedAudioCodecs = setOf("aac", "ac3", "mp3"),
            supportsHevc = false,
            supports10Bit = false,
            supportsHls = false,
            supportedExternalSubtitleFormats = setOf("srt"),
        )
    }
}
