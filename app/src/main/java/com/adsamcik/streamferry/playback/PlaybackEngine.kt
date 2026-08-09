package com.adsamcik.streamferry.playback

import com.adsamcik.streamferry.core.adaptive.AdaptiveBitrateController
import com.adsamcik.streamferry.core.adaptive.BitrateLadder
import com.adsamcik.streamferry.core.resume.NoOpSmartResumeRecordStore
import com.adsamcik.streamferry.core.resume.SmartResumeCheckpointKind
import com.adsamcik.streamferry.core.resume.SmartResumeDeviceContext
import com.adsamcik.streamferry.core.resume.SmartResumeSeed
import com.adsamcik.streamferry.core.resume.SmartResumeSessionTracker
import com.adsamcik.streamferry.core.stream.AudioTrackSelection
import com.adsamcik.streamferry.core.language.LanguageMatcher
import com.adsamcik.streamferry.core.language.TrackLanguage
import com.adsamcik.streamferry.core.stream.Protocol
import com.adsamcik.streamferry.core.stream.StreamPreferences
import com.adsamcik.streamferry.core.stream.TargetCapabilities
import com.adsamcik.streamferry.data.proxy.LocalProxyServer
import com.adsamcik.streamferry.diagnostics.NetworkInfoProvider
import com.adsamcik.streamferry.domain.DiscoveredTarget
import com.adsamcik.streamferry.domain.HlsSegmentFormat
import com.adsamcik.streamferry.domain.JellyfinRepository
import com.adsamcik.streamferry.domain.MediaItem
import com.adsamcik.streamferry.domain.MediaTrack
import com.adsamcik.streamferry.domain.PlaybackFailureKind
import com.adsamcik.streamferry.domain.PlaybackInfo
import com.adsamcik.streamferry.domain.PlaybackTargetController
import com.adsamcik.streamferry.domain.PlaybackTargetEvent
import com.adsamcik.streamferry.domain.RendererStream
import com.adsamcik.streamferry.logging.DiagnosticsLogger
import com.adsamcik.streamferry.playback.session.DefaultPlaybackSessionCoordinator
import com.adsamcik.streamferry.core.stream.StreamSelectionService
import com.adsamcik.streamferry.core.transcode.DeviceEncodeCapabilities
import com.adsamcik.streamferry.core.transcode.PlaybackRouter
import com.adsamcik.streamferry.core.transcode.ReceiverPlaybackCapabilities
import com.adsamcik.streamferry.core.transcode.ResolutionTier
import com.adsamcik.streamferry.core.transcode.RouteKind
import com.adsamcik.streamferry.core.transcode.SourceCapabilities
import com.adsamcik.streamferry.core.transcode.TranscodeNegotiator
import com.adsamcik.streamferry.core.stream.MediaProfile
import com.adsamcik.streamferry.core.transcode.TranscodeTarget
import com.adsamcik.streamferry.core.transcode.VideoCodec
import com.adsamcik.streamferry.core.volume.NightVolumeInput
import com.adsamcik.streamferry.core.volume.NightVolumePolicy
import com.adsamcik.streamferry.core.volume.NightVolumeScheduler
import com.adsamcik.streamferry.core.volume.NightVolumeSession
import com.adsamcik.streamferry.core.volume.RendererVolumeState
import com.adsamcik.streamferry.core.segments.MediaSegment
import com.adsamcik.streamferry.core.segments.MediaSegmentTracker
import com.adsamcik.streamferry.data.transcode.ClientTranscodeSession
import com.adsamcik.streamferry.data.jellyfin.DeviceProfiles
import com.adsamcik.streamferry.data.transcode.LocalMediaProbe
import com.adsamcik.streamferry.data.transcode.OnDeviceTranscoder
import android.content.Context
import java.io.File
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Snapshot of live playback for the UI (no secrets). */
data class PlaybackStatus(
    val targetName: String,
    val protocolName: String,
    val isPlaying: Boolean,
    val isBuffering: Boolean,
    val positionSeconds: Long,
    val durationSeconds: Long?,
    val streamMode: String,
    val currentBitrateBps: Long,
    val measuredThroughputBps: Long,
    val adaptiveNote: String,
    /** Bitrate rungs the user can pin the quality to (empty for local/offline; ≤1 hides the picker). */
    val availableBitratesBps: List<Long> = emptyList(),
    /** True when the user pinned a specific quality (adaptation paused); false = Auto (adaptive). */
    val isManualQuality: Boolean = false,
    /** Video codecs the TV can accept (best-first) for the manual codec picker; empty for local/offline. */
    val availableVideoCodecs: List<String> = emptyList(),
    /** Manually-chosen transcode codec (e.g. "hevc"), or null for automatic best-codec. */
    val preferredVideoCodec: String? = null,
    /** Saved automatic resolution cap, retained while a playback-only override is active. */
    val automaticMaxVideoHeight: Int? = null,
    /** Active server-stream resolution cap, or null when this is a local session. */
    val maxVideoHeight: Int? = null,
    /** True when the current session overrides the saved resolution cap. */
    val isManualMaxVideoHeight: Boolean = false,
    /** Source video resolution (px) and video-stream bitrate (bits/sec) when known, for the quality card. */
    val videoWidth: Int? = null,
    val videoHeight: Int? = null,
    val videoBitrateBps: Long? = null,
    /** Short description of the SOURCE media (codec · container · resolution), for the "what's playing" card. */
    val sourceFormat: String? = null,
    /** Short description of the OUTPUT sent to the TV when transcoding (codec · resolution · engine); null = direct. */
    val outputFormat: String? = null,
    val volume: Float = 1f,
    /** False until the active renderer has reported a valid volume baseline. */
    val volumeSupported: Boolean = false,
    val title: String = "",
    val errorMessage: String? = null,
    /** Bounded recovery state. All entries are redacted and safe to render/report. */
    val phase: PlaybackPhase = PlaybackPhase.STOPPED,
    val attemptGeneration: Long = 0,
    val attemptHistory: List<PlaybackAttemptDescriptor> = emptyList(),
    val recoveryBudget: RecoveryBudgetStatus = RecoveryBudget().status(RecoveryBudgetUsage()),
    val isTerminal: Boolean = false,
    /** True when an active session's renderer connection dropped unexpectedly (drives auto-reconnect). */
    val connectionLost: Boolean = false,
    /** Selectable audio tracks for the current media (empty for local/offline or single-track sources). */
    val audioTracks: List<MediaTrack> = emptyList(),
    /** Selectable subtitle tracks for the current media. */
    val subtitleTracks: List<MediaTrack> = emptyList(),
    /** Active audio track index (the server default when the user hasn't chosen one), or null if unknown. */
    val currentAudioIndex: Int? = null,
    /** Active subtitle track index, or null when subtitles are OFF. */
    val currentSubtitleIndex: Int? = null,
    /** A skippable segment (intro/outro/recap) covering the current position, for the "Skip" button. */
    val skipSegment: SkipSegment? = null,
)

/** A skippable segment surfaced to the UI: a button [label] and the position to seek to on skip. */
data class SkipSegment(val label: String, val targetSeconds: Long)

/** Immutable identity of a genuinely completed renderer session. */
data class PlaybackCompletion(
    val item: MediaItem?,
    /** True only when the completed stream had an active Jellyfin playback session. */
    val isJellyfinSession: Boolean,
    /** Exact renderer session; lets consumers reject a buffered event after a newer play begins. */
    val generation: Long,
)

/** Parameters of a LOCAL (offline / downloaded) playback, kept so the on-device stream can be reloaded. */
private data class LocalPlaybackParams(
    val filePath: String,
    val contentType: String,
    val title: String,
    val runtimeSeconds: Long?,
    val allowClientTranscode: Boolean,
)

/**
 * Orchestrates a single playback session end-to-end (§8, §9):
 *  - resolves a TV-compatible Jellyfin stream (PlaybackInfo) for the chosen quality,
 *  - starts the in-RAM proxy + foreground service and hands the TV ONLY the phone proxy URL,
 *  - tracks position / buffering from the renderer,
 *  - runs the [AdaptiveBitrateController] over real proxy throughput (averaged ≥ 30 s) and, when it
 *    decides, **switches quality mid-stream** by re-resolving PlaybackInfo at the current position and
 *    reloading the renderer — gradually and intelligently, never thrashing.
 *
 * Security invariant preserved: only the proxy URL is ever passed to the renderer; the controller and
 * throughput meter hold only numbers.
 */
