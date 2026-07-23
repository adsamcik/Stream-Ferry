package com.videobridge.data.dlna

import android.net.Network
import com.videobridge.core.dlna.DidlLite
import com.videobridge.core.dlna.SecureXml
import com.videobridge.core.dlna.SsdpDiscoveryLimiter
import com.videobridge.core.dlna.SsdpParser
import com.videobridge.core.redaction.LogRedactor
import com.videobridge.core.stream.Protocol
import com.videobridge.core.stream.TargetCapabilities
import com.videobridge.diagnostics.NetworkInfoProvider
import com.videobridge.domain.DiscoveredTarget
import com.videobridge.domain.PlaybackFailureKind
import com.videobridge.domain.PlaybackTargetController
import com.videobridge.domain.PlaybackTargetEvent
import com.videobridge.logging.DiagnosticsLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.ConnectionPool
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
) : PlaybackTargetController {

    override val protocol = Protocol.DLNA

    private val _events = MutableSharedFlow<PlaybackTargetEvent>(extraBufferCapacity = 16)
    override val events = _events.asSharedFlow()

    private data class Renderer(val avTransportControlUrl: String, val renderingControlUrl: String?)
    private var connected: Renderer? = null

    // A resume/reload start position (seconds) requested by [load]. DLNA has no "start position" in
    // SetAVTransportURI, so we issue a REL_TIME Seek — but only once the renderer is actually PLAYING (the
    // poll loop applies it), because a Seek before the transport has started is widely rejected/ignored.
    // Cleared after it is applied so we never re-seek a later user scrub.
    @Volatile private var pendingResumeSeconds: Long = 0L
    /** Last AVTransport state seen by the poll loop (PLAYING/TRANSITIONING/STOPPED/…), for diagnostics. */
    @Volatile private var lastTransportState: String? = null

    // Requests to the TV (device-description fetch + SOAP control) must egress over Wi-Fi, NOT the system
    // default network — which is the VPN/mesh tunnel when one is up to reach a remote Jellyfin. Otherwise
    // SSDP discovery receives the TV's reply but the follow-up HTTP fetch of its description (and every
    // SOAP call) is captured by the VPN and fails, so the renderer never appears. Bind a client to the
    // Wi-Fi Network so its sockets reach the LAN directly. (Samsung's native share does the same.)
    @Volatile private var boundClient: OkHttpClient? = null
    @Volatile private var boundNetwork: Network? = null
    // Some networks (a full-tunnel VPN to a remote Jellyfin) reject binding a socket to the Wi-Fi Network
    // with EPERM, so the Wi-Fi-bound client can't connect and only the default route reaches the TV. Once
    // we observe that, stop paying the doomed Wi-Fi attempt on every SSDP bind / describe fetch / SOAP
    // call (the repeated failure is the main cause of laggy DLNA controls AND a flood of EPERM log lines).
    // Re-probed when the Wi-Fi network changes (VPN toggled / Wi-Fi reconnected).
    @Volatile private var wifiBindUsable = true
    /** The Wi-Fi [Network] the [wifiBindUsable] verdict was probed on; a change re-probes. */
    @Volatile private var wifiBindProbedNetwork: Network? = null
    /** Log the "using the default route" transition only once per probe, not on every failed attempt. */
    @Volatile private var wifiBindUnusableLogged = false

    // A renderer is an UNTRUSTED LAN device. Never follow a redirect when talking to it: a hostile
    // renderer that passed the SSDP private-host SSRF gate could 30x the description fetch (or a SOAP
    // call) to a public host, poisoning the control URL and leaking the proxy URL off the LAN. Also cap
    // the total call time so a slowloris renderer can't stall discovery/polling. The default (non-Wi-Fi)
    // client carries the same hardening.
    private val defaultClient: OkHttpClient by lazy { httpClient.hardenedForRenderer() }

    private fun OkHttpClient.hardenedForRenderer(): OkHttpClient = newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .callTimeout(FETCH_TIMEOUT_MS.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
        .build()

    // Synchronized: called concurrently from the 4s poll loop and user-gesture avAction() calls, so the
    // check-then-build-then-store must be atomic or racing callers orphan extra OkHttpClient/pools.
    @Synchronized
    private fun client(): OkHttpClient {
        if (!wifiRouteUsable()) return defaultClient // Wi-Fi bind known to EPERM here; skip straight to default
        val net = network.wifiNetwork() ?: return defaultClient
        boundClient?.let { if (net == boundNetwork) return it }
        return httpClient.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .callTimeout(FETCH_TIMEOUT_MS.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
            .socketFactory(net.socketFactory)
            .connectionPool(ConnectionPool())
            .build()
            .also { boundClient = it; boundNetwork = net }
    }

    /**
     * Whether binding sockets to the Wi-Fi [Network] is usable here. Under a full-tunnel VPN the bind
     * EPERMs, so we skip the doomed Wi-Fi attempts and use the default route (which still reaches the LAN
     * for this user). Re-probes (resets to usable) when the Wi-Fi network changes.
     */
    private fun wifiRouteUsable(): Boolean {
        val net = network.wifiNetwork() ?: return false
        if (net != wifiBindProbedNetwork) {
            wifiBindProbedNetwork = net
            wifiBindUsable = true
            wifiBindUnusableLogged = false
        }
        return wifiBindUsable
    }

    /** Remember that the Wi-Fi-Network socket bind isn't permitted here (VPN); log the transition once. */
    private fun markWifiBindUnusable(where: String) {
        wifiBindUsable = false
        if (!wifiBindUnusableLogged) {
            wifiBindUnusableLogged = true
            logger.event("dlna", "Wi-Fi socket bind not permitted ($where) — using the default route to reach the TV")
        }
    }

    /** True when [t]'s chain is a Wi-Fi-Network bind rejection (EPERM under a VPN), not a generic failure. */
    private fun isWifiBindDenied(t: Throwable): Boolean =
        generateSequence(t as Throwable?) { it.cause }.any {
            val m = it.message ?: ""
            m.contains("EPERM") || m.contains("Operation not permitted") || m.contains("Binding socket to network")
        }

    private val pollScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null
    // Signalled after a local control (seek/play/pause) so the status poll wakes early instead of waiting a
    // full interval — the phone then reflects the change within ~a SOAP round-trip. CONFLATED: a burst of
    // controls collapses to a single wake.
    private val pollWake = Channel<Unit>(Channel.CONFLATED)

    override suspend fun discover(timeoutMillis: Long): List<DiscoveredTarget> = withContext(Dispatchers.IO) {
        val lock = network.multicastLock("dlna-discovery")?.also { it.setReferenceCounted(false); it.acquire() }
        try {
            ssdpSearch(timeoutMillis)
        } catch (e: Exception) {
            logger.w(TAG, "SSDP discovery failed (multicast may be blocked)", e)
            emptyList()
        } finally {
            runCatching { if (lock?.isHeld == true) lock.release() }
        }
    }

    private fun ssdpSearch(timeoutMillis: Long): List<DiscoveredTarget> {
        val results = LinkedHashMap<String, DiscoveredTarget>()
        val limiter = SsdpDiscoveryLimiter()
        val socket = MulticastSocket()
        socket.soTimeout = timeoutMillis.toInt().coerceAtMost(SSDP_READ_TIMEOUT_MS)
        // Send the M-SEARCH out the Wi-Fi/Ethernet LAN interface (where the TV lives), not the system
        // default route — which is the VPN/mesh tunnel when one is up to reach a remote Jellyfin. On the
        // default route the query never reaches a TV on the real LAN, so DLNA renderers (e.g. an LG
        // webOS TV) are silently undiscoverable even though the proxy itself binds to Wi-Fi.
        bindSsdpToLan(socket)
        socket.use { s ->
            val msearch = (
                "M-SEARCH * HTTP/1.1\r\n" +
                    "HOST: $SSDP_ADDR:$SSDP_PORT\r\n" +
                    "MAN: \"ssdp:discover\"\r\n" +
                    "MX: 2\r\n" +
                    "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n"
                ).toByteArray()
            val group = InetAddress.getByName(SSDP_ADDR)
            // UDP is lossy and some TVs miss the first probe; send a few bursts.
            val datagram = DatagramPacket(msearch, msearch.size, InetSocketAddress(group, SSDP_PORT))
            repeat(SSDP_PROBES) { runCatching { s.send(datagram) } }
            logger.trace(TAG, "SSDP M-SEARCH sent ($SSDP_PROBES) on ${runCatching { s.networkInterface?.name }.getOrNull() ?: "default route"}")
            // Record the network context so a failed describe can be attributed: if no Wi-Fi Network is
            // found while a VPN is up, the device-description HTTP fetch egresses the tunnel and fails.
            logger.trace(
                TAG,
                "DLNA discovery context: lanIp=${network.lanIpv4() ?: "none"} vpnActive=${network.isVpnActive()} " +
                    "wifiNetwork=${if (network.wifiNetwork() != null) "available" else "NONE"}",
            )

            val deadline = System.currentTimeMillis() + timeoutMillis
            val buf = ByteArray(2048)
            val tracedUsns = HashSet<String>() // trace each renderer's SSDP reply once, not every repeat probe
            while (System.currentTimeMillis() < deadline) {
                val packet = DatagramPacket(buf, buf.size)
                try {
                    s.receive(packet)
                } catch (e: Exception) {
                    break // timeout
                }
                val msg = SsdpParser.parse(String(packet.data, 0, packet.length, Charsets.UTF_8)) ?: continue
                // UDP + multiple probes means a renderer replies several times; trace only the first reply
                // per USN so a scan doesn't spam the log with identical lines.
                if (tracedUsns.add(msg.usn ?: msg.location ?: "?")) {
                    logger.trace(TAG, "SSDP <- mediaRenderer=${msg.isMediaRenderer()} usn=${msg.usn} loc=${msg.location}")
                }
                if (!msg.isMediaRenderer() || !SsdpParser.isAcceptableLocation(msg.location)) continue
                val location = msg.location ?: continue
                if (results.containsKey(msg.usn ?: location)) continue // already described (repeat probe)
                // Flood guard (§6/§16): bound how many device-description fetches one scan will do, so a
                // hostile device spamming distinct USNs can't trigger unbounded describe() HTTP fetches.
                val sourceIp = packet.address?.hostAddress ?: "unknown"
                if (!limiter.allowDescribe(sourceIp)) {
                    logger.trace(TAG, "SSDP describe rate-limited (flood guard)")
                    continue
                }
                runCatching {
                    val target = describe(location, msg.usn ?: location)
                    if (target != null) results[target.id] = target
                }
            }
        }
        logger.trace(TAG, "SSDP discovery finished: ${results.size} renderer(s)")
        logger.event("discovery", "DLNA scan: ${results.size} renderer(s)")
        return results.values.toList()
    }

    /** Pin outgoing SSDP multicast to the TV-reachable LAN interface (see [ssdpSearch]). Best-effort. */
    private fun bindSsdpToLan(socket: MulticastSocket) {
        // Bind the socket to the Wi-Fi Network first: when a VPN is the default network, IP_MULTICAST_IF
        // alone can be overridden by the tunnel, so the M-SEARCH would egress the VPN and never reach the
        // LAN. Network.bindSocket pins the datagram socket to Wi-Fi regardless. Best-effort + additive.
        // Skip it once we know the bind EPERMs here (VPN) — the IP_MULTICAST_IF pin below still works and
        // we avoid a failed bind + log line on every scan.
        if (wifiRouteUsable()) {
            runCatching { network.wifiNetwork()?.bindSocket(socket) }
                .onFailure {
                    if (isWifiBindDenied(it)) markWifiBindUnusable("SSDP")
                    else logger.trace(TAG, "Couldn't bind SSDP socket to the Wi-Fi Network: ${it.javaClass.simpleName}")
                }
        }
        val lanIp = network.lanIpv4() ?: return
        runCatching {
            val nic = NetworkInterface.getByInetAddress(InetAddress.getByName(lanIp))
            if (nic != null) socket.networkInterface = nic
        }.onFailure { logger.w(TAG, "Couldn't pin SSDP to the LAN interface; using default route", it) }
    }

    private fun describe(location: String, usn: String): DiscoveredTarget? {
        val xml = fetch(location)
        if (xml == null) {
            logger.w("dlna", "Device description fetch failed on all transports (renderer dropped)")
            return null
        }
        val doc = runCatching { SecureXml.parse(xml) }.getOrElse {
            logger.w("dlna", "Device description XML parse failed: ${it.javaClass.simpleName}")
            return null
        }
        // Confirm MediaRenderer + locate the AVTransport control URL (resolved against base/location).
        val friendly = doc.getElementsByTagName("friendlyName").item(0)?.textContent?.take(64) ?: "DLNA Renderer"
        val controlUrl = AVTransport.findControlUrl(doc, location)
        if (controlUrl == null) {
            logger.event("discovery", "Renderer '$friendly' has no AVTransport service — skipped")
            return null
        }
        rendererControlUrls[usn] = controlUrl
        AVTransport.findControlUrl(doc, location, RENDERING_CONTROL)?.let { rendererRenderingUrls[usn] = it }
        logger.trace(TAG, "describe: '$friendly' ready (AVTransport control URL resolved)")
        return DiscoveredTarget(
            id = usn,
            displayName = DidlLite.escape(friendly), // escape for safe UI rendering
            protocol = Protocol.DLNA,
            capabilities = DLNA_BASELINE.copy(modelName = friendly.take(64)),
            lastTestedStatus = null,
        )
    }

    private val rendererControlUrls = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val rendererRenderingUrls = java.util.concurrent.ConcurrentHashMap<String, String>()

    override suspend fun connect(target: DiscoveredTarget) {
        val url = rendererControlUrls[target.id] ?: run {
            logger.w("connect", "DLNA connect failed — no AVTransport URL for '${target.displayName}'")
            error("Unknown renderer")
        }
        logger.event("connect", "DLNA connect -> ${target.displayName}")
        logger.trace(TAG, "DLNA connect -> ${target.displayName} @ $url")
        connected = Renderer(url, rendererRenderingUrls[target.id])
        wifiBindProbedNetwork = null // re-probe the Wi-Fi bind for this session (the network may have changed)
        startPolling()
        _events.tryEmit(PlaybackTargetEvent.Connected)
    }

    override suspend fun load(proxyUrl: String, mimeType: String, title: String, durationSeconds: Long?, startPositionSeconds: Long) =
        withContext(Dispatchers.IO) {
            val r = connected ?: error("Not connected")
            val didl = DidlLite.build(proxyUrl, title, mimeType, durationSecs = durationSeconds)
            // Remember any resume position; the poll loop issues the Seek once the renderer reports PLAYING.
            pendingResumeSeconds = startPositionSeconds.coerceAtLeast(0)
            val setUri = {
                soap(r.avTransportControlUrl, "SetAVTransportURI", buildString {
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
                    logger.e("dlna", "DLNA SetAVTransportURI failed ($mimeType)", e)
                    throw e
                }
                logger.w("dlna", "DLNA SetAVTransportURI transient failure (${e.message}); settling ${SET_URI_RETRY_DELAY_MS}ms and retrying once")
                delay(SET_URI_RETRY_DELAY_MS)
                try {
                    setUri()
                } catch (e2: Exception) {
                    logger.e("dlna", "DLNA SetAVTransportURI failed after retry ($mimeType)", e2)
                    throw e2
                }
            }
            logger.event("dlna", "DLNA SetAVTransportURI sent ($mimeType, proxy URL only)")
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
        val url = connected?.renderingControlUrl ?: return@withContext // RenderingControl not available
        val percent = (level.coerceIn(0f, 1f) * 100).toInt()
        runCatching {
            soap(
                url,
                "SetVolume",
                "<InstanceID>0</InstanceID><Channel>Master</Channel><DesiredVolume>$percent</DesiredVolume>",
                serviceType = RENDERING_CONTROL,
            )
        }.onFailure { logger.w(TAG, "DLNA SetVolume failed", it) }
        Unit
    }

    override suspend fun disconnect() {
        stopPolling()
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
                if (everPlayed && (state == "STOPPED" || state == "NO_MEDIA_PRESENT")) {
                    // Don't emit end-of-media if this poll has been cancelled (a reload is tearing the
                    // old stream down) — the STOPPED is the teardown, not a real finish. tryEmit isn't a
                    // suspension point, so without this guard a cancelled mid-iteration poll could still
                    // emit a stale Ended that stops the freshly-reloaded stream.
                    if (isActive) {
                        logger.event("dlna", "DLNA end-of-media")
                        _events.tryEmit(PlaybackTargetEvent.Ended)
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
        val resp = soap(r.avTransportControlUrl, "GetPositionInfo", "<InstanceID>0</InstanceID>")
        val rel = TAG_REL_TIME.find(resp)?.groupValues?.get(1)?.trim() ?: return null
        return parseClock(rel)
    }

    private fun transportInfo(r: Renderer): AVTransport.TransportInfo {
        val resp = soap(r.avTransportControlUrl, "GetTransportInfo", "<InstanceID>0</InstanceID>")
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
        val r = connected ?: return@withContext
        runCatching { soap(r.avTransportControlUrl, action, body) }
            .onFailure { logger.w(TAG, "DLNA $action failed", it) }
        Unit
    }

    private fun soap(controlUrl: String, action: String, innerXml: String, serviceType: String = AV_TRANSPORT): String {
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
        // SOAP control must reach the TV on the LAN. Under a VPN to a remote Jellyfin the Wi-Fi-`Network`-
        // bound socket can fail to bind (EPERM) even though the TV is reachable on the default route
        // (split-tunnel) — so retry once on the default client, exactly as the description fetch falls back.
        // Only connection/bind failures (IOException) fall back; a real SOAP fault (non-2xx) is not retried.
        val wifiClient = client()
        return try {
            executeSoap(wifiClient, request, action)
        } catch (e: IOException) {
            if (wifiClient !== defaultClient) {
                // The Wi-Fi-bound socket failed (e.g. EPERM under a VPN). Remember it so subsequent SOAP
                // calls (and the 1-2/sec poll) skip the doomed Wi-Fi attempt — that repeated failure is the
                // main cause of laggy DLNA controls — and retry once on the default route now.
                if (isWifiBindDenied(e)) markWifiBindUnusable("SOAP") else wifiBindUsable = false
                logger.trace(TAG, "SOAP $action via wifi client failed (${e.javaClass.simpleName}); using the default route from now on")
                executeSoap(defaultClient, request, action)
            } else {
                throw e
            }
        }
    }

    private fun executeSoap(httpClient: OkHttpClient, request: Request, action: String): String {
        httpClient.newCall(request).execute().use { resp ->
            // Bound the SOAP read (§16): a hostile renderer must not be able to return an unbounded
            // body and OOM us. peekBody caps the in-memory read; we only regex a few small fields out.
            val text = resp.peekBody(MAX_SOAP_BYTES.toLong()).string()
            // Redact BEFORE truncating: a renderer's GetPositionInfo echoes the proxy URL, which could
            // otherwise land past the size cap and escape the logger's redaction.
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
     * Fetch a UPnP device description over the LAN, trying several LAN-reaching mechanisms in order of
     * directness; the first 2xx non-blank body wins. A VPN to a remote Jellyfin is usually the default
     * route, so a Wi-Fi-`Network`-bound request is required to reach the TV:
     *  1. `Network.openConnection` (HttpURLConnection pinned to the Wi-Fi Network) — the most direct bind.
     *  2. OkHttp on a Wi-Fi-`socketFactory` client.
     *  3. OkHttp on the default route (helps split-tunnel VPNs that route RFC1918 directly).
     * Every attempt is traced so a failure is attributable in the diagnostics export.
     */
    private fun fetch(url: String): String? {
        // Try the Wi-Fi-bound transports first (needed to reach the LAN when a VPN is the default route),
        // but skip them entirely once we know the Wi-Fi bind EPERMs here — that avoids two failed attempts
        // + EPERM log lines on every describe. The default route is always tried as the final fallback.
        if (wifiRouteUsable()) {
            network.wifiNetwork()?.let { net -> fetchViaNetwork(net, url)?.let { return it } }
            val wifiClient = client()
            if (wifiClient !== defaultClient) fetchViaOkHttp(wifiClient, "wifi-okhttp", url)?.let { return it }
        }
        return fetchViaOkHttp(defaultClient, "default-okhttp", url)
    }

    /** Fetch [url] over a specific [net] using an HttpURLConnection bound to that network (most direct). */
    private fun fetchViaNetwork(net: Network, url: String): String? = runCatching {
        val conn = (net.openConnection(java.net.URL(url)) as java.net.HttpURLConnection).apply {
            connectTimeout = FETCH_TIMEOUT_MS
            readTimeout = FETCH_TIMEOUT_MS
            requestMethod = "GET"
            // Never follow a redirect off the SSDP-validated private host (SSRF guard).
            instanceFollowRedirects = false
        }
        try {
            val code = conn.responseCode
            // Bound the read: a hostile LAN device could otherwise stream a huge body and OOM us before
            // SecureXml's parse-time cap applies. UPnP descriptions are a few KiB.
            val body = if (code in 200..299) {
                conn.inputStream.use { it.readNBytes(MAX_DESCRIPTION_BYTES).toString(Charsets.UTF_8) }
            } else {
                null
            }
            logger.trace(TAG, "describe fetch via wifi-direct: HTTP $code, ${body?.length ?: 0} bytes from $url")
            body?.takeIf { it.isNotBlank() }
        } finally {
            conn.disconnect()
        }
    }.onFailure {
        if (isWifiBindDenied(it)) markWifiBindUnusable("describe")
        logger.trace(TAG, "describe fetch via wifi-direct failed for $url: ${it.javaClass.simpleName} ${it.message ?: ""}")
    }.getOrNull()

    private fun fetchViaOkHttp(c: OkHttpClient, label: String, url: String): String? = runCatching {
        c.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            // peekBody caps the in-memory read at MAX_DESCRIPTION_BYTES (the client also disables
            // redirects), so a hostile renderer can't OOM us before SecureXml's parse-time cap applies.
            val body = if (resp.isSuccessful) resp.peekBody(MAX_DESCRIPTION_BYTES.toLong()).string() else null
            logger.trace(TAG, "describe fetch via $label: HTTP ${resp.code}, ${body?.length ?: 0} bytes from $url")
            body?.takeIf { it.isNotBlank() }
        }
    }.onFailure {
        if (label.startsWith("wifi") && isWifiBindDenied(it)) markWifiBindUnusable("describe")
        logger.trace(TAG, "describe fetch via $label failed for $url: ${it.javaClass.simpleName} ${it.message ?: ""}")
    }.getOrNull()

    companion object {
        private const val TAG = "DlnaTargetController"
        private const val SSDP_ADDR = "239.255.255.250"
        private const val SSDP_PORT = 1900
        private const val SSDP_READ_TIMEOUT_MS = 4000
        private const val SSDP_PROBES = 3
        private const val FETCH_TIMEOUT_MS = 5000
        private const val MAX_DESCRIPTION_BYTES = 512 * 1024 // matches SecureXml.MAX_XML_BYTES
        private const val MAX_SOAP_BYTES = 256 * 1024 // cap a renderer's SOAP response (we only parse a few small fields)
        private const val AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1"
        private const val RENDERING_CONTROL = "urn:schemas-upnp-org:service:RenderingControl:1"
        // Poll cadence while PLAYING. Kept fairly tight so the phone tracks the TV closely (and reflects a
        // seek/pause done on the TV's own remote quickly); the position is interpolated locally between
        // polls for a smooth scrubber. The Wi-Fi-bind fast-fail cache (wifiRouteUsable) keeps the SOAP
        // overhead low. Full push updates would need GENA SUBSCRIBE/NOTIFY, which renderers implement poorly.
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
