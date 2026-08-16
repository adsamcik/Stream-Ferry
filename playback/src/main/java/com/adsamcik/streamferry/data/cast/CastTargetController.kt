package com.adsamcik.streamferry.data.cast

import android.content.Context
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.CastDevice
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.HlsSegmentFormat as CastHlsSegmentFormat
import com.google.android.gms.cast.HlsVideoSegmentFormat as CastHlsVideoSegmentFormat
import com.google.android.gms.cast.MediaError
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaSeekOptions
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManager
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.gms.common.api.PendingResult
import com.adsamcik.streamferry.core.cast.ConnectOutcome
import com.adsamcik.streamferry.core.cast.ConnectRetryPolicy
import com.adsamcik.streamferry.core.metadata.MetadataSanitizer
import com.adsamcik.streamferry.core.stream.Protocol
import com.adsamcik.streamferry.core.stream.TargetCapabilities
import com.adsamcik.streamferry.domain.DiscoveredTarget
import com.adsamcik.streamferry.domain.TargetDiscoveryMetadata
import com.adsamcik.streamferry.domain.HlsSegmentFormat
import com.adsamcik.streamferry.domain.PlaybackFailureKind
import com.adsamcik.streamferry.domain.PlaybackTargetController
import com.adsamcik.streamferry.domain.PlaybackTargetEvent
import com.adsamcik.streamferry.domain.RendererStream
import com.adsamcik.streamferry.source.api.DiagnosticSink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.milliseconds

/**
 * Cast backend using the direct Google Cast Sender SDK + AndroidX MediaRouter (§10).
 *
 * Discovery enumerates Cast routes via [MediaRouter] (active scan) so the in-app device picker can
 * list them; [connect] selects a route and waits for the [CastSession] to establish. The receiver
 * only ever receives the phone proxy URL + a safe MIME/title — never any Jellyfin URL, token or
 * poster. A [RemoteMediaClient.Callback] surfaces real position / buffering / ended events that drive
 * progress reporting and the adaptive-bitrate controller.
 *
 * All Cast/MediaRouter interaction runs on the main thread (required by the SDK).
 */