class PlaybackEngine(
    private val jellyfin: JellyfinRepository,
    private val coordinator: DefaultPlaybackSessionCoordinator,
    private val proxy: LocalProxyServer,
    private val networkInfo: NetworkInfoProvider,
    private val serviceController: PlaybackServiceController,
    private val logger: DiagnosticsLogger,
    private val appContext: Context,
    private val onDeviceTranscoder: OnDeviceTranscoder,
    // A PROVIDER (not the value) so probing the phone's encoders — MediaCodecList enumeration, which can
    // take tens of ms — is deferred and runs off the main thread on first use (the engine only reads it
    // from Dispatchers.Default paths), not during construction (which happens on the main thread).
    deviceEncodeCapsProvider: () -> DeviceEncodeCapabilities,
    private val rendererCaps: RendererCapabilityStore,
    private val smartResume: SmartResumeSessionTracker = SmartResumeSessionTracker(NoOpSmartResumeRecordStore),
    private val clock: () -> Long = System::currentTimeMillis,
    /** Read lazily so a settings change applies to the next active scheduler tick. */
    private val nightVolumePolicyProvider: () -> NightVolumePolicy = { NightVolumePolicy.Off },
    /** Fresh local-network permission check for UI, notification, autoplay, and reconnect paths. */
    private val requireLocalNetworkAccess: () -> Unit = {},
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    // Evaluated lazily (thread-safe) on first on-device-transcode use, always from an off-main path.
    private val deviceEncodeCaps: DeviceEncodeCapabilities by lazy { deviceEncodeCapsProvider() }
    private val mutex = Mutex()
    private val streamSelection = StreamSelectionService()
    private val playbackRouter = PlaybackRouter()
    private val transcodeNegotiator = TranscodeNegotiator()

    private val _status = MutableStateFlow<PlaybackStatus?>(null)
    val status: StateFlow<PlaybackStatus?> = _status.asStateFlow()
    // Emits once when a genuine end-of-media is reached (not a reload/user-stop), so the ViewModel can
    // sync the exact completed item before considering autoplay. extraBufferCapacity keeps event threads nonblocking.
    private val _endOfMedia = MutableSharedFlow<PlaybackCompletion>(extraBufferCapacity = 1)
    val endOfMedia: SharedFlow<PlaybackCompletion> = _endOfMedia.asSharedFlow()

    @Volatile private var target: PlaybackTargetController? = null
    @Volatile private var selectedTarget: DiscoveredTarget? = null
    @Volatile private var item: MediaItem? = null
    /** Coordinator token for this online item; makes asynchronous progress reports session-safe. */
    @Volatile private var reportingSessionToken: Long? = null
    @Volatile private var prefs: StreamPreferences = StreamPreferences()

    @Volatile private var adaptive: AdaptiveBitrateController? = null
    @Volatile private var currentInfo: PlaybackInfo? = null
    /** Chosen audio stream index, or null to let the server pick its default. Re-resolves playback. */
    @Volatile private var audioSelection: Int? = null
    /** Chosen subtitle stream index, or null for OFF (no subtitles). A non-null selection is burned in. */
    @Volatile private var subtitleSelection: Int? = null
    /** Manual video-codec override for transcodes (e.g. "hevc"/"av1"), or null = automatic best-codec. */
    @Volatile private var preferredCodec: String? = null
    // Skippable segments (intro/outro/recap) for the current online item, and the set of segment indices
    // already auto-skipped this item so a user who seeks back in isn't force-skipped again.
    @Volatile private var mediaSegments: List<MediaSegment> = emptyList()
    private val skippedSegments = java.util.Collections.synchronizedSet(mutableSetOf<Int>())
    // One-shot per play: whether the preferred / per-show-remembered language has been applied (so a
    // re-resolve for quality/codec/manual-track changes doesn't keep re-applying it over the user).
    @Volatile private var languagePreferenceApplied = false
    // The media's selectable tracks, captured from the first resolve and kept stable across re-resolves
    // (a burn-in/transcode re-resolve can report fewer streams) so the pickers don't vanish mid-session.
    @Volatile private var mediaAudioTracks: List<MediaTrack> = emptyList()
    @Volatile private var mediaSubtitleTracks: List<MediaTrack> = emptyList()
    @Volatile private var currentIsHls = false
    /**
     * True when the current stream is a live server-side transcode (HLS for Cast, progressive for DLNA).
     * A transcode is NOT byte-seekable, so a seek/resume is done **server-side** by re-resolving the
     * stream at the target position ([reloadStream]); a direct-play stream ([currentIsTranscoding] == false)
     * is byte-range seekable and is seeked on the renderer. Distinct from [currentIsHls], which is only
     * true for the HLS *playlist* shape (a progressive DLNA transcode is transcoding but not HLS).
     */
    @Volatile private var currentIsTranscoding = false
    // The on-device transcode target (codec/resolution) currently being produced on the phone, or null
    // when not transcoding on-device — surfaced so the UI can show exactly what's being sent to the TV.
    @Volatile private var onDeviceTranscodeTarget: TranscodeTarget? = null
    // The probed source format of a LOCAL file (online sources use currentInfo.profile), for the same UI.
    @Volatile private var localSourceProfile: MediaProfile? = null
    // Parameters of the current LOCAL playback, kept so the on-device stream can be reloaded in place (a
    // manual codec change or an auto codec fallback) while reusing the live TV connection. Null = online.
    @Volatile private var localPlayback: LocalPlaybackParams? = null
    // User-pinned on-device transcode video codec for the current local file, or null = automatic.
    @Volatile private var forcedLocalCodec: VideoCodec? = null
    // Codecs the current TV can decode AND this phone can encode (best-first), for the local codec picker.
    @Volatile private var localTranscodeCodecOptions: List<VideoCodec> = emptyList()
    // On-device output codecs already tried and rejected by the TV this session, so auto-fallback advances.
    // Concurrent-safe: added under recoveryLock (the failure path) but cleared under mutex (play/stop paths).
    private val localCodecFallbacksTried = java.util.concurrent.ConcurrentHashMap.newKeySet<VideoCodec>()
    @Volatile private var streamStartSeconds = 0L
    @Volatile private var reportedPositionSeconds = 0L
    @Volatile private var isPlaying = false
    @Volatile private var isBuffering = false
    // After a seek we optimistically show the target position; a status poll that was already in flight when
    // the seek was issued (esp. DLNA, which polls the renderer) can report the OLD position and snap the
    // scrubber backward. Ignore contradicting reports until the renderer confirms a position near the target
    // (or a short window expires), so the phone reflects a seek immediately and holds it steadily.
    @Volatile private var seekSettleTargetSeconds: Long? = null
    @Volatile private var seekSettleUntilMs = 0L
    // True while we are intentionally tearing down and reloading the upstream stream (an HLS seek or an
    // adaptive bitrate switch). During that window the renderer briefly reports the OLD stream as
    // "finished", which must NOT be treated as end-of-media (it would stop the freshly-reloaded stream).
    @Volatile private var isReloadingStream = false
    // Set true after an optimistic direct-play attempt fails on the Cast receiver (the TV couldn't decode
    // the original): the engine then re-resolves with a safe server transcode. Reset on each new play.
    @Volatile private var forceTranscodeFallback = false
    // Set true after a NETWORK failure triggers a one-shot same-stream retry, so a persistent network
    // problem can't loop the retry. Reset on each new play() and on teardown.
    @Volatile private var retriedOnce = false
    // Set once a terminal failure has been surfaced for the CURRENT stream, so the extra failure events a
    // renderer emits for one failure (Cast fires onMediaError twice + an IDLE/ERROR status) don't
    // re-surface it 2-3x. Reset on every fresh resolve so a recovered/re-resolved stream can fail anew.
    @Volatile private var terminalFailureSurfaced = false
    // Startup watchdog (ideas 1+7): detects a stream that never actually starts playing on the TV — a
    // renderer that fails SILENTLY, with no error event — so recovery still runs. [playbackStarted] flips
    // true on the first PLAYING/position-advance; [startupBytesServed] tracks how much the proxy delivered
    // this attempt (distinguishes a decode failure from a TV that never fetched). Reset each attempt.
    @Volatile private var playbackStarted = false
    @Volatile private var startupBytesServed = 0L
    // Set true when an active renderer connection drops unexpectedly; surfaced in the status so the
    // ViewModel can auto-reconnect + resume. Cleared on each new play() and on teardown.
    @Volatile private var connectionLost = false
    @Volatile private var controlErrorMessage: String? = null
    @Volatile private var lastThroughputBps = 0L
    @Volatile private var lastNote = "Measuring link speed…"
    // True when the user has pinned a specific quality rung; adaptation is paused until they pick Auto.
    @Volatile private var manualQuality = false
    // Optional current-session cap. Choosing it forces a server transcode so it can recover a stream
    // without requiring the user to leave playback or edit their default in Settings.
    @Volatile private var manualMaxVideoHeight: Int? = null
    @Volatile private var rendererVolume = RendererVolumeState()
    private val volume: Float get() = rendererVolume.level
    private val volumeSynchronized: Boolean get() = rendererVolume.isSynchronized
    @Volatile private var smartResumeDeviceContext: SmartResumeDeviceContext? = null
    /** Session-only state survives an automatic protocol handoff, but never a fresh user Play. */
    @Volatile private var nightVolumeSession = NightVolumeSession()

    private var eventsJob: Job? = null
    private var monitorJob: Job? = null
    // The one-shot Cast direct-play -> transcode fallback reload. Tracked so it's cancelled on teardown
    // and can't orphan and run against a later play session if the mutex is contended. @Volatile because
    // it is written from the (off-mutex) event-collector coroutine and read under the mutex on teardown.
    @Volatile private var fallbackJob: Job? = null
    // The startup watchdog for the current attempt (ideas 1+7). Cancelled once playback starts / on teardown.
    @Volatile private var startupWatchdogJob: Job? = null
    // When true, end-of-media KEEPS the TV connection (+ proxy + foreground service) alive so the next
    // episode loads WITHOUT a reconnect (seamless autoplay-next); the ViewModel resolves the next episode
    // and calls [playNext]. Set per online-episode play; false for local / non-episode sources.
    @Volatile private var autoAdvance = false
    // Safety net: if end-of-media holds the connection for autoplay-next but no [playNext]/stop arrives
    // (last episode, resolution failed), this tears the session down after a grace period.
    @Volatile private var autoAdvanceTimeoutJob: Job? = null
    // Serializes the failure decision + re-entry flags so the event collector, the watchdog and the proxy
    // early-close signal can't race into a double recovery. See handlePlaybackFailure.
    private val recoveryLock = Any()
    // Monotonic id of the current live playback session, bumped on every new play()/playLocal(). An
    // end-of-media (Ended) handler captures the generation it belongs to and only tears the session down
    // if it is still current, so a stale "finished" from a just-stopped stream can't kill a newer session
    // that won the mutex first (e.g. when the user immediately starts a different item).
    @Volatile private var playGeneration = 0L
    /** Generation that reached a genuine end-of-media. Late renderer events for it must not revive playback. */
    @Volatile private var completedGeneration: Long? = null
    /** Pure ledger for attempt ordering, stale-work rejection, and the finite recovery budget. */
    @Volatile private var recoverySession = PlaybackRecoverySession()
    @Volatile private var pendingRecoveryKind: RecoveryAttemptKind? = null

    private val absolutePositionSeconds: Long get() = streamStartSeconds + reportedPositionSeconds

    /** Persist the latest renderer-confirmed position when the app leaves the foreground. */
    fun checkpointSmartResumeLifecycle() = smartResume.checkpoint(SmartResumeCheckpointKind.LIFECYCLE)

    /** Whether an asynchronous completion still belongs to the currently active renderer session. */
    fun isCurrentPlaybackGeneration(generation: Long): Boolean = playGeneration == generation
    /** Redacted snapshot for UI diagnostics and for coordinator-owned recovery decisions. */
    fun recoverySnapshot(): PlaybackRecoverySession = recoverySession

    /**
     * Reserve the one alternate-protocol recovery before the caller replaces the target controller and
     * invokes [play] or [playLocal] with the returned continuation. This preserves the shared attempt
     * budget/history across stop/start; a normal user-initiated retry simply omits the continuation.
     */
    fun reserveAlternateProtocolContinuation(
        hasAlternateProtocol: Boolean,
        failureStage: PlaybackFailureStage,
        failureCause: PlaybackFailureCause,
        sameEndpointRecoveryExhausted: Boolean = retriedOnce,
        isLocalSessionHint: Boolean? = null,
        isOnlineSessionHint: Boolean? = null,
    ): PlaybackRecoveryContinuation? = synchronized(recoveryLock) {
        val current = recoverySession
        val continuation = current.reserveAlternateProtocol(
            ProtocolSwitchInput(
                // Startup cleanup intentionally clears live source handles. The coordinator can provide
                // safe source-kind hints so a classified failed attempt remains eligible for handoff.
                isLocalSession = isLocalSessionHint ?: (localPlayback != null),
                isOnlineSession = isOnlineSessionHint ?: (currentInfo != null),
                hasAlternateProtocol = hasAlternateProtocol,
                hasAlreadySwitchedProtocol = current.alternateProtocolReserved || current.attempts.any {
                    it.automaticRecovery == RecoveryAttemptKind.ALTERNATE_PROTOCOL
                },
                sameEndpointRecoveryExhausted = sameEndpointRecoveryExhausted,
                failureStage = failureStage,
                failureCause = failureCause,
                budget = current.budget,
                usage = current.usage,
            ),
        )
        if (continuation != null) recoverySession = continuation.session
        continuation
    }

    /**
     * Reconnect the current endpoint once without creating a fresh playback session or resetting its
     * finite recovery budget. [refreshedEndpoint] is the same physical endpoint rebound by stable identity
     * after rediscovery, so a TV reboot can replace an ephemeral Cast route or DLNA control address. The
     * existing renderer controller, proxy orchestration and media choices are reused; failure leaves the
     * redacted ledger available for a possible alternate-protocol handoff.
     */
    suspend fun retrySameEndpointAfterDisconnect(
        refreshedEndpoint: DiscoveredTarget? = null,
    ): Boolean = withContext(Dispatchers.Default) {
        mutex.withLock {
            requireLocalNetworkAccess()
            if (!connectionLost) return@withLock false
            val activeTarget = target ?: return@withLock false
            val endpoint = refreshedEndpoint ?: selectedTarget ?: return@withLock false
            if (endpoint.protocol != activeTarget.protocol) {
                logger.w(TAG, "Ignoring same-endpoint retry with a different protocol")
                return@withLock false
            }
            if (currentInfo == null && localPlayback == null) return@withLock false
            val reserved = synchronized(recoveryLock) {
                recoverySession.reserveRecovery(
                    RecoveryAttemptKind.SAME_STREAM_NETWORK,
                    PlaybackPhase.RECONNECTING,
                )?.also {
                    recoverySession = it
                    retriedOnce = true
                    pendingRecoveryKind = RecoveryAttemptKind.SAME_STREAM_NETWORK
                }
            }
            if (reserved == null) {
                recoverySession = recoverySession.fail()
                publishStatus(error = "Couldn't reconnect to the TV.")
                return@withLock false
            }
            val resumeAt = absolutePositionSeconds
            val wasReloading = isReloadingStream
            isReloadingStream = true
            var endpointConnected = false
            try {
                selectedTarget = endpoint
                activeTarget.connect(endpoint)
                endpointConnected = true
                if (localPlayback != null) {
                    reloadLocalInPlace(resumeAt, "endpoint reconnect")
                } else {
                    reloadStream(
                        resumeAt,
                        requestedBitrate = adaptive?.currentBitrateBps,
                        reason = "endpoint reconnect",
                        armWatchdog = true,
                    )
                }
                connectionLost = false
                lastNote = "Reconnected to the TV"
                publishStatus()
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                smartResume.checkpoint(SmartResumeCheckpointKind.FAILURE, smartResumeDeviceContext)
                val stage = when {
                    e.isPreparationFailure() -> PlaybackFailureStage.STREAM_RESOLUTION
                    !endpointConnected -> PlaybackFailureStage.ENDPOINT_CONNECTION
                    else -> PlaybackFailureStage.SEEK_OR_RELOAD
                }
                val cause = when {
                    e.isPreparationFailure() -> PlaybackFailureCause.UPSTREAM_OR_SERVER_UNAVAILABLE
                    !endpointConnected -> PlaybackFailureCause.ENDPOINT_UNAVAILABLE
                    else -> PlaybackFailureCause.UNKNOWN
                }
                recoverySession = recoverySession
                    .recordFailure(stage, cause)
                    .fail()
                logger.w(TAG, "Same-endpoint reconnect failed", e)
                publishStatus(error = "Couldn't reconnect to the TV.")
                false
            } finally {
                isReloadingStream = wasReloading
            }
        }
    }

    /** Begin playing [item] to [selectedTarget] via [target]. [resumePositionOverrideSeconds], when set,
     *  takes precedence over the item's Jellyfin resume point (used by auto-reconnect to resume at the
     *  live position). [onForegrounded] is invoked once the proxy service has entered the foreground —
     *  the caller navigates to the playback screen THEN (not before), so the screen's recomposition can't
     *  delay onStartCommand and blow the startForegroundService() deadline (see docs/PROXY_DESIGN.md). */
    suspend fun play(
        item: MediaItem,
        target: PlaybackTargetController,
        selectedTarget: DiscoveredTarget,
        prefs: StreamPreferences,
        resumePositionOverrideSeconds: Long? = null,
        autoAdvance: Boolean = false,
        smartResumeSeed: SmartResumeSeed? = null,
        smartResumeDeviceContext: SmartResumeDeviceContext? = null,
        onForegrounded: () -> Unit = {},
        recoveryContinuation: PlaybackRecoveryContinuation? = null,
    ) = withContext(Dispatchers.Default) {
        // Run playback startup OFF the main thread. The Cast/DLNA controllers switch to Main internally
        // for each SDK call, so the main thread stays free BETWEEN them for the foreground service to
        // call startForeground() within its deadline — otherwise the system raises
        // ForegroundServiceDidNotStartInTimeException (seen on Galaxy S24 / Android 16).
        mutex.withLock {
        requireLocalNetworkAccess()
        val preservedNightVolumeSession = if (recoveryContinuation != null) nightVolumeSession else null
        stopInternalLocked("starting new playback")
        nightVolumeSession = preservedNightVolumeSession ?: NightVolumeSession(
            startedAt = Instant.ofEpochMilli(clock()),
        )
        this@PlaybackEngine.smartResumeDeviceContext = smartResumeDeviceContext
        smartResume.prepare(smartResumeSeed, smartResumeDeviceContext)
        playGeneration++
        completedGeneration = null
        recoverySession = recoveryContinuation?.let { recoverySession.continueFrom(it) } ?: recoverySession.startSession()
        pendingRecoveryKind = recoveryContinuation?.reservedKind
        connectionLost = false
        // A fresh item starts from its saved/default track choices. An automatic protocol handoff keeps
        // the user's source track selections because it is the same media session on the same screen.
        if (recoveryContinuation == null) {
            audioSelection = null
            subtitleSelection = null
            preferredCodec = null
            languagePreferenceApplied = false
        } else {
            languagePreferenceApplied = true
        }
        mediaSegments = emptyList()
        skippedSegments.clear()
        mediaAudioTracks = emptyList()
        mediaSubtitleTracks = emptyList()
        this@PlaybackEngine.item = item
        this@PlaybackEngine.target = target
        this@PlaybackEngine.selectedTarget = selectedTarget
        this@PlaybackEngine.prefs = prefs
        this@PlaybackEngine.autoAdvance = autoAdvance
        lastNote = "Measuring link speed…"
        try {
            // Promote to the foreground FIRST — while the main thread is still quiet (the caller has NOT
            // yet navigated to the playback screen) — and WAIT until the service has actually called
            // startForeground(). Context.startForegroundService() obliges the service to foreground within
            // ~5 s; if the playback-screen recomposition, the Cast/DLNA handshake, or the media load run
            // first they queue ahead of onStartCommand on the main thread and blow that deadline
            // (ForegroundServiceDidNotStartInTimeException on a Galaxy S24 / Android 16). Only once
            // foregrounded do we signal the caller to navigate ([onForegrounded]) and start the handshake.
            ensureProxyServiceForegrounded()
            onForegrounded()
            eventsJob = scope.launch { target.events.collect { onTargetEvent(target, it) } }
            target.connect(selectedTarget)
            synchronizeVolumeFromTargetLocked()
            val startPos = PlaybackPositionPolicy.clamp(
                resumePositionOverrideSeconds ?: item.resumePositionSeconds ?: 0L,
                item.runtimeSeconds,
            )
            startPlaybackWithFallback(startPos, requestedBitrate = prefs.maxBitrateBps)
            // Now that the first resolve has revealed the media's tracks, honor the user's preferred /
            // per-show-remembered audio & subtitle language (re-resolves once only when it differs from
            // the server default). Done before the monitor loop so it settles on the final stream.
            applyPreferredLanguageLocked()
            monitorJob = scope.launch { monitorLoop() }
            // Fetch skippable segments (intro/outro/recap) off the startup path; empty if unsupported.
            val segItemId = item.id
            scope.launch {
                val segments = runCatching { jellyfin.mediaSegments(segItemId) }.getOrDefault(emptyList())
                if (segments.isNotEmpty() && item?.id == segItemId) {
                    mediaSegments = segments
                    logger.event("playback", "Loaded ${segments.size} skippable segment(s) (intro/outro)")
                    publishStatus()
                }
            }
            logger.event("playback", "Playing ${item.title} -> ${selectedTarget.displayName} (${selectedTarget.protocol})")
        } catch (e: CancellationException) {
            withContext(NonCancellable) { stopInternalLocked("playback start cancelled") }
            throw e
        } catch (e: Exception) {
            // Keep a classified terminal checkpoint even though cleanup invalidates the active generation.
            val cause = if (e.isPreparationFailure()) {
                PlaybackFailureCause.UPSTREAM_OR_SERVER_UNAVAILABLE
            } else {
                PlaybackFailureCause.UNKNOWN
            }
            recoverySession = recoverySession.recordFailure(PlaybackFailureStage.STREAM_RESOLUTION, cause).fail()
            smartResume.checkpoint(SmartResumeCheckpointKind.FAILURE, smartResumeDeviceContext)
            // Roll back any partial setup so the user isn't left on a stuck "preparing" screen.
            stopInternalLocked("playback start failed")
            logger.e("playback", "Playback start failed", e)
            throw e
        }
        }
    }

    /**
     * Seamlessly play [item] on the ALREADY-CONNECTED target for autoplay-next: reuse the live Cast/DLNA
     * connection, proxy and foreground service (no reconnect), reloading the stream in place. Returns
     * false when there's no live session to reuse (the caller then does a full [play]); throws if the new
     * stream fails to load (after tearing the session down), so the caller surfaces it like a play error.
     * [prefs] is the caller-resolved per-item stream preference (same series ⇒ same language memory).
     */
    suspend fun playNext(
        item: MediaItem,
        prefs: StreamPreferences,
        smartResumeSeed: SmartResumeSeed? = null,
        autoAdvance: Boolean = false,
    ): Boolean = withContext(Dispatchers.Default) {
        mutex.withLock {
            requireLocalNetworkAccess()
            val tgt = target
            val sel = selectedTarget
            if (tgt == null || sel == null) return@withLock false // no live session — caller falls back to full play
            autoAdvanceTimeoutJob?.cancel(); autoAdvanceTimeoutJob = null
            playGeneration++
            completedGeneration = null // supersede the ended session (its pending end-of-media stop/timeout is now stale)
            recoverySession = recoverySession.startSession()
            smartResume.prepare(smartResumeSeed, smartResumeDeviceContext)
            connectionLost = false
            // Fresh per-item + recovery state. We do NOT tear the session down (that's the whole point), so
            // reset the flags stopInternalLocked would otherwise clear.
            audioSelection = null
            subtitleSelection = null
            preferredCodec = null
            manualMaxVideoHeight = null
            languagePreferenceApplied = false
            mediaSegments = emptyList()
            skippedSegments.clear()
            mediaAudioTracks = emptyList()
            mediaSubtitleTracks = emptyList()
            forceTranscodeFallback = false
                retriedOnce = false
            terminalFailureSurfaced = false
            playbackStarted = false
            this@PlaybackEngine.item = item
            this@PlaybackEngine.prefs = prefs
            this@PlaybackEngine.autoAdvance = autoAdvance
            lastNote = "Loading the next item…"
            publishStatus()
            val startPos = PlaybackPositionPolicy.clamp(item.resumePositionSeconds ?: 0L, item.runtimeSeconds)
            try {
                // Reuse the reload mechanism (prepareReload + coordinator.stop) so the target connection is
                // preserved (many DLNA renderers also need a Stop before a new URI), then resolve + load the
                // new item with a fresh adaptive ladder. isReloadingStream suppresses any stray end-of-media
                // from the old stream during teardown.
                isReloadingStream = true
                try {
                    runCatching { tgt.prepareReload() }
                    runCatching { coordinator.stop("autoplay next") }
                    startPlaybackWithFallback(startPos, requestedBitrate = prefs.maxBitrateBps)
                } finally {
                    isReloadingStream = false
                }
                applyPreferredLanguageLocked()
                if (monitorJob?.isActive != true) monitorJob = scope.launch { monitorLoop() }
                val segItemId = item.id
                scope.launch {
                    val segments = runCatching { jellyfin.mediaSegments(segItemId) }.getOrDefault(emptyList())
                    if (segments.isNotEmpty() && this@PlaybackEngine.item?.id == segItemId) {
                        mediaSegments = segments
                        publishStatus()
                    }
                }
                logger.event("playback", "Autoplay next -> ${item.title} on ${sel.displayName} (${sel.protocol}) — seamless (no reconnect)")
                true
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                stopInternalLocked("autoplay-next load failed")
                logger.e("playback", "Seamless autoplay-next failed to load", e)
                throw e
            }
        }
    }

    /**
     * Hold the (finished) session open for [AUTO_ADVANCE_HOLD_MS] so [playNext] can reuse the connection;
     * if nothing supersedes [gen] within the grace, tear the session down (last episode / resolve failed).
     */
    private fun armAutoAdvanceTimeout(gen: Long) {
        autoAdvanceTimeoutJob?.cancel()
        autoAdvanceTimeoutJob = scope.launch {
            delay(AUTO_ADVANCE_HOLD_MS)
            logger.event("playback", "Autoplay-next didn't start within ${AUTO_ADVANCE_HOLD_MS / 1000}s; stopping")
            stopIfCurrent(gen, "autoplay-next timed out")
        }
    }

    /** Update the seamless hand-off policy when the user changes the active playlist. */
    suspend fun setAutoAdvance(enabled: Boolean) = mutex.withLock {
        autoAdvance = enabled && target != null && item != null && completedGeneration != playGeneration
    }

    suspend fun togglePlayPause() = mutex.withLock {
        // Decide while holding the same lock that executes the command. Two rapid UI/key events then
        // alternate deterministically instead of both observing one stale pre-command state.
        if (isPlaying) pauseLocked() else resumeLocked()
    }

    suspend fun resume() = mutex.withLock {
        resumeLocked()
    }

    private suspend fun resumeLocked() {
        requireLocalNetworkAccess()
        val t = target ?: return
        t.play()
        isPlaying = true
        controlErrorMessage = null
        reportJellyfinProgressSoon()
        publishStatus()
    }

    suspend fun pause() = mutex.withLock {
        pauseLocked()
    }

    private suspend fun pauseLocked() {
        requireLocalNetworkAccess()
        val t = target ?: return
        t.pause()
        isPlaying = false
        controlErrorMessage = null
        smartResume.checkpoint(SmartResumeCheckpointKind.PAUSED)
        reportJellyfinProgressSoon()
        publishStatus()
    }

    /** Seek by a relative amount (used by media-button / notification skip actions). */
    suspend fun skip(deltaSeconds: Long) = seekTo(absolutePositionSeconds + deltaSeconds)

    suspend fun setVolume(level: Float) = mutex.withLock {
        requireLocalNetworkAccess()
        suspendNightVolumeAutomationForManualAdjustment()
        setVolumeLocked(level)
    }

    private suspend fun setVolumeLocked(level: Float) {
        val t = target ?: return
        val requested = level.coerceIn(0f, 1f)
        t.setVolume(requested)
        rendererVolume = rendererVolume.acceptExplicit(requested)
        publishStatus()
    }

    /** Reads the TV before enabling a relative adjustment; a stale phone-side default is never used. */
    private suspend fun synchronizeVolumeFromTargetLocked(): Boolean {
        val controller = target ?: return false
        val endpoint = selectedTarget ?: return false
        if (!endpoint.discoveryMetadata.volumeControlAvailable) return false
        rendererVolume = rendererVolume.awaitRenderer()
        val reported = runCatching { controller.readCurrentVolume() }
            .onFailure { logger.w(TAG, "Couldn't read the TV's current volume", it) }
            .getOrNull()
        rendererVolume = rendererVolume.acceptReported(reported)
        if (!volumeSynchronized) {
            logger.w(TAG, "TV did not report an initial volume; remote volume controls remain disabled")
            publishStatus()
            return false
        }
        if (nightVolumeSession.startingVolume == null) {
            nightVolumeSession = nightVolumeSession.copy(startingVolume = volume)
        }
        logger.event("playback", "TV volume synchronized to ${(volume * 100).toInt()}%")
        publishStatus()
        return true
    }

    /** Step volume up (direction > 0) or down (direction < 0) by one notch. */
    suspend fun adjustVolume(direction: Int) {
        if (direction == 0) return
        mutex.withLock {
            requireLocalNetworkAccess()
            if (!volumeSynchronized && !synchronizeVolumeFromTargetLocked()) return@withLock
            val requested = rendererVolume.adjustedLevel(direction, VOLUME_STEP) ?: return@withLock
            suspendNightVolumeAutomationForManualAdjustment()
            setVolumeLocked(requested)
        }
    }

    /**
     * Play a previously-downloaded, app-private file to [selectedTarget] — works OFFLINE (no Jellyfin).
     * The renderer still only ever receives the phone proxy URL; there is no adaptive bitrate (a local
     * file isn't re-encoded), and nothing is reported to Jellyfin.
     */
    suspend fun playLocal(
        filePath: String,
        contentType: String,
        title: String,
        runtimeSeconds: Long?,
        allowClientTranscode: Boolean,
        resumePositionSeconds: Long = 0,
        smartResumeSeed: SmartResumeSeed? = null,
        smartResumeDeviceContext: SmartResumeDeviceContext? = null,
        target: PlaybackTargetController,
        selectedTarget: DiscoveredTarget,
        onForegrounded: () -> Unit = {},
        recoveryContinuation: PlaybackRecoveryContinuation? = null,
    ) = withContext(Dispatchers.Default) {
        // Off the main thread so the foreground service can foreground within its deadline (see play()).
        mutex.withLock {
        requireLocalNetworkAccess()
        val preservedNightVolumeSession = if (recoveryContinuation != null) nightVolumeSession else null
        stopInternalLocked("starting downloaded playback")
        nightVolumeSession = preservedNightVolumeSession ?: NightVolumeSession(
            startedAt = Instant.ofEpochMilli(clock()),
        )
        this@PlaybackEngine.smartResumeDeviceContext = smartResumeDeviceContext
        smartResume.prepare(smartResumeSeed, smartResumeDeviceContext)
        playGeneration++
        completedGeneration = null
        recoverySession = recoveryContinuation?.let { recoverySession.continueFrom(it) } ?: recoverySession.startSession()
        pendingRecoveryKind = recoveryContinuation?.reservedKind
        this@PlaybackEngine.target = target
        this@PlaybackEngine.selectedTarget = selectedTarget
        localPlayback = LocalPlaybackParams(filePath, contentType, title, runtimeSeconds, allowClientTranscode)
        forcedLocalCodec = null // a fresh file starts on automatic codec negotiation
        localCodecFallbacksTried.clear()
        try {
            // See play(): foreground FIRST (while the caller is still on the quiet picker screen) and WAIT
            // for startForeground() BEFORE navigating ([onForegrounded]) or starting the renderer handshake,
            // so the startForegroundService() deadline can't be missed behind main-thread work.
            ensureProxyServiceForegrounded()
            onForegrounded()
            eventsJob = scope.launch { target.events.collect { onTargetEvent(target, it) } }
            target.connect(selectedTarget)
            synchronizeVolumeFromTargetLocked()
            loadLocalStreamLocked(PlaybackPositionPolicy.clamp(resumePositionSeconds, runtimeSeconds))
            monitorJob = scope.launch { monitorLoop() }
        } catch (e: CancellationException) {
            withContext(NonCancellable) { stopInternalLocked("downloaded playback start cancelled") }
            throw e
        } catch (e: Exception) {
            val cause = if (e.isPreparationFailure()) {
                PlaybackFailureCause.UPSTREAM_OR_SERVER_UNAVAILABLE
            } else {
                PlaybackFailureCause.UNKNOWN
            }
            recoverySession = recoverySession.recordFailure(PlaybackFailureStage.RENDERER_LOAD, cause).fail()
            smartResume.checkpoint(SmartResumeCheckpointKind.FAILURE, smartResumeDeviceContext)
            stopInternalLocked("downloaded playback start failed")
            logger.e("playback", "Playback start failed", e)
            throw e
        }
        }
    }

    /**
     * Resolve and load the current LOCAL file onto the (already-connected) target at [startPos]: choose the
     * on-device transcode target (honouring any [forcedLocalCodec] pick), build the phone-hosted HLS/CMAF
     * or direct-play origin, and start the renderer. Used by the initial [playLocal] and by
     * [reloadLocalInPlace] (a manual codec change or an auto codec fallback), which reuse the live
     * connection. Assumes [target]/[selectedTarget]/[localPlayback] are set; throws on load failure.
     */
    private suspend fun loadLocalStreamLocked(startPos: Long) {
        requireLocalNetworkAccess()
        val params = localPlayback ?: error("No local media selected")
        val sel = selectedTarget ?: error("No target")
        val tgt = target ?: error("No target")
        var transcodeTarget = if (params.allowClientTranscode) {
            runCatching { chooseClientTranscodeTarget(params.filePath, params.contentType, sel) }
                .onFailure { logger.w("transcode", "On-device transcode negotiation failed; direct-playing as-is", it) }
                .getOrNull()
        } else {
            null // default: hand the TV the file and let it decode (direct play), like a native gallery share
        }
        // Every local mode needs a real timeline for the phone scrubber, lock-screen absolute seek, resume,
        // and VOD HLS planning. Probe it whenever the caller lacks one, including direct play (the old code
        // only did this for on-device transcode and silently disabled seeking for SAF-picked direct files).
        var effectiveRuntimeSeconds = params.runtimeSeconds?.takeIf { it > 0 }
        if (effectiveRuntimeSeconds == null) {
            effectiveRuntimeSeconds = LocalMediaProbe.probeDurationSeconds(appContext, params.filePath)
            if (effectiveRuntimeSeconds != null) {
                logger.event("playback", "Probed local video duration: ${effectiveRuntimeSeconds}s (caller had none)")
            } else if (transcodeTarget != null) {
                logger.w("transcode", "Couldn't determine local video duration; on-device transcode isn't possible — direct-playing as-is")
                transcodeTarget = null
            }
        }
        val boundedStartPos = PlaybackPositionPolicy.clamp(startPos, effectiveRuntimeSeconds)
        this@PlaybackEngine.item = MediaItem(
            id = "local", title = params.title, year = null, runtimeSeconds = effectiveRuntimeSeconds,
            overview = null, resumePositionSeconds = null, isFolder = false,
        )
        lastNote = if (transcodeTarget != null) "Transcoding on-device (offline)" else "Playing a downloaded copy (offline)"
        // Always-on event so a shared diagnostics report shows the local playback decision + outcome even
        // without TV tracing (a top reason local casting "does nothing" is a silent transcode fallback).
        logger.event(
            "playback",
            "Local playback: \"${params.title}\" (${params.contentType}, runtime=${effectiveRuntimeSeconds ?: "?"}s, " +
                "allowTranscode=${params.allowClientTranscode}) -> " +
                (transcodeTarget?.let { "on-device transcode ${it.videoCodec} ${it.maxResolution.maxHeightPx}p" }
                    ?: "direct play"),
        )
        val phoneIp = networkInfo.lanIpv4()
            ?: throw PlaybackPreparationException("No phone LAN address is available for the media gateway.")
        currentInfo = null
        // An on-device transcode is a full-timeline HLS stream (seekable): reflect that so the seek
        // path (renderer-seek, not server re-resolve) and status/telemetry are correct. A direct-play
        // local file is a plain progressive stream.
        currentIsHls = transcodeTarget != null
        currentIsTranscoding = transcodeTarget != null
        onDeviceTranscodeTarget = transcodeTarget // null for direct play; set for on-device transcode
        streamStartSeconds = 0
        reportedPositionSeconds = boundedStartPos
        seekSettleTargetSeconds = null
        noteJellyfinPosition(reportedPositionSeconds)
        val streamForTv: RendererStream
        val url: String
        if (transcodeTarget != null) {
            val cacheDir = File(File(appContext.cacheDir, "transcode"), "ct_$playGeneration")
            val session = ClientTranscodeSession(
                onDeviceTranscoder, params.filePath, (effectiveRuntimeSeconds ?: 0L).toDouble(), transcodeTarget, cacheDir, logger,
            )
            url = coordinator.startClientTranscodeAndBuildUrl(session, phoneIp).second
            // Media3's active phone transcode path always emits fragmented MP4 behind HLS.
            streamForTv = RendererStream(
                mimeType = "application/vnd.apple.mpegurl",
                hlsSegmentFormat = HlsSegmentFormat.FMP4,
            )
        } else {
            url = preparePlaybackSource("Couldn't start the local media gateway.") {
                coordinator.startLocalAndBuildUrl(params.filePath, params.contentType, phoneIp).second
            }
            // Advertise DLNA byte seeking for plain files and for SAF/MediaStore descriptors only after
            // proving they have a stable size and support lseek. Cloud-backed pipes remain conservative.
            streamForTv = RendererStream(
                mimeType = params.contentType,
                isByteSeekable = LocalMediaProbe.isByteSeekable(appContext, params.filePath),
            )
        }
        // Track byte flow + early TV bail-out so the startup watchdog can catch a local file the TV
        // silently never plays (local has no server transcode, so recovery surfaces the failure).
        proxy.byteListener = { n -> startupBytesServed += n }
        proxy.onDownstreamClosed = { bytes, durationMs, completed -> onDownstreamClosedEarly(bytes, durationMs, completed) }
        logger.event(
            "playback",
            "Loading ${if (transcodeTarget != null) "on-device HLS transcode" else "direct-play (${streamForTv.mimeType})"} " +
                "on ${sel.displayName} (${sel.protocol})",
        )
        recoverySession = recoverySession.beginAttempt(
            PlaybackAttemptDescriptor(
                generation = 0,
                endpoint = redactPlaybackEndpoint(phoneIp),
                protocol = sel.protocol.name,
                sourceKind = if (transcodeTarget != null) PlaybackAttemptSourceKind.ON_DEVICE_TRANSCODE else PlaybackAttemptSourceKind.LOCAL,
                route = if (transcodeTarget != null) PlaybackAttemptRoute.ON_DEVICE_TRANSCODE else PlaybackAttemptRoute.DIRECT,
                codec = transcodeTarget?.videoCodec?.name?.lowercase() ?: localSourceProfile?.videoCodec,
                container = localSourceProfile?.container,
                capabilitySummary = "hls=${sel.capabilities.supportsHls};hevc=${sel.capabilities.supportsHevc}",
                startPositionSeconds = boundedStartPos,
                reason = "local stream load",
                automaticRecovery = pendingRecoveryKind,
            ),
        )
        val attemptGeneration = recoverySession.generation
        pendingRecoveryKind = null
        // Arm before load as well as after it: Cast can synchronously deliver an initial position callback
        // while the load result is being awaited, and that pre-position status must not snap resume to zero.
        armRendererStartSettle(reportedPositionSeconds)
        tgt.load(
            url,
            streamForTv,
            params.title,
            effectiveRuntimeSeconds,
            startPositionSeconds = reportedPositionSeconds,
            playWhenReady = true,
        )
        armRendererStartSettle(reportedPositionSeconds)
        armStartupWatchdog(attemptGeneration)
        publishStatus()
        logger.event("playback", "Playing ${params.title} -> ${sel.displayName} (${sel.protocol})")
    }

    /**
     * Seamlessly reload the current LOCAL file on the ALREADY-CONNECTED target (a manual codec change or an
     * auto codec fallback): reuse the live connection/proxy/foreground service and just re-resolve + reload
     * the on-device stream at [startPos]. Mirrors [reloadStream]/[playNext] for the offline path.
     */
    private suspend fun reloadLocalInPlace(startPos: Long, reason: String) {
        playGeneration++
        completedGeneration = null // supersede the current stream (its stray end-of-media/watchdog is now stale)
        val wasReloading = isReloadingStream
        isReloadingStream = true
        connectionLost = false
        playbackStarted = false
        startupBytesServed = 0
        terminalFailureSurfaced = false
        try {
            proxy.stop()
            // Tell a polled controller (DLNA) we're tearing down first, then Stop before the new URI (many
            // renderers need it), then reload in place from the current position.
            runCatching { target?.prepareReload() }
            runCatching { coordinator.stop(reason) }
            loadLocalStreamLocked(startPos)
        } finally {
            isReloadingStream = wasReloading
        }
    }

    suspend fun seekTo(absoluteSeconds: Long) = mutex.withLock {
        requireLocalNetworkAccess()
        val t = target ?: return@withLock
        val duration = currentInfo?.runtimeSeconds ?: item?.runtimeSeconds ?: localPlayback?.runtimeSeconds
        val pos = PlaybackPositionPolicy.clamp(absoluteSeconds, duration)
        smartResume.noteSeekRequested(pos)
        // Only a progressive (non-HLS) transcode is a non-seekable live stream that must be re-resolved at
        // the new position server-side. A direct-play file AND a full-timeline HLS transcode are seekable,
        // so the renderer seeks within the existing stream (matching what the TV's own controls do) — a
        // server-side re-resolve of a full-timeline stream would reload it and play from 0 ("restart").
        val serverSideSeek = PlaybackPositionPolicy.requiresServerReload(currentIsTranscoding, currentIsHls)
        logger.event("playback", "Seek -> ${pos}s (${if (serverSideSeek) "server-side re-resolve" else "renderer seek"})")
        if (serverSideSeek) {
            // Optimistically reflect the seek target so a transient status from the torn-down old stream
            // can't flash the reported position back to 0 during the reload.
            streamStartSeconds = pos
            reportedPositionSeconds = 0
            publishStatus()
            reloadStream(
                pos,
                requestedBitrate = adaptive?.currentBitrateBps,
                reason = "seek",
                playWhenReady = isPlaying,
            )
            controlErrorMessage = null
            adaptive?.let { it.noteApplied(it.currentIndex, clock()) } // reset window; don't change quality
        } else {
            // The proxy advertises the entity length (direct play) / the HLS playlist is full-timeline, so
            // the renderer seeks the stream itself. Do not move the scrubber until the command succeeds:
            // a rejected Cast seek must remain visible to the caller instead of looking like a completed
            // jump. Afterwards, guard against an older status poll snapping the confirmed target back.
            t.seekTo(pos)
            reportedPositionSeconds = pos
            armRendererStartSettle(pos)
            noteJellyfinPosition(pos)
            controlErrorMessage = null
            reportJellyfinProgressSoon()
            publishStatus()
        }
    }

    suspend fun stop() = mutex.withLock { stopInternalLocked("user stopped") }

    /**
     * Invoked by the permission monitor. Close local exposure before waiting for [mutex], which can be
     * held by a slow renderer/Jellyfin startup path; the remaining target/session cleanup is idempotent.
     */
    suspend fun onLocalNetworkPermissionRevoked() {
        val hadActivePlayback = target != null || proxy.isRunning
        if (!hadActivePlayback) return
        proxy.stop()
        runCatching { serviceController.stop() }
        mutex.withLock {
            if (target != null || hadActivePlayback) {
                logger.event("playback", "Local network permission revoked; ending the active TV session")
                stopInternalLocked("local network permission revoked")
            }
        }
    }

    /**
     * Switch the active audio track to the server stream [index] (null = server default) and re-resolve
     * playback at the current position. Changing the audio track requires a fresh Jellyfin stream (the
     * proxy serves a single track), so this re-resolves like a seek. No-op for a non-online (local) session.
     */
    suspend fun selectAudioTrack(index: Int?) = mutex.withLock {
        if (currentInfo == null || audioSelection == index) return@withLock
        logger.event("playback", "Audio track -> ${index ?: "default"}; re-resolving")
        reloadSelectionTransaction(audioSelection, index, { audioSelection = it }) {
            reloadStreamPreservingPosition("audio track change")
        }
    }

    /**
     * Enable subtitles at the server stream [index] (burned into the video), or disable them ([index] ==
     * null). Re-resolves playback at the current position with the new subtitle selection. Enabling forces
     * a transcode (burn-in is the only reliable way to show a subtitle through the proxy on Cast + DLNA).
     */
    suspend fun selectSubtitleTrack(index: Int?) = mutex.withLock {
        if (currentInfo == null || subtitleSelection == index) return@withLock
        logger.event("playback", "Subtitle -> ${index?.let { "track $it (burned in)" } ?: "off"}; re-resolving")
        reloadSelectionTransaction(subtitleSelection, index, { subtitleSelection = it }) {
            reloadStreamPreservingPosition("subtitle change")
        }
    }

    /**
     * Pin playback to a specific quality [bitrateBps] (a rung the adaptive controller offers), or return to
     * **Auto** ([bitrateBps] == null) so link-speed adaptation resumes. Pinning re-resolves the stream at
     * the chosen bitrate at the current position (like an adaptive switch — Jellyfin transcodes to fit) and
     * pauses adaptation until Auto is chosen again. No-op for a non-online (local) session, which has no
     * adaptive controller / bitrate ladder.
     */
    suspend fun selectQuality(bitrateBps: Long?) = mutex.withLock {
        val a = adaptive ?: return@withLock
        if (bitrateBps == null) {
            if (!manualQuality) return@withLock
            manualQuality = false
            lastNote = "Auto quality — adapting to the link"
            logger.event("quality", "Quality -> Auto (adaptation resumed)")
            publishStatus()
            return@withLock
        }
        val index = BitrateLadder.indexAtOrBelow(a.ladder, bitrateBps)
        val target = a.ladder[index]
        val wasManual = manualQuality
        val previousNote = lastNote
        val previousStreamStart = streamStartSeconds
        val previousReportedPosition = reportedPositionSeconds
        manualQuality = true
        if (!wasManual || target != a.currentBitrateBps) {
            logger.event("quality", "Quality cap -> ${target / 1000} kbps (manual Jellyfin override)")
            // An override must re-resolve even when the selected rung matches the current displayed rate:
            // direct play can have that rate without Jellyfin having applied the requested output cap.
            try {
                reloadStream(absolutePositionSeconds, requestedBitrate = target, reason = "manual quality override")
            } catch (failure: Throwable) {
                manualQuality = wasManual
                lastNote = previousNote
                streamStartSeconds = previousStreamStart
                reportedPositionSeconds = previousReportedPosition
                publishStatus()
                throw failure
            }
            a.noteApplied(index, clock())
        }
        lastNote = "Manual quality selected — adaptation paused"
        publishStatus()
    }

    /**
     * Override the server-stream resolution for this playback session. A concrete cap deliberately
     * requests a server transcode so choosing 1080p/720p is a reliable escape hatch from a broken direct
     * play or 4K profile; null returns to the saved automatic policy.
     */
    suspend fun selectMaxVideoHeight(height: Int?) = mutex.withLock {
        if (currentInfo == null || height !in setOf(null, 2160, 1080, 720, 480)) return@withLock
        if (manualMaxVideoHeight == height) return@withLock
        val previousHeight = manualMaxVideoHeight
        val previousStreamStart = streamStartSeconds
        val previousReportedPosition = reportedPositionSeconds
        val previousNote = lastNote
        manualMaxVideoHeight = height
        val label = height?.let { "${it}p" } ?: "Auto"
        lastNote = "Resolution -> $label; reloading stream…"
        logger.event("quality", "Resolution override -> $label; forcing server transcode=${height != null}")
        val pos = absolutePositionSeconds
        streamStartSeconds = pos
        reportedPositionSeconds = 0
        publishStatus()
        try {
            reloadStream(pos, requestedBitrate = adaptive?.currentBitrateBps, reason = "manual resolution", armWatchdog = true)
        } catch (failure: Throwable) {
            manualMaxVideoHeight = previousHeight
            streamStartSeconds = previousStreamStart
            reportedPositionSeconds = previousReportedPosition
            lastNote = previousNote
            publishStatus()
            throw failure
        }
    }

    /**
     * Manually pin the video codec used for TRANSCODES (e.g. "hevc", "av1", "vp9", "h264"), or null =
     * automatic. For an ONLINE source this re-resolves the Jellyfin stream to the chosen codec at the
     * current position. For a LOCAL on-device transcode it re-encodes the file to the chosen codec and
     * reloads it in place (seamless — reuses the live TV connection). A codec the phone can't encode or the
     * TV can't decode is ignored. No-op for a direct-play local file (there's no transcode to steer).
     */
    suspend fun selectPreferredCodec(codec: String?) = mutex.withLock {
        // Local on-device transcode: steer the phone's encoder and reload in place.
        if (localPlayback != null && currentInfo == null) {
            if (onDeviceTranscodeTarget == null) return@withLock // direct-play local file: nothing to transcode
            val requested = codec?.let { parseVideoCodec(it) }?.takeIf { it in localTranscodeCodecOptions }
            if (forcedLocalCodec == requested) return@withLock
            val previousCodec = forcedLocalCodec
            val previousFallbacks = localCodecFallbacksTried.toSet()
            val previousStreamStart = streamStartSeconds
            val previousReportedPosition = reportedPositionSeconds
            forcedLocalCodec = requested
            localCodecFallbacksTried.clear() // a manual pick supersedes any in-progress auto-fallback
            logger.event("quality", "Local transcode codec -> ${requested?.name?.lowercase() ?: "auto"}; reloading on-device")
            val pos = absolutePositionSeconds
            streamStartSeconds = pos // hold the position so a torn-down status can't flash it to 0
            reportedPositionSeconds = 0
            publishStatus()
            try {
                reloadLocalInPlace(pos, "local codec change")
            } catch (failure: Throwable) {
                forcedLocalCodec = previousCodec
                localCodecFallbacksTried.clear()
                localCodecFallbacksTried.addAll(previousFallbacks)
                streamStartSeconds = previousStreamStart
                reportedPositionSeconds = previousReportedPosition
                publishStatus()
                throw failure
            }
            return@withLock
        }
        if (currentInfo == null) return@withLock
        val requested = codec?.let { parseVideoCodec(it)?.name?.lowercase() ?: return@withLock }
        if (requested != null && requested !in tvVideoCodecs(selectedTarget?.capabilities ?: return@withLock)) {
            logger.w("quality", "Ignoring unsupported TV output format: $requested")
            return@withLock
        }
        if (preferredCodec == requested) return@withLock
        logger.event("quality", "Preferred codec -> ${requested ?: "auto"}; re-resolving")
        reloadSelectionTransaction(preferredCodec, requested, { preferredCodec = it }) {
            reloadStreamPreservingPosition("codec change")
        }
    }

    private fun parseVideoCodec(codec: String): VideoCodec? = when (codec.lowercase()) {
        "h264", "avc", "avc1" -> VideoCodec.H264
        "hevc", "h265", "hvc1" -> VideoCodec.HEVC
        "vp9", "vp09" -> VideoCodec.VP9
        "av1", "av01" -> VideoCodec.AV1
        else -> null
    }

    /** Re-resolve the current online stream at the current absolute position (used by track changes). */
    private suspend fun reloadStreamPreservingPosition(reason: String) {
        val pos = absolutePositionSeconds
        // Optimistically hold the position so a transient status from the torn-down stream can't flash it
        // back to 0 while the new (likely transcoded) stream loads.
        streamStartSeconds = pos
        reportedPositionSeconds = 0
        publishStatus()
        reloadStream(pos, requestedBitrate = adaptive?.currentBitrateBps, reason = reason)
    }

    /** Apply a UI selection only if its replacement stream loads; restore it on failure/cancellation. */
    private suspend fun <T> reloadSelectionTransaction(
        previous: T,
        requested: T,
        apply: (T) -> Unit,
        reload: suspend () -> Unit,
    ) {
        val previousStreamStart = streamStartSeconds
        val previousReportedPosition = reportedPositionSeconds
        val previousNote = lastNote
        apply(requested)
        try {
            reload()
        } catch (failure: Throwable) {
            apply(previous)
            streamStartSeconds = previousStreamStart
            reportedPositionSeconds = previousReportedPosition
            lastNote = previousNote
            publishStatus()
            throw failure
        }
    }

    /**
     * Stop only if [gen] is still the current session. Guards a stale end-of-media (which is dispatched
     * as a coroutine that is NOT a child of eventsJob, so teardown's cancelAndJoin can't cancel it) from
     * tearing down a newer playback session that won the mutex first.
     */
    private suspend fun stopIfCurrent(gen: Long, reason: String) = mutex.withLock {
        if (gen == playGeneration && !isReloadingStream) {
            stopInternalLocked(reason)
        } else {
            logger.w(TAG, "Ignoring stale end-of-media (a newer playback session is active)")
        }
    }

    // ----- internals -----

    /**
     * Capabilities to advertise to Jellyfin. For Cast we **optimistically** advertise a broad profile
     * (HEVC/10-bit/AC3/MKV/…) so the server **direct-plays** when the TV can decode the original, instead
     * of always transcoding to the lowest common denominator. If the receiver then can't decode it,
     * [onTargetEvent] sets [forceTranscodeFallback] and we re-resolve with the safe conservative profile.
     */
    private fun effectiveCaps(): TargetCapabilities {
        val target = selectedTarget ?: error("No target capabilities")
        // A recovery or explicit Jellyfin override must use only the selected target's proven capabilities;
        // the broad direct-play profile is deliberately not an output-format promise.
        if (forceTranscodeFallback || hasManualJellyfinOverride()) return target.capabilities
        return when {
            // Cast advertises a broad optimistic profile (incl. HEVC/10-bit) when preferring direct play.
            target.protocol == Protocol.CAST && prefs.preferDirectPlay -> CAST_DIRECT_PLAY_CAPS
            // 4K HDR passthrough: let DLNA (and Cast without direct-play preference) advertise HEVC + 10-bit
            // so a capable TV's native decoder direct-plays the original 4K HDR HEVC. Falls back to H.264 on
            // a genuine decode failure (the branch above).
            effectiveMaxVideoHeight() >= 2160 -> target.capabilities.withHevcHdrPassthrough()
            else -> target.capabilities
        }
    }

    /** Broaden caps to advertise HEVC 10-bit + common containers for a 4K HDR passthrough direct-play attempt. */
    private fun TargetCapabilities.withHevcHdrPassthrough(): TargetCapabilities = copy(
        supportedContainers = supportedContainers + setOf("mkv", "mp4", "ts", "m4v"),
        supportedVideoCodecs = supportedVideoCodecs + setOf("h264", "hevc", "h265"),
        supportsHevc = true,
        supports10Bit = true,
    )

    private fun hasManualJellyfinOverride(): Boolean =
        manualQuality || manualMaxVideoHeight != null || preferredCodec != null

    private fun effectiveForceTranscode(): Boolean =
        prefs.forceTranscode || forceTranscodeFallback || hasManualJellyfinOverride()

    private fun effectiveMaxVideoHeight(): Int = manualMaxVideoHeight ?: prefs.maxVideoHeight

    private fun nextLowerResolution(): Int? = when (effectiveMaxVideoHeight()) {
        in 2160..Int.MAX_VALUE -> 1080
        in 1080..2159 -> 720
        in 720..1079 -> 480
        else -> null
    }

    /**
     * The server's default audio track index (the track that plays on direct play with no explicit
     * selection), or null when the tracks aren't known yet. Used to decide whether an audio selection
     * differs from what direct play would already give — and to reflect the playing track in the picker.
     */
    private fun defaultAudioIndex(): Int? =
        mediaAudioTracks.firstOrNull { it.isDefault }?.index ?: mediaAudioTracks.firstOrNull()?.index

    /**
     * Apply the caller-resolved preferred audio/subtitle language (per-show memory already merged over the
     * global preference in [prefs]) once per play, after the first resolve has revealed the tracks. Selects
     * the matching audio/subtitle track and re-resolves ONLY when it differs from what's already playing
     * (server-default audio / subtitles-off), so the common case (preference == default) causes no reload.
     * Caller holds [mutex].
     */
    private suspend fun applyPreferredLanguageLocked() {
        if (languagePreferenceApplied) return
        languagePreferenceApplied = true
        if (currentInfo == null) return // not an online session; no server track list to match against
        val audioIdx = LanguageMatcher.matchIndex(
            mediaAudioTracks.map { TrackLanguage(it.index, it.language) },
            prefs.preferredAudioLanguage,
        )
        val subIdx = LanguageMatcher.matchIndex(
            mediaSubtitleTracks.map { TrackLanguage(it.index, it.language) },
            prefs.preferredSubtitleLanguage,
        )
        val applyAudio = audioIdx != null && audioIdx != defaultAudioIndex()
        val applySub = subIdx != null && subIdx != subtitleSelection
        if (!applyAudio && !applySub) return
        val previousAudio = audioSelection
        val previousSubtitle = subtitleSelection
        val previousStreamStart = streamStartSeconds
        val previousReportedPosition = reportedPositionSeconds
        val previousNote = lastNote
        if (applyAudio) audioSelection = audioIdx
        if (applySub) subtitleSelection = subIdx
        logger.event(
            "playback",
            "Applying preferred language (audio=${prefs.preferredAudioLanguage ?: "-"}, " +
                "subtitle=${prefs.preferredSubtitleLanguage ?: "-"}); re-resolving",
        )
        try {
            reloadStreamPreservingPosition("preferred language")
        } catch (failure: Throwable) {
            audioSelection = previousAudio
            subtitleSelection = previousSubtitle
            languagePreferenceApplied = false
            streamStartSeconds = previousStreamStart
            reportedPositionSeconds = previousReportedPosition
            lastNote = previousNote
            publishStatus()
            throw failure
        }
    }

    /** Stable-ish identity for the learned-capability store: protocol + the TV's model/name. */
    private fun deviceKey(t: DiscoveredTarget): String =
        "${t.protocol}:${t.capabilities.modelName ?: t.displayName}"

    private suspend fun <T> preparePlaybackSource(message: String, block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: PlaybackPreparationException) {
        throw e
    } catch (e: Exception) {
        throw PlaybackPreparationException(message, e)
    }

    private fun Throwable.isPreparationFailure(): Boolean =
        generateSequence(this) { it.cause }.any { it is PlaybackPreparationException }

    /**
     * Fail closed on foreground-service setup. The local proxy is not a best-effort convenience: it owns
     * the network/CPU locks that keep the receiver's URL usable while the app is backgrounded, so a load
     * must never be issued until the exact start request has been confirmed foregrounded.
     */
    private suspend fun ensureProxyServiceForegrounded() {
        when (val result = serviceController.startAndAwaitForegrounded()) {
            is ForegroundServiceStartResult.Foregrounded -> {
                logger.event("playback", "Proxy foreground service confirmed (${result.path})")
            }
            is ForegroundServiceStartResult.StartRejected -> {
                logger.e("playback", "Proxy foreground-service start was rejected; aborting before renderer load", result.cause)
                throw PlaybackPreparationException("The phone playback service was rejected.", result.cause)
            }
            is ForegroundServiceStartResult.ConfirmationTimedOut -> {
                logger.e(
                    "playback",
                    "Proxy foreground service was not confirmed within ${result.timeoutMs}ms; aborting before renderer load",
                )
                throw PlaybackPreparationException("The phone playback service did not start in time.")
            }
        }
    }

    /**
     * Cast's load request already has autoplay enabled. A follow-up play command can race that load and
     * conceal its failure, while DLNA renderers still require their separate Play request.
     */
    private fun notePlaybackLoadIntent(playWhenReady: Boolean) {
        // Controllers own load-time autoplay now. Keeping this helper makes the intent explicit at call sites
        // without issuing a second Play that can race Cast load or hide a rejected DLNA Play command.
        if (!playWhenReady) logger.trace(TAG, "Loaded replacement stream paused at the requested position")
    }

    private fun armRendererStartSettle(positionSeconds: Long) {
        if (positionSeconds <= 0L) return
        seekSettleTargetSeconds = positionSeconds
        seekSettleUntilMs = clock() + SEEK_SETTLE_WINDOW_MS
    }

    /**
     * Remember a receiver limitation only after a controller supplied explicit decode/source-not-supported
     * evidence for a direct-play stream. A generic load exception, proxy failure, idle error, or watchdog
     * timeout can still use the one-off fallback, but must not become a persistent transcode rule.
     */
    private fun rememberTranscodeRequirement(
        qualifiedFormatEvidence: Boolean,
        wasDirectPlay: Boolean,
    ) {
        val t = selectedTarget ?: return
        val profile = currentInfo?.profile
        if (!shouldPersistTranscodeRequirement(qualifiedFormatEvidence, wasDirectPlay, profile)) {
            logger.event(
                "playback",
                "Not persisting renderer capability downgrade (qualified=$qualifiedFormatEvidence direct=$wasDirectPlay profileKnown=${profile != null})",
            )
            return
        }
        rendererCaps.recordTranscodeRequired(deviceKey(t), RendererMediaFormat.from(requireNotNull(profile)))
    }

    private suspend fun resolveAndLoad(
        positionSeconds: Long,
        requestedBitrate: Long?,
        initialiseAdaptive: Boolean,
        armWatchdog: Boolean = false,
        playWhenReady: Boolean = true,
    ) {
        requireLocalNetworkAccess()
        val item = this.item ?: error("No media selected")
        val target = this.target ?: error("No target")
        // A fresh stream resolve: clear the terminal-failure guard so this attempt can surface its own
        // failure (a prior stream's terminal error must not suppress a new stream's).
        terminalFailureSurfaced = false
        onDeviceTranscodeTarget = null // a server resolve is not an on-device transcode (the fallback sets it)
        val caps = effectiveCaps()
        val phoneIp = networkInfo.lanIpv4()
            ?: throw PlaybackPreparationException("No phone LAN address is available for the media gateway.")

        val bitrate = requestedBitrate ?: adaptive?.currentBitrateBps
            // 4K HDR passthrough: on the first resolve (no explicit/adaptive bitrate yet) lift the request
            // cap well above the 20 Mbps default so a high-bitrate 4K HDR source direct-plays instead of
            // being force-transcoded to fit. The adaptive start below still uses the source bitrate.
            ?: HDR_PASSTHROUGH_MAX_BITRATE_BPS.takeIf { effectiveMaxVideoHeight() >= 2160 }
        val burnIn = prefs.allowSubtitleBurnIn || subtitleSelection != null
        // A specific (non-default) audio track can only be delivered reliably through the proxy by
        // transcoding: on DIRECT PLAY the TV receives the whole container and plays ITS OWN default track
        // (the proxy can't switch an embedded audio track on Cast/DLNA). So force a transcode whenever the
        // chosen track isn't the server default — the server then muxes only the selected audio into the
        // stream. Mirrors how a chosen subtitle forces burn-in.
        val audioTrackOverride = AudioTrackSelection.requiresServerTranscode(audioSelection, defaultAudioIndex())
        val forceTranscode = effectiveForceTranscode() || audioTrackOverride
        // A manual codec override advertises only that output codec and, through [effectiveForceTranscode],
        // prevents direct play so Jellyfin must either honor it or return a clear failure.
        val profileOverride = preferredCodec?.let {
            DeviceProfiles.forTarget(caps, bitrate, forceTranscode, burnIn, preferredVideoCodec = it, maxVideoHeight = effectiveMaxVideoHeight())
        }
        val info = preparePlaybackSource("Jellyfin couldn't prepare this media source.") {
            jellyfin.playbackInfo(
                itemId = item.id,
                capabilities = caps,
                maxBitrateBps = bitrate,
                forceTranscode = forceTranscode,
                // A selected subtitle is burned into the video (the only way to show it reliably through the
                // proxy on both Cast and DLNA), so enable burn-in whenever one is chosen.
                allowSubtitleBurnIn = burnIn,
                audioStreamIndex = audioSelection,
                // null selection = OFF: pass -1 so the server explicitly omits subtitles (not its default).
                subtitleStreamIndex = subtitleSelection ?: -1,
                startPositionSeconds = positionSeconds,
                maxVideoHeight = effectiveMaxVideoHeight(),
                deviceProfileOverride = profileOverride,
            ).getOrThrow()
        }

        // If this renderer previously rejected this exact direct-play media format, don't hand it the
        // original again — force a transcode from the start (skips the doomed direct-play round-trip +
        // failure wait). One-level re-resolve: the re-entry has effectiveForceTranscode()=true, so it's skipped.
        val known = selectedTarget
        if (known != null && !effectiveForceTranscode() &&
            rendererCaps.shouldForceTranscode(deviceKey(known), RendererMediaFormat.from(info.profile))
        ) {
            forceTranscodeFallback = true
            logger.event("playback", "Renderer previously rejected this format; transcoding from the start")
            resolveAndLoad(positionSeconds, requestedBitrate, initialiseAdaptive, armWatchdog)
            return
        }
        val upstream = preparePlaybackSource("Jellyfin couldn't resolve the selected media stream.") {
            jellyfin.resolveUpstream(info)
        }

        if (initialiseAdaptive) {
            val ladder = BitrateLadder.forSource(info.sourceBitrateBps)
            val start = requestedBitrate ?: info.sourceBitrateBps ?: ladder.last()
            adaptive = AdaptiveBitrateController(ladder, start, clock = clock)
            // Feed the adaptive controller AND the startup watchdog's byte counter, and learn when the TV
            // bails early (idea 1). Set once per session; reset per-attempt in armStartupWatchdog.
            proxy.byteListener = { n -> startupBytesServed += n; adaptive?.recordThroughput(n, clock()) }
            proxy.onDownstreamClosed = { bytes, durationMs, completed -> onDownstreamClosedEarly(bytes, durationMs, completed) }
        }

        currentInfo = info
        // Capture selectable tracks the first time they're known and keep them (a burn-in/transcode
        // re-resolve may report fewer streams) so the pickers stay populated for the whole session.
        if (info.audioTracks.isNotEmpty()) mediaAudioTracks = info.audioTracks
        if (info.subtitleTracks.isNotEmpty()) mediaSubtitleTracks = info.subtitleTracks
        currentIsHls = upstream.isHls
        currentIsTranscoding = upstream.isTranscoding
        // Only a PROGRESSIVE (non-HLS) transcode is a genuine live stream that begins server-side at the
        // requested position (its own t=0 == media position). A direct-play file AND a full-timeline HLS
        // transcode (Jellyfin VOD HLS lists 0..end regardless of startTimeTicks) are seekable, so the
        // renderer positions itself within them and the stream's t=0 is media 0.
        val serverSideStart = PlaybackPositionPolicy.requiresServerReload(upstream.isTranscoding, upstream.isHls)
        streamStartSeconds = if (serverSideStart) positionSeconds else 0L
        reportedPositionSeconds = if (serverSideStart) 0L else positionSeconds
        seekSettleTargetSeconds = null // fresh stream: don't let a prior seek's hold suppress its statuses

        val (_, url) = preparePlaybackSource("Couldn't establish the phone-hosted Jellyfin gateway.") {
            coordinator.startAndBuildUrl(info, upstream, phoneIp, initialPositionSeconds = positionSeconds)
        }
        reportingSessionToken = coordinator.activeReportToken()
        noteJellyfinPosition(positionSeconds)
        // Hand the start position to load() so the renderer begins AT it (Cast sets it in the load request;
        // DLNA seeks once playing) for a direct-play file or a full-timeline HLS transcode — no separate,
        // racy post-load seek. A progressive transcode already starts at the position server-side (load 0).
        val loadStart = PlaybackPositionPolicy.rendererLoadPosition(positionSeconds, serverSideStart)
        logger.event(
            "playback",
            "Loading ${if (upstream.isTranscoding) "transcode" else "direct-play"} " +
                "(${if (upstream.isHls) "HLS/${upstream.hlsSegmentFormat}" else "progressive"}, " +
                    "${upstream.contentType}, output=${upstream.outputContainer}) at ${loadStart}s " +
                "on ${selectedTarget?.displayName} (${selectedTarget?.protocol})",
        )
        recoverySession = recoverySession.beginAttempt(
            PlaybackAttemptDescriptor(
                generation = 0,
                endpoint = redactPlaybackEndpoint(phoneIp),
                protocol = selectedTarget?.protocol?.name,
                sourceKind = PlaybackAttemptSourceKind.ONLINE,
                route = if (upstream.isTranscoding) PlaybackAttemptRoute.SERVER_TRANSCODE else PlaybackAttemptRoute.DIRECT,
                codec = info.profile.videoCodec,
                container = info.profile.container ?: upstream.outputContainer,
                capabilitySummary = "hls=${caps.supportsHls};hevc=${caps.supportsHevc};10bit=${caps.supports10Bit}",
                startPositionSeconds = positionSeconds,
                audioStreamIndex = audioSelection,
                subtitleStreamIndex = subtitleSelection,
                reason = if (armWatchdog) "automatic recovery load" else "stream load",
                automaticRecovery = pendingRecoveryKind,
            ),
        )
        val attemptGeneration = recoverySession.generation
        pendingRecoveryKind = null
        if (!serverSideStart) armRendererStartSettle(loadStart)
        target.load(
            url,
            upstream.rendererStream,
            item.title,
            info.runtimeSeconds,
            startPositionSeconds = loadStart,
            playWhenReady = playWhenReady,
        )
        notePlaybackLoadIntent(playWhenReady)
        if (!serverSideStart) armRendererStartSettle(loadStart)
        // Watch for the TV silently never starting (ideas 1+7). Only for a fresh start / recovery reload —
        // not a seek or bitrate switch, where playback is already established.
        if (armWatchdog) armStartupWatchdog(attemptGeneration)
        publishStatus()
    }

    /**
     * Run the initial resolve+load for an online session, and if the optimistic direct-play can't even
     * START (e.g. a DLNA renderer synchronously rejects the URI, or the media fails to load), fall back
     * ONCE to a server transcode before surfacing the failure — the startup analogue of the runtime
     * FORMAT recovery in [onTargetEvent]. Only when a fallback is possible (direct-play preferred, not
     * already forcing/fallen back).
     */
    private suspend fun startPlaybackWithFallback(positionSeconds: Long, requestedBitrate: Long?) {
        try {
            resolveAndLoad(positionSeconds, requestedBitrate = requestedBitrate, initialiseAdaptive = true, armWatchdog = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e.isPreparationFailure()) throw e
            if (!prefs.preferDirectPlay || effectiveForceTranscode()) throw e
            logger.w(TAG, "Playback failed to start on the renderer; retrying once with a server transcode", e)
            // A thrown startup/load exception has no qualified decoder evidence. Recover once, but do not
            // permanently downgrade this renderer: the cause may have been the proxy, CORS, a range request,
            // network loss, or an interrupted session rather than an unsupported media format.
            val reserved = recoverySession.reserveRecovery(
                RecoveryAttemptKind.FORMAT_COMPATIBILITY, PlaybackPhase.CHANGING_STREAM,
            ) ?: throw e
            recoverySession = reserved
            pendingRecoveryKind = RecoveryAttemptKind.FORMAT_COMPATIBILITY
            forceTranscodeFallback = true
            resolveAndLoad(positionSeconds, requestedBitrate = requestedBitrate, initialiseAdaptive = true, armWatchdog = true)
        }
    }

    /**
     * Tear down the current upstream stream and reload it at [positionSeconds] (used by an HLS seek, an
     * adaptive bitrate switch, and the failure-recovery reloads). [isReloadingStream] is held for the
     * whole teardown→reload so the renderer's transient "finished" on the old stream isn't mistaken for
     * end-of-media. [armWatchdog] re-arms the startup watchdog for recovery reloads (not seeks/switches).
     */
    private suspend fun reloadStream(
        positionSeconds: Long,
        requestedBitrate: Long?,
        reason: String,
        armWatchdog: Boolean = false,
        playWhenReady: Boolean = true,
    ) {
        // The coordinator reports a terminal position while replacing the Jellyfin session. Record the
        // requested resume point first so a seek, bitrate switch, or recovery cannot save stale progress.
        noteJellyfinPosition(positionSeconds)
        val wasReloading = isReloadingStream
        isReloadingStream = true
        try {
            // Revoke the old phone-hosted stream before any renderer control call can block. The
            // coordinator then clears its session bookkeeping and performs bounded remote cleanup.
            proxy.stop()
            // Tell the target we're about to tear down the current stream so a polled controller (DLNA)
            // stops its poll BEFORE the renderer is forced to STOP — otherwise the old poll can emit a
            // stale end-of-media that survives the reload window and stops the new stream.
            runCatching { target?.prepareReload() }
            runCatching { coordinator.stop(reason) }
            resolveAndLoad(
                positionSeconds,
                requestedBitrate = requestedBitrate,
                initialiseAdaptive = false,
                armWatchdog = armWatchdog,
                playWhenReady = playWhenReady,
            )
        } finally {
            isReloadingStream = wasReloading
        }
    }

    /**
     * Launch the off-mutex reload used by both recovery paths (network retry / transcode fallback). The
     * caller has already set the flags ([isReloadingStream] plus [retriedOnce] or [forceTranscodeFallback])
     * synchronously; this only performs the reload from the current position and surfaces a terminal error
     * if even the reload fails. Tracked in [fallbackJob] so teardown cancels it (it can't orphan onto a
     * later session because it re-checks state under the mutex).
     */
    private fun launchRecoveryReload(requestedBitrate: Long?, reason: String) {
        if (fallbackJob?.isActive == true) return // one automatic recovery coroutine at a time
        val resumeAt = absolutePositionSeconds
        val expectedGeneration = recoverySession.generation
        fallbackJob = scope.launch {
            mutex.withLock {
                if (!recoverySession.acceptsEvent(expectedGeneration)) {
                    logger.trace(TAG, "Ignoring stale recovery reload for generation $expectedGeneration")
                    return@withLock
                }
                runCatching { reloadStream(resumeAt, requestedBitrate, reason, armWatchdog = true) }
                    .onFailure {
                        if (it !is CancellationException) {
                            logger.e("playback", "Recovery reload failed ($reason); giving up", it)
                            recoverySession = if (it.isPreparationFailure()) {
                                recoverySession
                                    .recordFailure(
                                        PlaybackFailureStage.STREAM_RESOLUTION,
                                        PlaybackFailureCause.UPSTREAM_OR_SERVER_UNAVAILABLE,
                                    )
                                    .fail()
                            } else {
                                recoverySession.fail()
                            }
                            smartResume.checkpoint(SmartResumeCheckpointKind.FAILURE, smartResumeDeviceContext)
                            publishStatus(error = "Couldn't start playback.")
                        }
                    }
            }
        }
    }

    /** Reserve automatic work after the duplicate-event guard has claimed the reload window. */
    private fun admitRecoveryDecision(action: RecoveryAction): RecoveryAction {
        val kind = when (action) {
            RecoveryAction.RETRY_SAME_STREAM -> RecoveryAttemptKind.SAME_STREAM_NETWORK
            RecoveryAction.TRANSCODE_FALLBACK -> RecoveryAttemptKind.FORMAT_COMPATIBILITY
            RecoveryAction.LOWER_RESOLUTION_FALLBACK -> RecoveryAttemptKind.LOWER_RESOLUTION
            else -> null
        } ?: run {
            if (action == RecoveryAction.SURFACE) recoverySession = recoverySession.fail()
            return action
        }
        val phase = when (kind) {
            RecoveryAttemptKind.SAME_STREAM_NETWORK -> PlaybackPhase.RECONNECTING
            RecoveryAttemptKind.FORMAT_COMPATIBILITY,
            RecoveryAttemptKind.LOWER_RESOLUTION -> PlaybackPhase.CHANGING_STREAM
            RecoveryAttemptKind.ALTERNATE_PROTOCOL -> PlaybackPhase.CHANGING_PROTOCOL
        }
        val reserved = recoverySession.reserveRecovery(kind, phase)
        if (reserved != null) {
            recoverySession = reserved
            pendingRecoveryKind = kind
            return action
        }
        // The existing failure handler set isReloadingStream before admission. Undo that claim when the
        // finite budget rejects it so the terminal state is stable and no orphan reload can start.
        isReloadingStream = false
        terminalFailureSurfaced = true
        recoverySession = recoverySession.fail()
        logger.w(TAG, "Recovery budget exhausted; surfacing playback failure")
        return RecoveryAction.SURFACE
    }
    /**
     * Central handler for a playback failure — whether a renderer-reported [PlaybackTargetEvent.Error] or
     * one synthesized by the startup watchdog. The decision + the flags that gate re-entry (so concurrent
     * signals from the event collector, the watchdog and the proxy can't double-fire a recovery) are set
     * atomically under [recoveryLock]; the actual reload runs off-lock. See [decideRecovery].
     */
    private fun handlePlaybackFailure(
        kind: PlaybackFailureKind,
        message: String,
        qualifiedFormatEvidence: Boolean = false,
        expectedGeneration: Long? = null,
        failureStage: PlaybackFailureStage = PlaybackFailureStage.RENDERER_LOAD,
    ) {
        if (expectedGeneration != null && !recoverySession.acceptsEvent(expectedGeneration)) {
            logger.trace(TAG, "Ignoring stale playback failure for generation $expectedGeneration")
            return
        }
        recoverySession = recoverySession.recordFailure(failureStage, kind.toRecoveryCause())
        // Local on-device transcode AUTO-FALLBACK: if the TV rejected this format before playback started,
        // re-encode to the next codec it can decode (and this phone can encode) before giving up. Guarded by
        // recoveryLock + isReloadingStream so the burst of duplicate error events can't each trigger a reload,
        // and by !playbackStarted so a working stream isn't disrupted by a transient mid-stream hiccup.
        val localFallbackTo: VideoCodec? = synchronized(recoveryLock) {
            if (terminalFailureSurfaced || isReloadingStream || playbackStarted) return@synchronized null
            if (localPlayback == null) return@synchronized null
            // Snapshot the volatile once: stopInternalLocked (under mutex, not recoveryLock) can null it out
            // between a separate check and a !! deref, which — on the unjoined watchdog path — would NPE.
            val odt = onDeviceTranscodeTarget ?: return@synchronized null
            localCodecFallbacksTried.add(odt.videoCodec)
            val next = localTranscodeCodecOptions.firstOrNull { it !in localCodecFallbacksTried }
                ?: return@synchronized null
            forcedLocalCodec = next
            isReloadingStream = true // enter the reload window so the dying stream's further errors are ignored
            next
        }
        if (localFallbackTo != null) {
            lastNote = "The TV couldn't play that format; trying ${localFallbackTo.name.lowercase()}…"
            logger.event(
                "playback",
                "On-device transcode ($kind) rejected by the TV; auto-falling back to codec ${localFallbackTo.name.lowercase()}",
            )
            val resumeAt = absolutePositionSeconds
            fallbackJob = scope.launch {
                mutex.withLock {
                    runCatching { reloadLocalInPlace(resumeAt, "local codec fallback") }
                        .onFailure {
                            if (it !is CancellationException) {
                                logger.e("playback", "On-device codec fallback failed; giving up", it)
                                terminalFailureSurfaced = true
                                recoverySession = recoverySession
                                    .recordFailure(PlaybackFailureStage.RENDERER_LOAD, PlaybackFailureCause.ON_DEVICE_TRANSCODE)
                                    .fail()
                                smartResume.checkpoint(SmartResumeCheckpointKind.FAILURE, smartResumeDeviceContext)
                                publishStatus(error = "Couldn't play this video on your TV.")
                            }
                        }
                }
            }
            return
        }
        val online = currentInfo != null
        val transcoding = currentIsTranscoding || effectiveForceTranscode()
        val decidedAction = synchronized(recoveryLock) {
            // A single failure often arrives as multiple events (Cast fires onMediaError twice plus an
            // IDLE/ERROR status); once we've surfaced a terminal failure for this stream, drop the extras.
            if (terminalFailureSurfaced) {
                logger.trace(TAG, "Ignoring duplicate playback error (terminal failure already surfaced)")
                return
            }
            val decided = decideRecovery(
                failure = kind,
                isReloading = isReloadingStream,
                isOnlineSession = online,
                preferDirectPlay = prefs.preferDirectPlay,
                alreadyTranscoding = transcoding,
                alreadyRetried = retriedOnce,
                hasLowerResolutionFallback = online && transcoding && nextLowerResolution() != null,
            )
            // Enter the reload window synchronously so further errors from the dying stream are ignored.
            when (decided) {
                RecoveryAction.RETRY_SAME_STREAM -> { retriedOnce = true; isReloadingStream = true }
                RecoveryAction.TRANSCODE_FALLBACK -> { forceTranscodeFallback = true; isReloadingStream = true }
                RecoveryAction.LOWER_RESOLUTION_FALLBACK -> {
                    manualMaxVideoHeight = nextLowerResolution()
                    forceTranscodeFallback = true
                    isReloadingStream = true
                }
                RecoveryAction.SURFACE -> terminalFailureSurfaced = true
                else -> Unit
            }
            decided
        }
        val action = admitRecoveryDecision(decidedAction)
        // Always-on: attribute the recovery decision to its inputs so a shared report explains WHY a
        // failure was retried, transcoded, ignored, or surfaced (e.g. "kind=UNKNOWN ... -> SURFACE").
        logger.event(
            "playback",
            "Recovery decision: kind=$kind online=$online transcoding=$transcoding retried=$retriedOnce " +
                "reloading=$isReloadingStream -> $action",
        )
        when (action) {
            RecoveryAction.IGNORE_DURING_RELOAD ->
                logger.w(TAG, "Ignoring playback error during a stream reload")
            // A transient network blip: reload the SAME stream once (transcoding wouldn't fix connectivity).
            RecoveryAction.RETRY_SAME_STREAM -> {
                lastNote = "Connection hiccup — retrying…"
                logger.event("playback", "Renderer reported a network error; retrying the same stream")
                launchRecoveryReload(requestedBitrate = adaptive?.currentBitrateBps, reason = "network retry")
            }
            // The renderer couldn't decode the original: fall back once to a server transcode (Cast -> HLS,
            // DLNA -> progressive TS). Remember it so the next play to this renderer skips the doomed attempt.
            RecoveryAction.TRANSCODE_FALLBACK -> {
                rememberTranscodeRequirement(
                    qualifiedFormatEvidence = qualifiedFormatEvidence,
                    wasDirectPlay = online && !transcoding,
                )
                lastNote = "The TV couldn't play the original; switching to a server transcode…"
                logger.event("playback", "Direct play failed on the receiver; falling back to server transcode")
                launchRecoveryReload(requestedBitrate = adaptive?.currentBitrateBps, reason = "transcode fallback")
            }
            RecoveryAction.LOWER_RESOLUTION_FALLBACK -> {
                val height = effectiveMaxVideoHeight()
                lastNote = "That stream failed; trying a ${height}p server transcode…"
                logger.event("playback", "Server transcode failed; falling back to ${height}p")
                launchRecoveryReload(requestedBitrate = adaptive?.currentBitrateBps, reason = "resolution fallback")
            }
            RecoveryAction.SURFACE -> {
                logger.e("playback", "Unrecoverable playback failure (kind=$kind); surfaced to user")
                smartResume.checkpoint(SmartResumeCheckpointKind.FAILURE, smartResumeDeviceContext)
                publishStatus(error = message)
            }
        }
    }

    /**
     * Arm the startup watchdog (ideas 1+7) for a freshly-loaded stream: if the renderer neither reaches
     * PLAYING nor advances position within [STARTUP_GRACE_MS] — i.e. it failed *silently*, with no error
     * event — synthesize a failure so recovery still runs. Bytes-flowed distinguishes a decode/format
     * failure (transcodable) from a TV that never even fetched. Cancelled the moment playback starts.
     */
    private fun armStartupWatchdog(generation: Long = recoverySession.generation) {
        startupWatchdogJob?.cancel()
        playbackStarted = false
        startupBytesServed = 0L
        startupWatchdogJob = scope.launch {
            delay(StartupWatchdog.GRACE_MS)
            if (!recoverySession.acceptsEvent(generation)) {
                logger.trace(TAG, "Ignoring stale startup watchdog for generation $generation")
                return@launch
            }
            if (!playbackStarted && !isReloadingStream) {
                val kind = StartupWatchdog.graceTimeoutKind(startupBytesServed)
                // Capture WHY the TV never started (Cast state/idle/mediaInfo, DLNA transport state) so a
                // shared report is diagnosable without a device — the recurring "TV never plays" gap.
                val rendererStatus = runCatching { target?.diagnosticStatus() }.getOrNull().orEmpty()
                logger.event(
                    "playback",
                    "Stream didn't start within ${StartupWatchdog.GRACE_MS / 1000}s " +
                        "(${startupBytesServed / 1024} KiB served to the TV; renderer: ${rendererStatus.ifBlank { "unknown" }}); attempting recovery",
                )
                handlePlaybackFailure(
                    kind, startupFailureMessage(), expectedGeneration = generation,
                    failureStage = PlaybackFailureStage.FIRST_FRAME,
                )
            }
        }
    }

    /** An actionable message for a stream that never started, tailored to what we were doing. */
    private fun startupFailureMessage(): String = when {
        currentInfo == null && currentIsTranscoding ->
            "The TV didn't start playing the on-device transcode. Try a different maximum resolution or " +
                "format in Settings, or cast a directly-playable file."
        // A local direct-play on a DLNA renderer that never started: on-device transcoding is Cast-only, so
        // don't send the user down that dead end — the file's format simply isn't supported by this TV.
        currentInfo == null && selectedTarget?.protocol != Protocol.CAST ->
            "Your DLNA TV couldn't play this file's format. Try a directly-playable file (H.264/AAC MP4), " +
                "or cast to a Chromecast, which can transcode on the phone."
        currentInfo == null ->
            "The TV didn't start playing this file. It may not support the format — try enabling on-device " +
                "transcoding in Settings."
        else -> "The TV didn't start playing."
    }

    private fun markPlaybackStarted() {
        if (!playbackStarted) {
            playbackStarted = true
            logger.event("playback", "Playback confirmed on the TV (~${startupBytesServed / 1024} KiB served)")
            startupWatchdogJob?.cancel()
            startupWatchdogJob = null
        }
    }

    /**
     * Idea 1 fast path: the TV connected, read a little, then closed the stream *before* playback started
     * — a strong, fast "can't play this" signal (faster than the grace timeout or a poll). Only within an
     * active startup window, and only for a "read some real data then bailed" close (a tiny read is a
     * range/HEAD probe, a large read is normal buffering), so it doesn't abort healthy playback.
     */
    private fun onDownstreamClosedEarly(bytesServed: Long, durationMs: Long, completedNormally: Boolean) {
        // A fully-delivered range/segment is NOT a bail-out; and HLS is served as many per-segment
        // connections that each close on completion, so an early-close signal is meaningless there (the
        // grace timeout still catches a silent HLS failure). Only an aborted progressive read counts.
        if (completedNormally || currentIsHls || playbackStarted || isReloadingStream ||
            startupWatchdogJob?.isActive != true
        ) {
            return
        }
        if (StartupWatchdog.isEarlyRejection(bytesServed, durationMs)) {
            logger.event("playback", "TV aborted the stream early (~${bytesServed / 1024} KiB); attempting recovery")
            handlePlaybackFailure(PlaybackFailureKind.FORMAT, "The TV couldn't play the file.")
        }
    }

    private suspend fun switchBitrate(decision: AdaptiveBitrateController.Decision.ChangeBitrate) {
        if (item == null || target == null) return
        val position = absolutePositionSeconds
        logger.event("adaptive", "Bitrate ${decision.direction} -> ${decision.newBitrateBps / 1000} kbps: ${decision.reason}")
        reloadStream(position, requestedBitrate = decision.newBitrateBps, reason = "bitrate switch")
        adaptive?.noteApplied(decision.newIndex, clock())
        lastNote = "Adjusted quality: ${decision.reason}"
        publishStatus()
    }

    /** Record a position only for the exact online session that produced it. */
    private fun noteJellyfinPosition(positionSeconds: Long) {
        reportingSessionToken?.let { token -> coordinator.notePosition(positionSeconds, expectedToken = token) }
    }

    /** Queue an immediate lifecycle update; the periodic monitor remains the steady-state heartbeat. */
    private fun reportJellyfinProgressSoon() {
        val token = reportingSessionToken ?: return
        val paused = !isPlaying
        scope.launch { coordinator.reportProgress(isPaused = paused, expectedToken = token) }
    }

    private suspend fun monitorLoop() {
        while (scope.isActive) {
            delay(MONITOR_INTERVAL_MS)
            try {
                mutex.withLock { evaluateNightVolumeLocked() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.w(TAG, "Night-volume scheduler tick failed; leaving volume unchanged", e)
            }
            val a = adaptive
            if (a == null) {
                publishStatus()
                continue
            }
            val now = clock()
            reportingSessionToken?.let { token ->
                runCatching { coordinator.reportProgress(isPaused = !isPlaying, expectedToken = token) }
            }
            // A transient failure here must never silently kill adaptation for the rest of the session.
            runCatching {
                lastThroughputBps = a.averageThroughputBps(now)
                if (manualQuality) {
                    // The user pinned a quality: keep measuring the link (for display) but don't adapt.
                    lastNote = "Manual quality selected — adaptation paused"
                } else when (val decision = a.evaluate(now)) {
                    is AdaptiveBitrateController.Decision.Hold -> lastNote = decision.reason
                    is AdaptiveBitrateController.Decision.ChangeBitrate ->
                        mutex.withLock {
                            // Re-check under the lock: the user may have pinned a quality since evaluate().
                            if (item != null && !manualQuality) runCatching { switchBitrate(decision) }
                                .onFailure {
                                    if (it is CancellationException) throw it
                                    logger.w(TAG, "Bitrate switch failed; keeping current quality", it)
                                }
                        }
                }
            }.onFailure {
                if (it is CancellationException) throw it
                logger.w(TAG, "Adaptive monitor tick failed", it)
            }
            publishStatus()
        }
    }

    /** Polling is frequent, but the pure scheduler admits only sparse, reduction-only commands. */
    private suspend fun evaluateNightVolumeLocked() {
        val endpoint = selectedTarget ?: return
        val policy = runCatching(nightVolumePolicyProvider).getOrDefault(NightVolumePolicy.Off)
        val decision = NightVolumeScheduler.evaluate(
            policy,
            NightVolumeInput(
                now = Instant.ofEpochMilli(clock()),
                zoneId = ZoneId.systemDefault(),
                activePlayback = isPlaying,
                volumeSupported = endpoint.discoveryMetadata.volumeControlAvailable && volumeSynchronized,
                effectiveVolume = volume,
            ),
            nightVolumeSession,
        )
        nightVolumeSession = decision.session
        decision.commandVolume?.let { reducedVolume ->
            setVolumeLocked(reducedVolume)
            logger.event("playback", "Night volume reduced to ${(reducedVolume * 100).toInt()}%")
        }
    }

    private fun suspendNightVolumeAutomationForManualAdjustment() {
        val endpoint = selectedTarget
        val decision = NightVolumeScheduler.evaluate(
            runCatching(nightVolumePolicyProvider).getOrDefault(NightVolumePolicy.Off),
            NightVolumeInput(
                now = Instant.ofEpochMilli(clock()),
                zoneId = ZoneId.systemDefault(),
                activePlayback = target != null,
                volumeSupported = endpoint?.discoveryMetadata?.volumeControlAvailable == true && volumeSynchronized,
                effectiveVolume = volume,
                manualVolumeAdjusted = true,
            ),
            nightVolumeSession,
        )
        nightVolumeSession = decision.session
    }

    /**
     * Auto-skip the segment (intro/outro/recap) covering the current position, at most once per segment
     * per item. Non-suspending: launches the seek so [onTargetEvent] never blocks on the [mutex].
     */
    private fun maybeAutoSkip() {
        if (!prefs.autoSkipSegments) return
        val segments = mediaSegments
        if (segments.isEmpty()) return
        val idx = MediaSegmentTracker.activeIndex(segments, absolutePositionSeconds) ?: return
        if (!skippedSegments.add(idx)) return // already auto-skipped this segment
        val target = segments[idx].endSeconds
        logger.event("playback", "Auto-skipping ${segments[idx].type.name.lowercase()} to ${target}s")
        scope.launch { runCatching { seekTo(target) } }
    }

    /**
     * Skip the segment covering the current position (the "Skip intro/outro" button). Marks it skipped
     * so auto-skip won't fight the user, then seeks to its end. Safe to call off the event path.
     */
    suspend fun skipActiveSegment() {
        val segments = mediaSegments
        val idx = MediaSegmentTracker.activeIndex(segments, absolutePositionSeconds) ?: return
        skippedSegments.add(idx)
        seekTo(segments[idx].endSeconds)
    }

    private fun onTargetEvent(source: PlaybackTargetController, event: PlaybackTargetEvent) {
        if (source !== target) {
            logger.w(TAG, "Ignoring event from a replaced playback controller")
            return
        }
        if (completedGeneration == playGeneration) {
            logger.trace(TAG, "Ignoring late renderer event after end-of-media")
            return
        }
        when (event) {
            is PlaybackTargetEvent.Connected -> {
                recoverySession = recoverySession.transition(PlaybackPhase.PREPARING)
                logger.event("playback", "Renderer connected (${selectedTarget?.protocol})")
                publishStatus()
            }
            is PlaybackTargetEvent.StatusChanged -> {
                val incomingAbsolute = streamStartSeconds + event.positionSeconds
                if (SeekSettle.shouldHold(
                        seekSettleTargetSeconds, incomingAbsolute, clock(), seekSettleUntilMs, SEEK_SETTLE_TOLERANCE_SECONDS,
                    )
                ) {
                    // A status emitted before the renderer applied the seek would snap the scrubber back to
                    // the old position; keep showing the target until the renderer catches up.
                    logger.trace(TAG, "Holding seek target ${seekSettleTargetSeconds}s; ignoring stale status ${incomingAbsolute}s")
                    return
                }
                seekSettleTargetSeconds = null // no seek pending, confirmed near target, or window expired
                reportedPositionSeconds = event.positionSeconds
                isPlaying = event.isPlaying
                recoverySession = recoverySession.transition(if (event.isPlaying) PlaybackPhase.PLAYING else PlaybackPhase.PAUSED)
                // Evidence the stream actually started playing on the TV — cancels the startup watchdog.
                if (event.isPlaying || event.positionSeconds > 0) markPlaybackStarted()
                noteJellyfinPosition(absolutePositionSeconds)
                smartResume.onRendererStatus(
                    absolutePositionSeconds,
                    item?.runtimeSeconds ?: localPlayback?.runtimeSeconds,
                    event.isPlaying,
                    smartResumeDeviceContext,
                )
                maybeAutoSkip()
                publishStatus()
            }
            is PlaybackTargetEvent.BufferingChanged -> {
                isBuffering = event.isBuffering
                recoverySession = recoverySession.transition(if (event.isBuffering) PlaybackPhase.BUFFERING else if (isPlaying) PlaybackPhase.PLAYING else PlaybackPhase.PAUSED)
                if (event.isBuffering) adaptive?.recordRebuffer(clock())
                logger.trace(TAG, "Buffering ${if (event.isBuffering) "started" else "ended"} at ${absolutePositionSeconds}s")
                publishStatus()
            }
            is PlaybackTargetEvent.ControlError -> {
                controlErrorMessage = event.redactedMessage
                logger.w("playback", event.redactedMessage)
                publishStatus()
            }
            is PlaybackTargetEvent.Error -> handlePlaybackFailure(
                event.kind,
                event.redactedMessage,
                event.qualifiedFormatEvidence,
            )
            is PlaybackTargetEvent.Ended -> {
                // Ignore a "finished" that fires only because we are mid-reload (HLS seek / bitrate
                // switch) tearing down the old stream; otherwise it's a real end-of-media -> stop. This
                // is independent of the duration metadata, so a real end is honoured even when the item's
                // reported runtime overestimates the actual content length.
                if (isReloadingStream) {
                    logger.w(TAG, "Ignoring end-of-media during a stream reload (seek/bitrate switch)")
                } else {
                    // Cast may repeat its finished/idle event, and a delayed poll may still say "playing".
                    // Capture and latch this exact generation before publishing so delayed consumers cannot
                    // mistake this completion for a newer stream that has reused the same TV connection.
                    val completedPlayGeneration = playGeneration
                    completedGeneration = completedPlayGeneration
                    isPlaying = false
                    isBuffering = false
                    seekSettleTargetSeconds = null
                    recoverySession = recoverySession.transition(PlaybackPhase.COMPLETED)
                    lastNote = "Finished"
                    logger.event("playback", "End of media reached at ${absolutePositionSeconds}s")
                    noteJellyfinPosition(absolutePositionSeconds)
                    reportJellyfinProgressSoon()
                    smartResume.complete(smartResumeDeviceContext)
                    // Publish before handing off so the mini-player, notification, and media session stop
                    // advertising stale playback while a next item is being resolved.
                    publishStatus()
                    // Signal a genuine end-of-media so the ViewModel can sync the exact completed Jellyfin
                    // item and then consider autoplay (a user-stop tears down eventsJob first).
                    _endOfMedia.tryEmit(
                        PlaybackCompletion(
                            item = item,
                            isJellyfinSession = currentInfo != null,
                            generation = completedPlayGeneration,
                        ),
                    )
                    val gen = completedPlayGeneration
                    if (autoAdvance) {
                        // Keep the TV connection (+ proxy + foreground service) alive so the next episode
                        // loads WITHOUT a reconnect. The ViewModel resolves the next episode and calls
                        // playNext(); the safety timeout tears down if none arrives (last episode / failure).
                        logger.event("playback", "Holding the TV connection for a seamless autoplay-next")
                        armAutoAdvanceTimeout(gen)
                    } else {
                        // Capture the generation this end belongs to: stopIfCurrent ignores it if a newer
                        // playback session has since started (the stop job is a sibling of eventsJob, so
                        // cancelAndJoin on teardown does NOT cancel it).
                        scope.launch { stopIfCurrent(gen, "end of media") }
                    }
                }
            }
            is PlaybackTargetEvent.Stopped -> {
                isPlaying = false
                isBuffering = false
                seekSettleTargetSeconds = null
                logger.event("playback", "Renderer stopped playback at ${absolutePositionSeconds}s")
                publishStatus()
                val gen = playGeneration
                scope.launch { stopIfCurrent(gen, "renderer stopped") }
            }
            is PlaybackTargetEvent.Disconnected -> {
                isPlaying = false
                smartResume.checkpoint(SmartResumeCheckpointKind.DISCONNECTED)
                // An unexpected drop of an ACTIVE session. A deliberate user stop cancels eventsJob
                // BEFORE disconnecting the target (stopInternalLocked), so this handler never runs for a
                // user stop — only for a real, unexpected drop. Flag it so the ViewModel can auto-reconnect
                // and resume from the last reported position.
                if (currentInfo != null || localPlayback != null) {
                    connectionLost = true
                    recoverySession = recoverySession
                        .recordFailure(PlaybackFailureStage.ENDPOINT_DISCONNECT, PlaybackFailureCause.ENDPOINT_UNAVAILABLE)
                        .transition(PlaybackPhase.RECONNECTING)
                    logger.w("playback", "Renderer connection lost unexpectedly at ${absolutePositionSeconds}s; auto-reconnecting")
                }
                publishStatus()
            }
        }
    }

    /**
     * Decide whether an on-device file should be transcoded on the phone before casting (the target
     * can't decode it and there is no server to transcode), and if so the [TranscodeTarget] to use; null
     * means direct-play. The caller guards this so any probe/negotiate failure falls back to direct play.
     */
    private fun chooseClientTranscodeTarget(uri: String, contentType: String, target: DiscoveredTarget): TranscodeTarget? {
        val container = containerForMime(contentType)
        val profile = LocalMediaProbe.probe(appContext, uri, container)
        localSourceProfile = profile // remember the source format for the "what's playing" UI (may be null)
        if (profile == null) {
            logger.event("transcode", "Local media probe failed for a $container file; direct-playing as-is")
            return null
        }
        // Transformer has no receiver-aware HDR preservation or tone-mapping contract. Do not silently
        // turn an HDR/Main10 local source into an unspecified output; leave it on the direct-play path.
        if (profile.isHdr || profile.bitDepth > 8) {
            logger.event(
                "transcode",
                "Local ${profile.videoCodec}/$container is HDR or >8-bit; on-device transcoding is disabled until color conversion is explicit",
            )
            return null
        }
        val decision = streamSelection.select(target.capabilities, profile, StreamPreferences())
        val route = playbackRouter.route(
            decision,
            SourceCapabilities(
                canServerTranscode = false,
                isSeekable = true,
                isReopenable = true,
                canStreamToClientTranscoder = true,
            ),
        )
        if (route.kind != RouteKind.CLIENT_TRANSCODE) {
            logger.event("transcode", "Local ${profile.videoCodec}/$container is direct-playable to this TV (${route.kind}); no on-device transcode")
            return null
        }
        // The on-device transcoder serves a phone-hosted HLS/CMAF origin, which Cast plays but a baseline
        // DLNA renderer cannot. Attempting it on DLNA yields an HLS-over-DLNA hang / UPnP 701, so don't:
        // direct-play the original instead (it may fail, but with a clear reason — see startupFailureMessage).
        if (target.protocol != Protocol.CAST) {
            logger.event(
                "transcode",
                "Local ${profile.videoCodec}/$container needs a transcode but ${target.displayName} is a " +
                    "${target.protocol} renderer, which can't play the phone's HLS on-device transcode; " +
                    "attempting direct play (on-device transcoding currently supports Chromecast)",
            )
            return null
        }
        // Codecs the TV can decode AND this phone can encode, for the manual picker. A selected codec
        // is fed back through the negotiator rather than copied onto an automatic tier: H.264 and HEVC
        // often have different phone/renderer height ceilings.
        val options = localTranscodeCodecOptions(target.capabilities)
        localTranscodeCodecOptions = options
        val forced = forcedLocalCodec?.takeIf { it in options }
        val chosen = transcodeNegotiator.negotiate(
            deviceEncodeCaps,
            receiverCapsFor(target.capabilities),
            sourceMaxResolution = cappedSourceTier(profile.heightPx),
            preferredCodec = forced,
        )
        logger.event(
            "transcode",
            "On-device transcode chosen for local ${profile.videoCodec}/$container -> " +
                "${chosen.videoCodec} ${chosen.maxResolution.maxHeightPx}p" +
                (forced?.let { " (codec pinned)" } ?: " (auto)") +
                " (${decision.playMethod}); ${deviceEncodeCapsSummary()}",
        )
        return chosen
    }

    /**
     * Codecs the active phone fMP4 pipeline can request and package. AV1/VP9 capability discovery remains
     * useful for server/direct play, but must not become a selectable Transformer target until qualified.
     */
    private fun localTranscodeCodecOptions(caps: TargetCapabilities): List<VideoCodec> {
        val tv = caps.supportedVideoCodecs.map { it.lowercase() }.toSet()
        val enc = deviceEncodeCaps
        return buildList {
            if ((caps.supportsHevc || "hevc" in tv || "h265" in tv) && enc.hevcMaxResolution != null) add(VideoCodec.HEVC)
            if (enc.h264MaxResolution != null) add(VideoCodec.H264)
        }
    }

    /** Concise summary of the local H.264/HEVC hardware encoders used for admission decisions. */
    private fun deviceEncodeCapsSummary(): String {
        val c = deviceEncodeCaps
        fun t(r: com.adsamcik.streamferry.core.transcode.ResolutionTier?) = r?.let { "${it.maxHeightPx}p" } ?: "no"
        return "hardware encoders h264=${t(c.h264MaxResolution)} hevc=${t(c.hevcMaxResolution)} @${c.maxFps}fps"
    }

    private fun containerForMime(mime: String): String = when (mime.lowercase().substringBefore(';').trim()) {
        "video/mp4" -> "mp4"
        "video/x-matroska" -> "mkv"
        "video/webm" -> "webm"
        "video/quicktime" -> "mov"
        "video/x-msvideo", "video/avi" -> "avi"
        "video/mp2t" -> "ts"
        "video/3gpp" -> "3gp"
        else -> "mp4"
    }

    /** Video codecs the TV can accept for a server transcode, best-first (always incl. the H.264 floor). */
    private fun tvVideoCodecs(caps: TargetCapabilities): List<String> {
        val tv = caps.supportedVideoCodecs.map { it.lowercase() }.toSet()
        return buildList {
            if ("av1" in tv) add("av1")
            if ("vp9" in tv) add("vp9")
            if (caps.supportsHevc || "hevc" in tv || "h265" in tv) add("hevc")
            add("h264")
        }
    }

    private fun receiverCapsFor(caps: TargetCapabilities): ReceiverPlaybackCapabilities {
        val codecs = caps.supportedVideoCodecs.map { it.lowercase() }.toSet()
        fun has(vararg names: String) = names.any { it in codecs }
        return ReceiverPlaybackCapabilities(
            // H.264 is the universal fallback but 4K H.264 is essentially never HW-decodable — cap at 1080p.
            h264MaxResolution = ResolutionTier.FHD_1080P,
            hevcMaxResolution = if (caps.supportsHevc || has("hevc", "h265")) ResolutionTier.UHD_4K else null,
            vp9MaxResolution = if (has("vp9")) ResolutionTier.UHD_4K else null,
            av1MaxResolution = if (has("av1")) ResolutionTier.UHD_4K else null,
            tenBit = caps.supports10Bit,
            supportsFmp4 = true,
            supportsTs = true,
        )
    }

    private fun resolutionTierFor(heightPx: Int?): ResolutionTier = when {
        heightPx == null -> ResolutionTier.UHD_4K
        heightPx >= 2000 -> ResolutionTier.UHD_4K
        heightPx >= 1000 -> ResolutionTier.FHD_1080P
        heightPx >= 700 -> ResolutionTier.HD_720P
        else -> ResolutionTier.SD_480P
    }

    /** The source resolution tier, capped by the user's max-resolution setting ([prefs].maxVideoHeight). */
    private fun cappedSourceTier(sourceHeightPx: Int?): ResolutionTier {
        val source = resolutionTierFor(sourceHeightPx)
        val cap = resolutionTierFor(prefs.maxVideoHeight.takeIf { it > 0 })
        return if (source.maxHeightPx <= cap.maxHeightPx) source else cap
    }

    private suspend fun stopInternalLocked(reason: String) {
        recoverySession = recoverySession.stop().let { stopped ->
            if (reason.endsWith("failed")) stopped.fail() else stopped
        }
        pendingRecoveryKind = null
        smartResume.checkpoint(SmartResumeCheckpointKind.STOPPED)
        monitorJob?.cancel(); monitorJob = null
        startupWatchdogJob?.cancel(); startupWatchdogJob = null
        autoAdvanceTimeoutJob?.cancel(); autoAdvanceTimeoutJob = null
        autoAdvance = false
        completedGeneration = null
        // cancelAndJoin (not cancel): wait for the event collector to fully stop, so it can't write a new
        // fallbackJob AFTER we clear it below and leak an orphan reload onto the next play session.
        runCatching { eventsJob?.cancelAndJoin() }; eventsJob = null
        fallbackJob?.cancel(); fallbackJob = null
        proxy.byteListener = null
        proxy.onDownstreamClosed = null
        // Close the local relay before any renderer/Jellyfin RPC can block. coordinator.stop() repeats
        // this idempotently while it clears its bookkeeping and performs bounded remote cleanup.
        proxy.stop()
        target?.let {
            runCatching { it.stop() }
            runCatching { it.disconnect() }
        }
        runCatching { coordinator.stop(reason) }
        reportingSessionToken = null
        runCatching { serviceController.stop() }
        target = null
        selectedTarget = null
        item = null
        adaptive = null
        mediaSegments = emptyList()
        skippedSegments.clear()
        currentInfo = null
        currentIsHls = false
        currentIsTranscoding = false
        onDeviceTranscodeTarget = null
        localSourceProfile = null
        localPlayback = null
        forcedLocalCodec = null
        localTranscodeCodecOptions = emptyList()
        localCodecFallbacksTried.clear()
        isPlaying = false
        isBuffering = false
        isReloadingStream = false
        forceTranscodeFallback = false
        retriedOnce = false
        terminalFailureSurfaced = false
        playbackStarted = false
        startupBytesServed = 0
        connectionLost = false
        controlErrorMessage = null
        streamStartSeconds = 0
        reportedPositionSeconds = 0
        seekSettleTargetSeconds = null
        manualQuality = false
        manualMaxVideoHeight = null
        lastThroughputBps = 0
        nightVolumeSession = NightVolumeSession()
        rendererVolume = RendererVolumeState()
        _status.value = null
        smartResume.detach()
        smartResumeDeviceContext = null
    }

    /** "H.264 · MKV · 1080p" style summary of the source media, or null when nothing is known. */
    private fun describeSource(p: MediaProfile?): String? {
        if (p == null) return null
        return listOfNotNull(
            p.videoCodec.takeIf { it.isNotBlank() }?.let { codecDisplayName(it) },
            p.container.takeIf { it.isNotBlank() }?.uppercase(),
            p.heightPx?.takeIf { it > 0 }?.let { "${it}p" },
        ).joinToString(" · ").ifBlank { null }
    }

    /** "H.264 · 720p · on device" style summary of the transcode OUTPUT, or null for direct play. */
    private fun describeOutput(t: TranscodeTarget?): String? {
        if (t != null) return "${codecDisplayName(t.videoCodec.name)} · ${t.maxResolution.maxHeightPx}p · on device"
        if (!currentIsTranscoding) return null // direct play
        // A server (Jellyfin) transcode: we know the requested codec only when the user pinned one; the
        // source profile is NOT the output, so show "auto" rather than mislabel the source codec.
        val codec = preferredCodec?.let { codecDisplayName(it) } ?: "auto"
        return "$codec · ${if (currentIsHls) "HLS" else "progressive"} · server"
    }

    private fun codecDisplayName(codec: String): String = when (codec.lowercase()) {
        "h264", "avc", "avc1" -> "H.264"
        "hevc", "h265", "hvc1" -> "HEVC"
        "vp9", "vp09" -> "VP9"
        "av1", "av01" -> "AV1"
        else -> codec.uppercase()
    }

    private fun publishStatus(error: String? = null) {
        val sel = selectedTarget ?: return
        val a = adaptive
        val srcProfile = currentInfo?.profile ?: localSourceProfile
        val odt = onDeviceTranscodeTarget
        val recovery = recoverySession
        _status.value = PlaybackStatus(
            targetName = sel.displayName,
            protocolName = sel.protocol.name,
            isPlaying = isPlaying,
            isBuffering = isBuffering,
            positionSeconds = absolutePositionSeconds,
            // For an online session the duration comes from PlaybackInfo; a local session has no
            // PlaybackInfo, so fall back to the item runtime (probed for on-device files).
            durationSeconds = currentInfo?.runtimeSeconds ?: item?.runtimeSeconds,
            streamMode = when {
                odt != null -> "On-device transcode"
                !currentIsTranscoding -> "Direct play"
                currentIsHls -> "Server transcode (HLS)"
                else -> "Server transcode"
            },
            currentBitrateBps = a?.currentBitrateBps ?: 0L,
            measuredThroughputBps = lastThroughputBps,
            adaptiveNote = lastNote,
            availableBitratesBps = a?.ladder ?: emptyList(),
            isManualQuality = manualQuality,
            // Codec picker: an online session offers the TV's decodable codecs (server transcodes to the
            // pick); a local on-device transcode offers the TV∩phone-encodable codecs (the phone re-encodes).
            availableVideoCodecs = when {
                currentInfo != null -> tvVideoCodecs(sel.capabilities)
                odt != null -> localTranscodeCodecOptions.map { it.name.lowercase() }
                else -> emptyList()
            },
            preferredVideoCodec = if (currentInfo != null) preferredCodec else forcedLocalCodec?.name?.lowercase(),
            automaticMaxVideoHeight = currentInfo?.let { prefs.maxVideoHeight },
            maxVideoHeight = currentInfo?.let { effectiveMaxVideoHeight() },
            isManualMaxVideoHeight = manualMaxVideoHeight != null,
            videoWidth = srcProfile?.widthPx,
            videoHeight = srcProfile?.heightPx,
            videoBitrateBps = srcProfile?.videoBitrateBps ?: currentInfo?.sourceBitrateBps,
            sourceFormat = describeSource(srcProfile),
            outputFormat = describeOutput(odt),
            volume = volume,
            volumeSupported = sel.discoveryMetadata.volumeControlAvailable && volumeSynchronized,
            title = item?.title ?: localPlayback?.title.orEmpty(),
            errorMessage = error ?: controlErrorMessage,
            phase = recovery.phase,
            attemptGeneration = recovery.generation,
            attemptHistory = recovery.attempts,
            recoveryBudget = recovery.budgetStatus,
            isTerminal = recovery.phase == PlaybackPhase.FAILED,
            connectionLost = connectionLost,
            audioTracks = mediaAudioTracks,
            subtitleTracks = mediaSubtitleTracks,
            // When the user hasn't explicitly chosen audio, reflect the server default so the picker shows
            // the track that is actually playing.
            currentAudioIndex = audioSelection ?: defaultAudioIndex(),
            currentSubtitleIndex = subtitleSelection,
            // Surface a skippable segment covering the current position for the manual "Skip" button —
            // but only one not yet auto-skipped, so the button doesn't flash for a segment we just skipped.
            skipSegment = activeSkipSegment(),
        )
    }

    /** The skip-button descriptor for the segment at the current position, or null if none/already skipped. */
    private fun activeSkipSegment(): SkipSegment? {
        val segments = mediaSegments
        if (segments.isEmpty()) return null
        val idx = MediaSegmentTracker.activeIndex(segments, absolutePositionSeconds) ?: return null
        if (idx in skippedSegments) return null
        return SkipSegment(segments[idx].type.label, segments[idx].endSeconds)
    }

    companion object {
        private const val TAG = "PlaybackEngine"
        private const val MONITOR_INTERVAL_MS = 5_000L
        // How long end-of-media keeps the TV connection alive waiting for a seamless autoplay-next before
        // giving up and tearing down (covers the next-episode lookup + stream resolve on a slow link).
        private const val AUTO_ADVANCE_HOLD_MS = 20_000L
        private const val VOLUME_STEP = 0.05f
        // After a seek the target position is shown immediately; hold it (ignoring contradicting status
        // reports from a poll that was in flight when the seek was issued) until the renderer reports within
        // the tolerance, or this window elapses — whichever comes first.
        private const val SEEK_SETTLE_WINDOW_MS = 4_000L
        private const val SEEK_SETTLE_TOLERANCE_SECONDS = 5L
        // Request cap used for a 4K HDR passthrough direct-play attempt — high enough to cover UHD BD-remux
        // bitrates (~100 Mbps) so the server direct-plays the original instead of transcoding to fit.
        private const val HDR_PASSTHROUGH_MAX_BITRATE_BPS = 120_000_000L

        /**
         * Broad, optimistic Cast capabilities for the first (direct-play) attempt — the common formats a
         * modern Cast device / TV-with-Chromecast-built-in can usually decode. Advertising these lets
         * Jellyfin direct-play or lightly remux instead of always transcoding; if the receiver can't
         * actually decode the stream, the engine falls back once to a safe server transcode.
         */
        private val CAST_DIRECT_PLAY_CAPS = TargetCapabilities(
            protocol = Protocol.CAST,
            supportedContainers = setOf("mp4", "m4v", "mkv", "webm", "mov", "ts"),
            supportedVideoCodecs = setOf("h264", "hevc", "h265", "vp8", "vp9", "av1", "mpeg4"),
            supportedAudioCodecs = setOf("aac", "mp3", "ac3", "eac3", "opus", "flac", "vorbis"),
            supportsHevc = true,
            supports10Bit = true,
            supportsHls = true,
            supportedExternalSubtitleFormats = setOf("vtt"),
        )
    }
}
