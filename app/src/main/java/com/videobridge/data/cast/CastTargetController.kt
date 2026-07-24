package com.videobridge.data.cast

import android.content.Context
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
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
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.gms.common.api.PendingResult
import com.videobridge.core.cast.ConnectOutcome
import com.videobridge.core.cast.ConnectRetryPolicy
import com.videobridge.core.stream.Protocol
import com.videobridge.core.stream.TargetCapabilities
import com.videobridge.domain.DiscoveredTarget
import com.videobridge.domain.HlsSegmentFormat
import com.videobridge.domain.PlaybackFailureKind
import com.videobridge.domain.PlaybackTargetController
import com.videobridge.domain.PlaybackTargetEvent
import com.videobridge.domain.RendererStream
import com.videobridge.logging.DiagnosticsLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
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
    private val logger: DiagnosticsLogger,
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

    private var mediaCallback: RemoteMediaClient.Callback? = null
    private var routeCallback: MediaRouter.Callback? = null
    private var sessionListener: SessionManagerListener<CastSession>? = null
    // Pending "treat suspension as a disconnect" timer; cancelled if the SDK resumes the session itself.
    private val controllerScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var suspendGraceJob: Job? = null
    private var lastBuffering = false
    /** Last Cast player state we logged a transition for, so we emit a high-signal event only on change. */
    private var lastPlayerState: Int? = null

    private val session: CastSession?
        get() = castContext?.sessionManager?.currentCastSession

    override suspend fun discover(timeoutMillis: Long): List<DiscoveredTarget> {
        if (castContext == null) return emptyList()
        return withContext(Dispatchers.Main) {
            val router = MediaRouter.getInstance(appContext)
            // Keep the callback registered (do NOT remove after the scan): MediaRouter purges discovered
            // routes the instant no callback is active, which made the selected route vanish before
            // connect() could select it ("That Cast device is no longer available.").
            ensureRouteCallback(router)
            delay(timeoutMillis.milliseconds)
            val results = router.routes
                .filter { it.matchesSelector(selector) && !it.isDefault }
                .map { route ->
                    DiscoveredTarget(
                        id = route.id,
                        displayName = route.name,
                        protocol = Protocol.CAST,
                        capabilities = CAST_BASELINE.copy(modelName = route.description),
                        lastTestedStatus = null,
                    )
                }
            logger.event("discovery", "Cast scan: ${results.size} route(s)")
            results
        }
    }

    /**
     * Register one active-scan MediaRouter callback and keep it alive until [disconnect]. Routes only
     * exist while a callback is registered. Android suppresses active scan while the app is in the
     * background, so this does not drain battery when not casting.
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

    override suspend fun connect(target: DiscoveredTarget) {
        val ctx = castContext ?: run {
            logger.w("connect", "Cast connect failed — Cast is unavailable on this device")
            error("Cast is unavailable on this device.")
        }
        logger.event("connect", "Cast connect -> ${target.displayName}")
        logger.trace(TAG, "Cast connect -> ${target.displayName} (route ${target.id})")
        withContext(Dispatchers.Main) {
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
            val current = ctx.sessionManager.currentCastSession
            if (current != null && current.isConnected) {
                router.selectRoute(selected)
            } else {
                connectWithRetry(ctx) { router.selectRoute(selected) }
            }
            registerMediaCallback()
            registerSessionListener(ctx)
            logger.event("connect", "Cast session connected -> ${target.displayName}")
            _events.tryEmit(PlaybackTargetEvent.Connected)
        }
    }

    override suspend fun load(proxyUrl: String, stream: RendererStream, title: String, durationSeconds: Long?, startPositionSeconds: Long) =
        withContext(Dispatchers.Main) {
            val client = session?.remoteMediaClient ?: error("No Cast session")
            registerMediaCallback()
            lastPlayerState = null // a fresh load: log the new session's first state transition from "none"
            val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
                putString(MediaMetadata.KEY_TITLE, title)
            }
            val mediaInfoBuilder = MediaInfo.Builder(proxyUrl) // phone proxy URL ONLY
                .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
                .setContentType(stream.mimeType)
                .setMetadata(metadata)
            durationSeconds?.let { mediaInfoBuilder.setStreamDuration(it * 1000L) }
            // Cast defaults HLS media to MPEG-TS unless told otherwise. The phone's on-device
            // transcode is CMAF/fMP4, and Jellyfin can also select fMP4 for HEVC/AV1/VP9 profiles,
            // so both audio and video segment format must be declared explicitly.
            when (stream.hlsSegmentFormat) {
                HlsSegmentFormat.FMP4 -> {
                    mediaInfoBuilder.setHlsSegmentFormat(CastHlsSegmentFormat.FMP4)
                    mediaInfoBuilder.setHlsVideoSegmentFormat(CastHlsVideoSegmentFormat.FMP4)
                }
                HlsSegmentFormat.MPEG2_TS -> {
                    // Jellyfin's TS profile uses AAC audio multiplexed into the transport stream.
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
                .setAutoplay(true)
                .setCurrentTime(startPositionSeconds.coerceAtLeast(0) * 1000L)
                .build()
            logger.trace(
                TAG,
                "Cast load: type=${stream.mimeType} hlsFormat=${stream.hlsSegmentFormat} " +
                    "title=$title dur=${durationSeconds}s start=${startPositionSeconds}s url=$proxyUrl",
            )
            awaitLoad(client.load(request))
            logger.i(TAG, "Cast load issued (proxy URL, redacted)")
        }

    /**
     * Await the media-load [PendingResult] so the engine's follow-up calls (play, and progress tracking)
     * run against a receiver that has actually loaded the media. Bounded so a receiver that never answers
     * can't hang startup — the startup watchdog then catches a silent no-start. A non-success result is
     * logged (renderer errors surface via the media callback / IDLE_REASON_ERROR path), not thrown.
     */
    private suspend fun awaitLoad(pending: PendingResult<RemoteMediaClient.MediaChannelResult>) {
        withTimeoutOrNull(LOAD_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                pending.setResultCallback { result ->
                    if (!result.status.isSuccess) {
                        logger.w(TAG, "Cast load result not success (statusCode=${result.status.statusCode})")
                    }
                    if (cont.isActive) cont.resume(Unit)
                }
                cont.invokeOnCancellation { runCatching { pending.cancel() } }
            }
        } ?: logger.w(TAG, "Cast load did not complete within ${LOAD_TIMEOUT_MS}ms; proceeding")
    }

    override suspend fun play() = onMain { session?.remoteMediaClient?.play() }
    override suspend fun pause() = onMain { session?.remoteMediaClient?.pause() }
    override suspend fun seekTo(positionSeconds: Long) = onMain {
        logger.trace(TAG, "Cast seek -> ${positionSeconds}s")
        session?.remoteMediaClient?.seek(
            MediaSeekOptions.Builder().setPosition(positionSeconds * 1000L).build(),
        )
    }
    override suspend fun stop() = onMain { session?.remoteMediaClient?.stop() }
    override suspend fun setVolume(level: Float) = onMain { session?.volume = level.toDouble() }

    override suspend fun disconnect() = withContext(Dispatchers.Main) {
        unregisterMediaCallback()
        // Remove the persistent session listener BEFORE ending the session so our own intentional end
        // doesn't fire it as an "unexpected" disconnect.
        removeSessionListener()
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
    private fun registerSessionListener(ctx: CastContext) {
        if (sessionListener != null) return
        val listener = object : SessionManagerListener<CastSession> {
            override fun onSessionSuspended(s: CastSession, reason: Int) {
                // Don't react immediately: the Cast SDK transparently resumes brief blips (Wi-Fi handoff,
                // momentary signal loss). Only treat it as a real drop — and auto-reconnect — if it hasn't
                // resumed within the grace window, so a recoverable suspension doesn't force a needless
                // teardown + reload + "reconnecting" flash.
                logger.event("playback", "Cast session suspended (reason $reason); awaiting auto-recovery")
                suspendGraceJob?.cancel()
                suspendGraceJob = controllerScope.launch {
                    delay(SUSPEND_GRACE_MS.milliseconds)
                    logger.event("playback", "Cast session did not resume in time — treating as a disconnect")
                    _events.tryEmit(PlaybackTargetEvent.Disconnected)
                }
            }
            override fun onSessionResumed(s: CastSession, wasSuspended: Boolean) {
                // Recovered on its own — cancel the pending disconnect.
                logger.event("playback", "Cast session resumed (wasSuspended=$wasSuspended) — recovered")
                suspendGraceJob?.cancel(); suspendGraceJob = null
            }
            override fun onSessionEnded(s: CastSession, error: Int) {
                suspendGraceJob?.cancel(); suspendGraceJob = null
                // error != 0 => an unexpected end (our own endCurrentSession removes this listener first,
                // so it never reaches here for a deliberate stop).
                if (error != 0) {
                    logger.w("playback", "Cast session ended unexpectedly (error $error)")
                    _events.tryEmit(PlaybackTargetEvent.Disconnected)
                }
            }
            override fun onSessionStarting(s: CastSession) {}
            override fun onSessionStarted(s: CastSession, id: String) {}
            override fun onSessionStartFailed(s: CastSession, error: Int) {
                logger.w("cast", "Cast session start failed (error $error)")
            }
            override fun onSessionEnding(s: CastSession) {}
            override fun onSessionResuming(s: CastSession, id: String) {}
            override fun onSessionResumeFailed(s: CastSession, error: Int) {
                logger.w("cast", "Cast session resume failed (error $error)")
            }
        }
        sessionListener = listener
        ctx.sessionManager.addSessionManagerListener(listener, CastSession::class.java)
    }

    private fun removeSessionListener() {
        suspendGraceJob?.cancel(); suspendGraceJob = null
        val l = sessionListener ?: return
        castContext?.sessionManager?.removeSessionManagerListener(l, CastSession::class.java)
        sessionListener = null
    }

    /**
     * Start a Cast session, retrying a transient failure automatically ([ConnectRetryPolicy]) so a first-try
     * hiccup doesn't force the user to tap again. Between attempts it drops any half-open session and settles
     * briefly, then re-selects the route to trigger a fresh start. Throws only after retries are exhausted.
     */
    private suspend fun connectWithRetry(ctx: CastContext, select: () -> Unit) {
        var attemptsMade = 0
        var timeoutsSeen = 0
        while (true) {
            if (attemptsMade > 0) {
                // A fresh attempt clears a transient start failure: drop any half-open session, let the SDK
                // settle, then re-select the route (below, inside awaitSessionStart) to start anew.
                runCatching { ctx.sessionManager.endCurrentSession(true) }
                delay(CONNECT_RETRY_DELAY_MS.milliseconds)
            }
            val outcome = awaitSessionStart(ctx, select)
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
    private suspend fun awaitSessionStart(ctx: CastContext, select: () -> Unit): ConnectOutcome {
        val started = withTimeoutOrNull(CONNECT_TIMEOUT_MS.milliseconds) {
            suspendCancellableCoroutine { cont ->
                val manager = ctx.sessionManager
                val listener = object : SessionManagerListener<CastSession> {
                    private fun done() = manager.removeSessionManagerListener(this, CastSession::class.java)
                    override fun onSessionStarted(s: CastSession, id: String) { done(); if (cont.isActive) cont.resume(true) }
                    override fun onSessionResumed(s: CastSession, wasSuspended: Boolean) { done(); if (cont.isActive) cont.resume(true) }
                    override fun onSessionStartFailed(s: CastSession, error: Int) { done(); if (cont.isActive) cont.resume(false) }
                    override fun onSessionEnded(s: CastSession, error: Int) {}
                    override fun onSessionEnding(s: CastSession) {}
                    override fun onSessionResuming(s: CastSession, id: String) {}
                    override fun onSessionResumeFailed(s: CastSession, error: Int) {}
                    override fun onSessionStarting(s: CastSession) {}
                    override fun onSessionSuspended(s: CastSession, reason: Int) {}
                }
                manager.addSessionManagerListener(listener, CastSession::class.java)
                cont.invokeOnCancellation { manager.removeSessionManagerListener(listener, CastSession::class.java) }
                select()
            }
        }
        return when {
            started == true || ctx.sessionManager.currentCastSession?.isConnected == true -> ConnectOutcome.STARTED
            started == false -> ConnectOutcome.FAILED
            else -> ConnectOutcome.TIMED_OUT
        }
    }

    private fun registerMediaCallback() {
        val client = session?.remoteMediaClient ?: return
        if (mediaCallback != null) return
        val callback = object : RemoteMediaClient.Callback() {
            override fun onStatusUpdated() = emitStatus(client)
            override fun onMediaError(e: com.google.android.gms.cast.MediaError) {
                // Always kept (not just under tracing): a Cast media error is a top diagnostic signal.
                logger.w("cast", "Cast media error: reason=${e.reason} detailedCode=${e.detailedErrorCode} -> classified ${classifyCastError(e.detailedErrorCode)}")
                _events.tryEmit(PlaybackTargetEvent.Error(classifyCastError(e.detailedErrorCode), "Cast playback error"))
            }
        }
        mediaCallback = callback
        client.registerCallback(callback)
    }

    private fun unregisterMediaCallback() {
        val cb = mediaCallback ?: return
        session?.remoteMediaClient?.unregisterCallback(cb)
        mediaCallback = null
    }

    private fun emitStatus(client: RemoteMediaClient) {
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
        logger.trace(TAG, "Cast status: state=${playerStateName(state)} idle=${idleReasonName(status?.idleReason)} pos=${positionSeconds}s")
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
                    // The receiver rejected the media — e.g. an unsupported container/codec on an
                    // optimistic direct-play (a Cast Default Media Receiver can't demux MKV). Cast does
                    // NOT always fire onMediaError for this; it often just parks in IDLE with an ERROR
                    // reason. Surface it here too, or the direct-play -> transcode fallback never runs and
                    // playback silently dies (observed casting an MKV to an LG webOS receiver).
                    MediaStatus.IDLE_REASON_ERROR -> {
                        logger.w("cast", "Cast parked IDLE with error reason — treating as a FORMAT failure")
                        // No MediaError object here (it's a status idle reason); a receiver parking in
                        // IDLE/ERROR is overwhelmingly an unsupported format, so classify it as FORMAT.
                        _events.tryEmit(PlaybackTargetEvent.Error(PlaybackFailureKind.FORMAT, "Cast playback error"))
                    }
                    // NONE / CANCELED / INTERRUPTED are our own stop/reload transitions — ignore them.
                    else -> Unit
                }
            else -> Unit
        }
    }

    override suspend fun diagnosticStatus(): String = withContext(Dispatchers.Main) {
        val client = session?.remoteMediaClient ?: return@withContext "no Cast session"
        runCatching { client.requestStatus() } // nudge a fresh status from the receiver
        val status = client.mediaStatus
        val info = status?.mediaInfo
        buildString {
            append("cast state=").append(playerStateName(status?.playerState))
            append(" idle=").append(idleReasonName(status?.idleReason))
            append(" pos=").append(client.approximateStreamPosition / 1000L).append('s')
            if (info != null) {
                append(" contentType=").append(info.contentType)
                append(" streamType=").append(info.streamType)
            } else {
                append(" mediaInfo=none")
            }
        }
    }

    private suspend inline fun onMain(crossinline block: () -> Unit) = withContext(Dispatchers.Main) { block() }

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
        // Grace period after a Cast session suspension before treating it as a real disconnect, so the
        // SDK's own transparent resume of a brief blip isn't preempted by a needless reconnect.
        private const val SUSPEND_GRACE_MS = 4_000L

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