class CastTargetController(
    private val appContext: Context,
    private val castContextProvider: () -> CastContext?,
    private val logger: DiagnosticSink,
) : PlaybackTargetController {

    // Resolved fresh on every use: the shared CastContext may only become available after an initial
    // failure (e.g. right after a hard crash), so we must never capture an early null for the whole
    // process lifetime. See AppContainer.castContext.
    private val castContext: CastContext? get() = castContextProvider()

    override val protocol = Protocol.CAST

    private val _events = MutableSharedFlow<PlaybackTargetEvent>(extraBufferCapacity = 32)
    override val events = _events.asSharedFlow()

    private val appId = CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID
    private val selector = MediaRouteSelector.Builder()
        .addControlCategory(CastMediaControlIntent.categoryForCast(appId))
        .build()

    private var routeCallback: MediaRouter.Callback? = null
    /** A picker-close request is deferred until an in-flight route selection has bound a session. */
    private var stopDiscoveryRequested = false
    private var routeSelectionInProgress = false
    /**
     * Callback ownership matters when the user switches targets: unregistering against the *current*
     * media client leaks the callback on the previous client. Keep both ends of every registration.
     */
    private data class MediaCallbackRegistration(
        val client: RemoteMediaClient,
        val callback: RemoteMediaClient.Callback,
        val generation: Long,
    )

    /** Keep the originating manager as well as the listener for the same reason as media callbacks. */
    private data class SessionListenerRegistration(
        val manager: SessionManager,
        val listener: SessionManagerListener<CastSession>,
        val generation: Long,
    )

    /** Stable identity resolved from the requested MediaRouter route before selecting it. */
    private data class RequestedCastRoute(
        val routeId: String,
        val deviceId: String,
    )

    /**
     * One atomic owner for a live Cast connection. Route/device identity proves that the session is for
     * the requested endpoint; the object reference and SDK session ID make late callbacks unambiguous.
     */
    private data class ActiveCastBinding(
        val generation: Long,
        val routeId: String,
        val deviceId: String,
        val session: CastSession,
        val sessionId: String?,
    )

    private data class ActiveMediaClient(
        val session: CastSession,
        val client: RemoteMediaClient,
        val generation: Long,
    )

    private data class MediaCommandOutcome(
        val succeeded: Boolean,
        val statusCode: Int,
    )

    private var mediaCallbackRegistration: MediaCallbackRegistration? = null
    private var sessionListenerRegistration: SessionListenerRegistration? = null
    private val callbackScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var suspensionWatchdogJob: Job? = null
    /** Keeps durable progress current while the Cast SDK is quiet during steady playback. */
    private var positionHeartbeatJob: Job? = null
    /** Incremented before every connect/disconnect so delayed callbacks from a superseded session are inert. */
    private var connectionGeneration = 0L
    private var activeBinding: ActiveCastBinding? = null
    /** Prevent resume-failed followed by ended from emitting two disconnected events for one session. */
    private var terminalDisconnectGeneration: Long? = null
    private var lastBuffering = false
    /** Last Cast player state we logged a transition for, so we emit a high-signal event only on change. */
    private var lastPlayerState: Int? = null

    override suspend fun discover(timeoutMillis: Long): List<DiscoveredTarget> {
        if (castContext == null) return emptyList()
        return withContext(Dispatchers.Main) {
            stopDiscoveryRequested = false
            val router = MediaRouter.getInstance(appContext)
            // Keep the callback registered while the picker remains open: MediaRouter purges discovered
            // routes the instant no callback is active, which can make a just-selected route vanish before
            // connect() selects it. The owner calls [stopDiscovery] when the picker closes.
            ensureRouteCallback(router)
            delay(timeoutMillis.milliseconds)
            val results = router.routes
                .filter { it.matchesSelector(selector) && !it.isDefault }
                .map { route ->
                    // CastDevice identity comes only from documented route extras. A MediaRouter route
                    // ID is ephemeral, so it is never used as a stable physical-device identifier.
                    val device = route.extras?.let { CastDevice.getFromBundle(it) }
                    val deviceId = device?.deviceId?.takeIf { it.isNotBlank() }
                    val modelName = device?.modelName?.takeIf { it.isNotBlank() }
                    DiscoveredTarget(
                        id = route.id,
                        displayName = route.name,
                        protocol = Protocol.CAST,
                        capabilities = CAST_BASELINE.copy(modelName = modelName ?: route.description),
                        lastTestedStatus = null,
                        discoveryMetadata = TargetDiscoveryMetadata(
                            castDeviceId = deviceId,
                            validatedSourceHost = device?.inetAddress?.hostAddress,
                            modelName = modelName,
                            volumeControlAvailable = true,
                        ),
                    )
                }
            logger.event("discovery", "Cast scan: ${results.size} route(s)")
            results
        }
    }

    /**
     * Register one active-scan MediaRouter callback while the picker needs live routes. Routes only exist
     * while a callback is registered; the owner must call [stopDiscovery] on picker exit or permission
     * loss. Android suppresses active scan while the app is in the background.
     */
    private fun ensureRouteCallback(router: MediaRouter) {
        if (routeCallback != null) return
        val cb = object : MediaRouter.Callback() {}
        routeCallback = cb
        router.addCallback(selector, cb, MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN)
    }

    private fun removeRouteCallback() {
        routeCallback?.let { MediaRouter.getInstance(appContext).removeCallback(it) }
        routeCallback = null
    }

    /** Stop active scanning after any in-flight route selection has completed. */
    suspend fun stopDiscovery() = withContext(Dispatchers.Main) {
        stopDiscoveryRequested = true
        if (!routeSelectionInProgress) removeRouteCallback()
    }

    override suspend fun connect(target: DiscoveredTarget) {
        val ctx = castContext ?: run {
            logger.w("connect", "Cast connect failed — Cast is unavailable on this device")
            error("Cast is unavailable on this device.")
        }
        logger.event("connect", "Cast connect -> ${target.displayName}")
        logger.trace(TAG, "Cast connect -> ${target.displayName} (route ${target.id})")
        withContext(Dispatchers.Main) {
            routeSelectionInProgress = true
            try {
                val router = MediaRouter.getInstance(appContext)
                ensureRouteCallback(router)
                var route = router.routes.firstOrNull { it.id == target.id }
                if (route == null) {
                    // A route can still be momentarily absent (just (re)started scanning); poll briefly
                    // before giving up so a transient gap isn't reported as a permanent failure.
                    route = withTimeoutOrNull(ROUTE_RECOVER_MS.milliseconds) {
                        var r: MediaRouter.RouteInfo? = null
                        while (r == null) { delay(250.milliseconds); r = router.routes.firstOrNull { it.id == target.id } }
                        r
                    }
                }
                val selected = route ?: run {
                    logger.w("connect", "Cast route not found after ${ROUTE_RECOVER_MS}ms: '${target.displayName}'")
                    error("That Cast device is no longer available.")
                }
                val requestedRoute = requestedCastRoute(selected)
                val generation = ++connectionGeneration
                // A framework MediaRouteButton can create the requested session before play is tapped. Reuse
                // that exact receiver rather than ending/restarting it; anything else is an old/mismatched
                // session and must be cleared before awaiting a new session for this route.
                val reusableSession = ctx.sessionManager.currentCastSession?.takeIf {
                    it.isConnected && sessionMatchesRequestedRoute(it, requestedRoute)
                }
                val connectedSession = if (reusableSession != null) {
                    logger.event("connect", "Reusing Cast session already selected by the framework")
                    detachActiveSession()
                    reusableSession
                } else {
                    detachActiveSession()
                    clearCurrentSession(ctx, reason = "switching Cast targets")
                    connectWithRetry(ctx, generation, requestedRoute) { router.selectRoute(selected) }
                    if (!isCurrentGeneration(generation)) error("Cast connection was superseded.")
                    ctx.sessionManager.currentCastSession
                        ?.takeIf { it.isConnected && sessionMatchesRequestedRoute(it, requestedRoute) }
                        ?: error("Cast route selected but no connected session was established for the requested receiver.")
                }
                if (!isCurrentGeneration(generation)) error("Cast connection was superseded.")
                bindActiveSession(requestedRoute, connectedSession, generation)
                registerMediaCallback()
                registerSessionListener(ctx, connectedSession, generation)
                logger.event("connect", "Cast session connected -> ${target.displayName}")
                _events.tryEmit(PlaybackTargetEvent.Connected)
                // Session binding is complete; active discovery is no longer needed.
                removeRouteCallback()
            } finally {
                routeSelectionInProgress = false
                if (stopDiscoveryRequested) removeRouteCallback()
            }
        }
    }

    override suspend fun load(
        proxyUrl: String,
        stream: RendererStream,
        title: String,
        durationSeconds: Long?,
        startPositionSeconds: Long,
        playWhenReady: Boolean,
    ) = withContext(Dispatchers.Main) {
        val activeClient = requireActiveMediaClient()
        registerMediaCallback()
        cancelPositionHeartbeat()
        lastPlayerState = null // a fresh load: log the new session's first state transition from "none"
        val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
            putString(MediaMetadata.KEY_TITLE, MetadataSanitizer.receiverTitle(title))
        }
        val mediaInfoBuilder = MediaInfo.Builder(proxyUrl) // phone proxy URL ONLY
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(stream.mimeType)
            .setMetadata(metadata)
        durationSeconds?.let { mediaInfoBuilder.setStreamDuration(it * 1000L) }
        // Cast defaults HLS media to MPEG-TS unless told otherwise. The phone's on-device
        // transcode is CMAF/fMP4, and Jellyfin can also select fMP4 for HLS profiles, so both
        // audio and video segment format must be declared explicitly.
        when (stream.hlsSegmentFormat) {
            HlsSegmentFormat.FMP4 -> {
                mediaInfoBuilder.setHlsSegmentFormat(CastHlsSegmentFormat.FMP4)
                mediaInfoBuilder.setHlsVideoSegmentFormat(CastHlsVideoSegmentFormat.FMP4)
            }
            HlsSegmentFormat.MPEG2_TS -> {
                mediaInfoBuilder.setHlsSegmentFormat(CastHlsSegmentFormat.TS_AAC)
                mediaInfoBuilder.setHlsVideoSegmentFormat(CastHlsVideoSegmentFormat.MPEG2_TS)
            }
            null -> Unit
        }
        val mediaInfo = mediaInfoBuilder.build()
        // Start AT the resume position via the load request itself (setCurrentTime), NOT a post-load
        // seek(): load() completes asynchronously, so a seek issued right after it races the load and is
        // dropped by the receiver, which then autoplays from 0 (the "TV plays from the start" bug).
        val request = MediaLoadRequestData.Builder()
            .setMediaInfo(mediaInfo)
            .setAutoplay(playWhenReady)
            .setCurrentTime(startPositionSeconds.coerceAtLeast(0) * 1000L)
            .build()
        logger.trace(
            TAG,
            "Cast load: type=${stream.mimeType} hlsFormat=${stream.hlsSegmentFormat} " +
                "title=$title dur=${durationSeconds}s start=${startPositionSeconds}s autoplay=$playWhenReady url=$proxyUrl",
        )
        awaitMediaCommand("load", activeClient.client.load(request), activeClient)
        startPositionHeartbeat(activeClient)
        logger.i(TAG, "Cast load accepted (proxy URL, redacted)")
    }

    private suspend fun awaitMediaCommand(
        command: String,
        pending: PendingResult<RemoteMediaClient.MediaChannelResult>,
        activeClient: ActiveMediaClient,
    ) {
        val outcome = withTimeoutOrNull(LOAD_TIMEOUT_MS.milliseconds) {
            suspendCancellableCoroutine<MediaCommandOutcome> { cont ->
                pending.setResultCallback { result ->
                    if (cont.isActive) {
                        cont.resume(MediaCommandOutcome(result.status.isSuccess, result.status.statusCode))
                    }
                }
                cont.invokeOnCancellation { runCatching { pending.cancel() } }
            }
        } ?: run {
            logger.w(TAG, "Cast $command did not complete within ${LOAD_TIMEOUT_MS}ms")
            error("Cast $command timed out.")
        }
        if (!outcome.succeeded) {
            logger.w(TAG, "Cast $command failed (statusCode=${outcome.statusCode})")
            error("Cast $command failed (status ${outcome.statusCode}).")
        }
        if (!isActiveMediaClient(activeClient)) {
            logger.w(TAG, "Cast session changed while $command was in progress")
            error("Cast session changed while $command was in progress.")
        }
    }

    override suspend fun play() = runMediaCommand("play") { it.play() }
    override suspend fun pause() = runMediaCommand("pause") { it.pause() }
    override suspend fun seekTo(positionSeconds: Long) = runMediaCommand("seek") { client ->
        logger.trace(TAG, "Cast seek -> ${positionSeconds}s")
        client.seek(MediaSeekOptions.Builder().setPosition(positionSeconds.coerceAtLeast(0) * 1000L).build())
    }
    override suspend fun stop() = runMediaCommand("stop") { it.stop() }
    override suspend fun readCurrentVolume(): Float? = withContext(Dispatchers.Main) {
        requireActiveMediaClient().session.volume.toFloat().takeIf { level ->
            !level.isNaN() && !level.isInfinite() && level in 0f..1f
        }
    }
    override suspend fun setVolume(level: Float) = withContext(Dispatchers.Main) {
        val activeClient = requireActiveMediaClient()
        activeClient.session.volume = level.coerceIn(0f, 1f).toDouble()
        if (!isActiveMediaClient(activeClient)) error("Cast session changed while setting volume.")
    }

    private suspend fun runMediaCommand(
        command: String,
        action: (RemoteMediaClient) -> PendingResult<RemoteMediaClient.MediaChannelResult>,
    ) = withContext(Dispatchers.Main) {
        val activeClient = requireActiveMediaClient()
        awaitMediaCommand(command, action(activeClient.client), activeClient)
    }

    override suspend fun disconnect() = withContext(Dispatchers.Main) {
        ++connectionGeneration
        // Remove callbacks/listeners before our intentional end so it cannot look like a terminal drop.
        detachActiveSession()
        removeRouteCallback()
        castContext?.sessionManager?.endCurrentSession(true)
        _events.tryEmit(PlaybackTargetEvent.Disconnected)
        Unit
    }

    /**
     * Persistent session listener for the LIVE session: surfaces an UNEXPECTED drop (network loss /
     * receiver gone) as [PlaybackTargetEvent.Disconnected] so the app can auto-reconnect + resume. It is
     * registered after [connect] and removed in [disconnect] before our own intentional end, so a
     * deliberate stop never looks like a drop. (The temporary listener in [awaitSessionStart] removes
     * itself on session start, so it can't serve this role.)
     */
    private fun registerSessionListener(ctx: CastContext, expectedSession: CastSession, generation: Long) {
        val manager = ctx.sessionManager
        val existing = sessionListenerRegistration
        if (existing != null && existing.manager === manager && existing.generation == generation) return
        removeSessionListener()
        val listener = object : SessionManagerListener<CastSession> {
            private fun isExpectedActiveSession(s: CastSession): Boolean =
                s === expectedSession && isTrackedActiveSession(s, generation)

            override fun onSessionSuspended(s: CastSession, reason: Int) {
                if (!isExpectedActiveSession(s)) return
                logger.event(
                    "playback",
                    "Cast session suspended (reason $reason); allowing SDK recovery for ${SUSPENSION_GRACE_MS}ms",
                )
                armSuspensionWatchdog(s, generation)
            }
            override fun onSessionResumed(s: CastSession, wasSuspended: Boolean) {
                if (!isExpectedActiveSession(s)) return
                cancelSuspensionWatchdog()
                logger.event("playback", "Cast session resumed (wasSuspended=$wasSuspended) — recovered")
            }
            override fun onSessionEnded(s: CastSession, error: Int) {
                // Our intentional end removes this listener first. Any matching callback here is terminal,
                // including error=0 receiver/session termination.
                emitTerminalDisconnect(s, generation, "ended (error $error)")
            }
            override fun onSessionStarting(s: CastSession) {}
            override fun onSessionStarted(s: CastSession, id: String) {}
            override fun onSessionStartFailed(s: CastSession, error: Int) {
                logger.w("cast", "Cast session start failed (error $error)")
            }
            override fun onSessionEnding(s: CastSession) {}
            override fun onSessionResuming(s: CastSession, id: String) {}
            override fun onSessionResumeFailed(s: CastSession, error: Int) {
                emitTerminalDisconnect(s, generation, "resume failed (error $error)")
            }
        }
        sessionListenerRegistration = SessionListenerRegistration(manager, listener, generation)
        manager.addSessionManagerListener(listener, CastSession::class.java)
    }

    private fun removeSessionListener() {
        cancelSuspensionWatchdog()
        val registration = sessionListenerRegistration ?: return
        registration.manager.removeSessionManagerListener(registration.listener, CastSession::class.java)
        sessionListenerRegistration = null
    }

    /**
     * A brief Wi-Fi interruption should remain the Cast SDK's responsibility, but some TV reboots leave
     * a session suspended forever without an ended/resume-failed callback. Convert only a prolonged,
     * still-disconnected suspension into the normal terminal-disconnect event so app recovery can
     * rediscover the restarted receiver and resume playback.
     */
    private fun armSuspensionWatchdog(castSession: CastSession, generation: Long) {
        if (suspensionWatchdogJob?.isActive == true) return
        cancelSuspensionWatchdog()
        suspensionWatchdogJob = callbackScope.launch {
            delay(SUSPENSION_GRACE_MS)
            if (!isTrackedActiveSession(castSession, generation)) return@launch
            if (castSession.isConnected) {
                logger.event("playback", "Cast session recovered before the suspension watchdog fired")
                return@launch
            }
            emitTerminalDisconnect(castSession, generation, "remained suspended for ${SUSPENSION_GRACE_MS}ms")
        }
    }

    private fun cancelSuspensionWatchdog() {
        suspensionWatchdogJob?.cancel()
        suspensionWatchdogJob = null
    }

    /**
     * Cast status callbacks are transition-driven, not a reliable telemetry clock. While media is loaded,
     * sample the SDK's locally-maintained approximate position on a short, bounded cadence so a sudden
     * process death loses only a few seconds of progress rather than every quiet minute since the last
     * receiver callback. No receiver request is issued here.
     */
    private fun startPositionHeartbeat(activeClient: ActiveMediaClient) {
        cancelPositionHeartbeat()
        positionHeartbeatJob = callbackScope.launch {
            while (isActive && isCurrentMediaCallback(activeClient.client, activeClient.generation)) {
                delay(POSITION_HEARTBEAT_INTERVAL_MS)
                if (isCurrentMediaCallback(activeClient.client, activeClient.generation)) {
                    emitStatus(activeClient.client, activeClient.generation, isHeartbeat = true)
                    // Paused/buffering state is checkpointed once above. A later PLAYING callback restarts
                    // this loop, so an idle TV does not keep writing unchanged progress to disk.
                    if (activeClient.client.mediaStatus?.playerState != MediaStatus.PLAYER_STATE_PLAYING) return@launch
                }
            }
        }
    }

    private fun cancelPositionHeartbeat() {
        positionHeartbeatJob?.cancel()
        positionHeartbeatJob = null
    }

    /**
     * Start a Cast session, retrying a transient failure automatically ([ConnectRetryPolicy]) so a first-try
     * hiccup doesn't force the user to tap again. Between attempts it drops any half-open session and settles
     * briefly, then re-selects the route to trigger a fresh start. Throws only after retries are exhausted.
     */
    private suspend fun connectWithRetry(
        ctx: CastContext,
        generation: Long,
        requestedRoute: RequestedCastRoute,
        select: () -> Unit,
    ) {
        var attemptsMade = 0
        var timeoutsSeen = 0
        while (true) {
            if (!isCurrentGeneration(generation)) error("Cast connection was superseded.")
            if (attemptsMade > 0) {
                // A fresh attempt clears a transient start failure: drop any half-open session, let the SDK
                // settle, then re-select the route (below, inside awaitSessionStart) to start anew.
                clearCurrentSession(ctx, reason = "retrying Cast connection")
                delay(CONNECT_RETRY_DELAY_MS.milliseconds)
            }
            val outcome = awaitSessionStart(ctx, generation, requestedRoute, select)
            attemptsMade++
            if (outcome == ConnectOutcome.TIMED_OUT) timeoutsSeen++
            if (outcome == ConnectOutcome.STARTED) return
            if (!ConnectRetryPolicy.shouldRetry(outcome, attemptsMade, timeoutsSeen)) break
            logger.event("connect", "Cast connect ${outcome.name.lowercase()} (attempt $attemptsMade); retrying")
        }
        logger.w("cast", "Cast connect failed after $attemptsMade attempt(s)")
        error("Couldn't connect to the Cast device.")
    }

    /**
     * Make ONE Cast session-start attempt: register a temporary listener, trigger the start via [select]
     * (route selection), and await the result up to [CONNECT_TIMEOUT_MS]. Returns the [ConnectOutcome] —
     * never throws — so [connectWithRetry] owns the retry/failure decision.
     */
    private suspend fun awaitSessionStart(
        ctx: CastContext,
        generation: Long,
        requestedRoute: RequestedCastRoute,
        select: () -> Unit,
    ): ConnectOutcome {
        val started = withTimeoutOrNull(CONNECT_TIMEOUT_MS.milliseconds) {
            suspendCancellableCoroutine<Boolean> { cont ->
                val manager = ctx.sessionManager
                val listener = object : SessionManagerListener<CastSession> {
                    private fun isRequestedSession(s: CastSession): Boolean =
                        isCurrentGeneration(generation) && sessionMatchesRequestedRoute(s, requestedRoute)

                    private fun completeForCurrentRequestedSession(s: CastSession) {
                        if (!isCurrentGeneration(generation) || manager.currentCastSession !== s) return
                        if (sessionMatchesRequestedRoute(s, requestedRoute)) {
                            complete(true)
                        } else {
                            logger.w("connect", "Cast session started for a different receiver; retrying requested route")
                            complete(false)
                        }
                    }

                    private var completed = false
                    private fun complete(outcome: Boolean) {
                        if (completed) return
                        completed = true
                        manager.removeSessionManagerListener(this, CastSession::class.java)
                        if (cont.isActive) cont.resume(outcome)
                    }
                    override fun onSessionStarted(s: CastSession, id: String) {
                        completeForCurrentRequestedSession(s)
                    }
                    override fun onSessionResumed(s: CastSession, wasSuspended: Boolean) {
                        completeForCurrentRequestedSession(s)
                    }
                    override fun onSessionStartFailed(s: CastSession, error: Int) {
                        if (isRequestedSession(s)) complete(false)
                    }
                    override fun onSessionEnded(s: CastSession, error: Int) {
                        if (isRequestedSession(s)) complete(false)
                    }
                    override fun onSessionEnding(s: CastSession) {}
                    override fun onSessionResuming(s: CastSession, id: String) {}
                    override fun onSessionResumeFailed(s: CastSession, error: Int) {
                        if (isRequestedSession(s)) complete(false)
                    }
                    override fun onSessionStarting(s: CastSession) {}
                    override fun onSessionSuspended(s: CastSession, reason: Int) {}
                }
                manager.addSessionManagerListener(listener, CastSession::class.java)
                cont.invokeOnCancellation { manager.removeSessionManagerListener(listener, CastSession::class.java) }
                if (isCurrentGeneration(generation)) {
                    runCatching { select() }
                        .onFailure {
                            logger.w("cast", "Cast route selection failed: ${it.message ?: it.javaClass.simpleName}")
                            manager.removeSessionManagerListener(listener, CastSession::class.java)
                            if (cont.isActive) cont.resume(false)
                        }
                } else if (cont.isActive) {
                    cont.resume(false)
                }
            }
        }
        return when {
            started == true && isCurrentGeneration(generation) &&
                ctx.sessionManager.currentCastSession?.let {
                    it.isConnected && sessionMatchesRequestedRoute(it, requestedRoute)
                } == true -> ConnectOutcome.STARTED
            started == false -> ConnectOutcome.FAILED
            else -> ConnectOutcome.TIMED_OUT
        }
    }

    private fun registerMediaCallback() {
        val activeClient = activeMediaClientOrNull() ?: return
        val current = mediaCallbackRegistration
        if (current?.client === activeClient.client && current.generation == activeClient.generation) return
        unregisterMediaCallback()
        val callback = object : RemoteMediaClient.Callback() {
            override fun onStatusUpdated() = emitStatus(activeClient.client, activeClient.generation)
            override fun onMediaError(e: com.google.android.gms.cast.MediaError) {
                if (!isCurrentMediaCallback(activeClient.client, activeClient.generation)) return
                // Always kept (not just under tracing): a Cast media error is a top diagnostic signal.
                val kind = classifyCastError(e.detailedErrorCode)
                logger.w("cast", "Cast media error: reason=${e.reason} detailedCode=${e.detailedErrorCode} -> classified $kind")
                _events.tryEmit(
                    PlaybackTargetEvent.Error(
                        kind,
                        "Cast playback error",
                        qualifiedFormatEvidence = hasQualifiedFormatEvidence(e.detailedErrorCode),
                    ),
                )
            }
        }
        mediaCallbackRegistration = MediaCallbackRegistration(activeClient.client, callback, activeClient.generation)
        activeClient.client.registerCallback(callback)
    }

    private fun unregisterMediaCallback() {
        val registration = mediaCallbackRegistration ?: return
        // Unregister on the exact client that received the registration. The current session may already
        // point at a replacement client by the time switch/disconnect cleanup runs.
        mediaCallbackRegistration = null
        runCatching { registration.client.unregisterCallback(registration.callback) }
            .onFailure { logger.w(TAG, "Couldn't unregister Cast media callback: ${it.message ?: it.javaClass.simpleName}") }
    }

    private fun emitStatus(client: RemoteMediaClient, generation: Long, isHeartbeat: Boolean = false) {
        if (!isCurrentMediaCallback(client, generation)) return
        val status = client.mediaStatus
        val positionSeconds = client.approximateStreamPosition / 1000L
        val state = status?.playerState
        // High-signal, always-on transition event (mirrors DLNA's) so a shared report shows the receiver's
        // state machine — e.g. a stall reads as "... -> LOADING" that never reaches PLAYING, a decode
        // rejection as "LOADING -> IDLE (ERROR)". The per-callback line below stays on the trace channel.
        if (state != null && state != lastPlayerState) {
            val detail = if (state == MediaStatus.PLAYER_STATE_IDLE) " (${idleReasonName(status.idleReason)})" else ""
            logger.event("cast", "Cast player state ${playerStateName(lastPlayerState)} -> ${playerStateName(state)}$detail at ${positionSeconds}s")
            lastPlayerState = state
        }
        if (!isHeartbeat) {
            logger.trace(TAG, "Cast status: state=${playerStateName(state)} idle=${idleReasonName(status?.idleReason)} pos=${positionSeconds}s")
            val activeClient = activeMediaClientOrNull()
            if (state == MediaStatus.PLAYER_STATE_PLAYING &&
                activeClient?.client === client && activeClient.generation == generation
            ) {
                startPositionHeartbeat(activeClient)
            } else if (state != MediaStatus.PLAYER_STATE_PLAYING) {
                cancelPositionHeartbeat()
            }
        }
        when (status?.playerState) {
            MediaStatus.PLAYER_STATE_BUFFERING -> {
                if (!lastBuffering) { lastBuffering = true; _events.tryEmit(PlaybackTargetEvent.BufferingChanged(true)) }
                _events.tryEmit(PlaybackTargetEvent.StatusChanged(positionSeconds, isPlaying = false))
            }
            MediaStatus.PLAYER_STATE_PLAYING -> {
                if (lastBuffering) { lastBuffering = false; _events.tryEmit(PlaybackTargetEvent.BufferingChanged(false)) }
                _events.tryEmit(PlaybackTargetEvent.StatusChanged(positionSeconds, isPlaying = true))
            }
            MediaStatus.PLAYER_STATE_PAUSED -> {
                if (lastBuffering) { lastBuffering = false; _events.tryEmit(PlaybackTargetEvent.BufferingChanged(false)) }
                _events.tryEmit(PlaybackTargetEvent.StatusChanged(positionSeconds, isPlaying = false))
            }
            MediaStatus.PLAYER_STATE_IDLE ->
                when (status.idleReason) {
                    MediaStatus.IDLE_REASON_FINISHED -> _events.tryEmit(PlaybackTargetEvent.Ended)
                    // There is no detailed error code in an IDLE/ERROR status. It could be an unsupported
                    // source, but it could just as easily be a proxy/network/receiver failure; only an
                    // explicit SDK decode/source-not-supported error is qualified format evidence.
                    MediaStatus.IDLE_REASON_ERROR -> {
                        logger.w("cast", "Cast parked IDLE with error reason — classifying as UNKNOWN without detailed SDK evidence")
                        _events.tryEmit(PlaybackTargetEvent.Error(PlaybackFailureKind.UNKNOWN, "Cast playback error"))
                    }
                    // NONE / CANCELED / INTERRUPTED are our own stop/reload transitions — ignore them.
                    else -> Unit
                }
            else -> Unit
        }
    }

    override suspend fun diagnosticStatus(): String = withContext(Dispatchers.Main) {
        val activeClient = activeMediaClientOrNull() ?: return@withContext "no active Cast session"
        val refreshState = try {
            awaitMediaCommand("status refresh", activeClient.client.requestStatus(), activeClient)
            "ok"
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalStateException) {
            logger.w(TAG, "Cast diagnostic status refresh failed: ${e.message ?: e.javaClass.simpleName}")
            "failed"
        }
        if (!isActiveMediaClient(activeClient)) return@withContext "no active Cast session"
        val client = activeClient.client
        val status = client.mediaStatus
        val info = status?.mediaInfo
        buildString {
            append("cast state=").append(playerStateName(status?.playerState))
            append(" idle=").append(idleReasonName(status?.idleReason))
            append(" pos=").append(client.approximateStreamPosition / 1000L).append('s')
            append(" refresh=").append(refreshState)
            if (info != null) {
                append(" contentType=").append(info.contentType)
                append(" streamType=").append(info.streamType)
            } else {
                append(" mediaInfo=none")
            }
        }
    }

    /**
     * Classify a Cast [MediaError.getDetailedErrorCode] so recovery can be precise: a decode / unsupported
     * source is a FORMAT failure (transcode fallback), a media-network error is NETWORK (retry same), and
     * anything else is UNKNOWN (treated optimistically like FORMAT for an online direct-play).
     */
    private fun classifyCastError(detailedErrorCode: Int?): PlaybackFailureKind = when (detailedErrorCode) {
        MediaError.DetailedErrorCode.MEDIA_DECODE,
        MediaError.DetailedErrorCode.MEDIA_SRC_NOT_SUPPORTED,
        -> PlaybackFailureKind.FORMAT
        MediaError.DetailedErrorCode.MEDIA_NETWORK -> PlaybackFailureKind.NETWORK
        else -> PlaybackFailureKind.UNKNOWN
    }

    private fun hasQualifiedFormatEvidence(detailedErrorCode: Int?): Boolean = when (detailedErrorCode) {
        MediaError.DetailedErrorCode.MEDIA_DECODE,
        MediaError.DetailedErrorCode.MEDIA_SRC_NOT_SUPPORTED,
        -> true
        else -> false
    }

    private fun isCurrentGeneration(generation: Long): Boolean = connectionGeneration == generation

    private fun requestedCastRoute(route: MediaRouter.RouteInfo): RequestedCastRoute {
        val deviceId = route.extras
            ?.let { CastDevice.getFromBundle(it) }
            ?.deviceId
            ?.takeIf { it.isNotBlank() }
            ?: run {
                logger.w("connect", "Cast route '${route.name}' has no usable CastDevice identity")
                error("That Cast route could not be identified safely.")
            }
        return RequestedCastRoute(route.id, deviceId)
    }

    /** A CastSession has no MediaRouter route ID, so device ID is the route-to-session join key. */
    private fun sessionMatchesRequestedRoute(session: CastSession, requestedRoute: RequestedCastRoute): Boolean =
        runCatching { session.castDevice?.deviceId == requestedRoute.deviceId }.getOrDefault(false)

    private fun bindActiveSession(
        requestedRoute: RequestedCastRoute,
        session: CastSession,
        generation: Long,
    ) {
        if (!isCurrentGeneration(generation) || !session.isConnected || !sessionMatchesRequestedRoute(session, requestedRoute)) {
            error("Cast session no longer matches the requested receiver.")
        }
        activeBinding = ActiveCastBinding(
            generation = generation,
            routeId = requestedRoute.routeId,
            deviceId = requestedRoute.deviceId,
            session = session,
            sessionId = session.sessionId,
        )
        terminalDisconnectGeneration = null
    }

    /** A session can be terminal before SessionManager clears currentCastSession, so do not consult it here. */
    private fun isTrackedActiveSession(castSession: CastSession, generation: Long): Boolean {
        val binding = activeBinding ?: return false
        return isCurrentGeneration(generation) &&
            binding.generation == generation &&
            binding.session === castSession &&
            binding.sessionId == castSession.sessionId
    }

    private fun activeMediaClientOrNull(): ActiveMediaClient? {
        val binding = activeBinding ?: return null
        val castSession = binding.session
        val client = castSession.remoteMediaClient ?: return null
        val activeClient = ActiveMediaClient(castSession, client, binding.generation)
        return if (isActiveMediaClient(activeClient)) activeClient else null
    }

    private fun requireActiveMediaClient(): ActiveMediaClient =
        activeMediaClientOrNull() ?: error("No active Cast session.")

    private fun isActiveMediaClient(activeClient: ActiveMediaClient): Boolean =
        isTrackedActiveSession(activeClient.session, activeClient.generation) &&
            activeClient.session.isConnected &&
            activeClient.session.remoteMediaClient === activeClient.client &&
            castContext?.sessionManager?.currentCastSession === activeClient.session

    private fun isCurrentMediaCallback(client: RemoteMediaClient, generation: Long): Boolean {
        val registration = mediaCallbackRegistration
        return registration?.client === client && registration.generation == generation &&
            activeMediaClientOrNull()?.let { it.client === client && it.generation == generation } == true
    }

    private fun detachActiveSession(clearTerminalDisconnectMarker: Boolean = true) {
        cancelPositionHeartbeat()
        unregisterMediaCallback()
        removeSessionListener()
        activeBinding = null
        if (clearTerminalDisconnectMarker) terminalDisconnectGeneration = null
        lastBuffering = false
        lastPlayerState = null
    }

    private suspend fun clearCurrentSession(ctx: CastContext, reason: String) {
        val manager = ctx.sessionManager
        if (manager.currentCastSession == null) return
        logger.event("connect", "Ending existing Cast session before $reason")
        manager.endCurrentSession(true)
        val cleared = withTimeoutOrNull(SESSION_END_TIMEOUT_MS.milliseconds) {
            while (manager.currentCastSession != null) delay(SESSION_END_POLL_MS.milliseconds)
            true
        } ?: false
        if (!cleared) {
            logger.w("cast", "Existing Cast session did not clear before $reason")
            error("Couldn't close the existing Cast session.")
        }
    }

    private fun emitTerminalDisconnect(castSession: CastSession, generation: Long, reason: String) {
        if (!isTrackedActiveSession(castSession, generation)) {
            logger.trace(TAG, "Ignoring terminal callback from superseded Cast session: $reason")
            return
        }
        if (terminalDisconnectGeneration == generation) return
        terminalDisconnectGeneration = generation
        logger.w("playback", "Cast session $reason")
        detachActiveSession(clearTerminalDisconnectMarker = false)
        _events.tryEmit(PlaybackTargetEvent.Disconnected)
    }

    /** Human-readable Cast player state, so a shared diagnostics report needn't decode the raw enum int. */
    private fun playerStateName(state: Int?): String = when (state) {
        MediaStatus.PLAYER_STATE_IDLE -> "IDLE"
        MediaStatus.PLAYER_STATE_PLAYING -> "PLAYING"
        MediaStatus.PLAYER_STATE_PAUSED -> "PAUSED"
        MediaStatus.PLAYER_STATE_BUFFERING -> "BUFFERING"
        MediaStatus.PLAYER_STATE_LOADING -> "LOADING"
        MediaStatus.PLAYER_STATE_UNKNOWN -> "UNKNOWN"
        null -> "none"
        else -> "state($state)"
    }

    /** Human-readable Cast idle reason (the crucial one is ERROR: the receiver rejected/failed the media). */
    private fun idleReasonName(reason: Int?): String = when (reason) {
        MediaStatus.IDLE_REASON_NONE -> "NONE"
        MediaStatus.IDLE_REASON_FINISHED -> "FINISHED"
        MediaStatus.IDLE_REASON_CANCELED -> "CANCELED"
        MediaStatus.IDLE_REASON_INTERRUPTED -> "INTERRUPTED"
        MediaStatus.IDLE_REASON_ERROR -> "ERROR"
        null -> "none"
        else -> "reason($reason)"
    }

    companion object {
        private const val TAG = "CastTargetController"
        private const val CONNECT_TIMEOUT_MS = 20_000L
        // A transient Cast session-start failure clears on a fresh attempt, so connect() auto-retries
        // (ConnectRetryPolicy). This is the settle delay between attempts (drop half-open session -> wait ->
        // re-select route). Kept short so a retry that will succeed feels near-instant to the user.
        private const val CONNECT_RETRY_DELAY_MS = 900L
        private const val LOAD_TIMEOUT_MS = 20_000L
        private const val ROUTE_RECOVER_MS = 4_000L
        private const val SUSPENSION_GRACE_MS = 30_000L
        private const val POSITION_HEARTBEAT_INTERVAL_MS = 5_000L
        private const val SESSION_END_TIMEOUT_MS = 5_000L
        private const val SESSION_END_POLL_MS = 50L

        /**
         * Conservative capability baseline for the Cast Default Media Receiver: it reliably direct-plays
         * H.264 + AAC/MP3 in MP4 and supports HLS, so anything else (HEVC, AC3, MKV, 10-bit/HDR, …) is
         * transcoded server-side to HLS H.264/AAC — a format every Cast device can decode.
         */
        private val CAST_BASELINE = TargetCapabilities(
            protocol = Protocol.CAST,
            supportedContainers = setOf("mp4"),
            supportedVideoCodecs = setOf("h264"),
            supportedAudioCodecs = setOf("aac", "mp3"),
            supportsHevc = false,
            supports10Bit = false,
            supportsHls = true,
            supportedExternalSubtitleFormats = setOf("vtt"),
        )
    }
}
