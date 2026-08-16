package com.adsamcik.streamferry.app

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import coil.ImageLoader
import coil.request.CachePolicy
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.tasks.Task
import com.adsamcik.streamferry.core.session.SessionRegistry
import com.adsamcik.streamferry.data.cache.CachingMediaLibraryRepository
import com.adsamcik.streamferry.data.cache.JellyfinConnectionMonitor
import com.adsamcik.streamferry.data.cache.LibraryCache
import com.adsamcik.streamferry.data.cast.CastTargetController
import com.adsamcik.streamferry.data.dlna.DlnaTargetController
import com.adsamcik.streamferry.data.download.DownloadService
import com.adsamcik.streamferry.data.download.DownloadedJellyfinMediaLibraryRepository
import com.adsamcik.streamferry.data.download.DownloadOwner
import com.adsamcik.streamferry.data.download.DownloadQueueStore
import com.adsamcik.streamferry.data.download.DownloadStore
import com.adsamcik.streamferry.data.download.MediaDownloader
import com.adsamcik.streamferry.data.jellyfin.HttpJellyfinRepository
import com.adsamcik.streamferry.data.jellyfin.JellyfinAuthRepository
import com.adsamcik.streamferry.data.jellyfin.JellyfinClient
import com.adsamcik.streamferry.data.jellyfin.JellyfinMediaLibraryRepository
import com.adsamcik.streamferry.data.jellyfin.JellyfinMediaSource
import com.adsamcik.streamferry.data.jellyfin.JellyfinPlaybackProvider
import com.adsamcik.streamferry.data.jellyfin.JellyfinSourceBackend
import com.adsamcik.streamferry.data.jellyfin.JellyfinWatchMutationStore
import com.adsamcik.streamferry.data.language.ShowLanguageStore
import com.adsamcik.streamferry.data.local.LocalMediaSource
import com.adsamcik.streamferry.data.local.LocalSourceBackend
import com.adsamcik.streamferry.data.local.LocalSourceStore
import com.adsamcik.streamferry.data.resume.ResumeStore
import com.adsamcik.streamferry.data.resume.SmartResumeStore
import com.adsamcik.streamferry.core.resume.SmartResumeSessionTracker
import com.adsamcik.streamferry.data.transcode.MediaCodecCapabilityProbe
import com.adsamcik.streamferry.data.volume.NightVolumeSettingsStore
import com.adsamcik.streamferry.data.transcode.OnDeviceTranscoder
import com.adsamcik.streamferry.data.proxy.LocalProxyServer
import com.adsamcik.streamferry.data.security.KeystoreTokenStore
import com.adsamcik.streamferry.data.security.ServerConfigStore
import com.adsamcik.streamferry.diagnostics.CrashReporter
import com.adsamcik.streamferry.diagnostics.DiagnosticsEventLog
import com.adsamcik.streamferry.diagnostics.DiagnosticsPreferences
import com.adsamcik.streamferry.diagnostics.NetworkInfoProvider
import com.adsamcik.streamferry.diagnostics.ReportShare
import com.adsamcik.streamferry.domain.JellyfinLibraryScope
import com.adsamcik.streamferry.domain.MediaLibraryRepository
import com.adsamcik.streamferry.domain.MediaSource
import com.adsamcik.streamferry.domain.SecureTokenStore
import com.adsamcik.streamferry.logging.DiagnosticsLogger
import com.adsamcik.streamferry.permissions.AndroidNetworkPermissionManager
import com.adsamcik.streamferry.permissions.LocalNetworkAccessGate
import com.adsamcik.streamferry.physical.PersistentPhysicalTvAssociationStore
import com.adsamcik.streamferry.physical.PhysicalTvAssociationStore
import com.adsamcik.streamferry.playback.AndroidPlaybackServiceController
import com.adsamcik.streamferry.playback.MediaSessionController
import com.adsamcik.streamferry.playback.PlaybackEngine
import com.adsamcik.streamferry.playback.PlaybackPreferences
import com.adsamcik.streamferry.playback.PersistentRendererCapabilityStore
import com.adsamcik.streamferry.playback.RendererCapabilityStore
import com.adsamcik.streamferry.playback.reporting.DefaultJellyfinPlaybackReporter
import com.adsamcik.streamferry.playback.session.DefaultPlaybackSessionCoordinator
import com.adsamcik.streamferry.ui.theme.AppearancePreferences
import com.adsamcik.streamferry.source.api.SourceRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import kotlin.coroutines.resume
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Minimal manual dependency container (no DI framework, §14). Holds process-scoped singletons that
 * are cheap to construct; heavier per-session objects are built on demand. Wires the full connect →
 * browse → cast/DLNA → adaptive-playback graph.
 */
class AppContainer(context: Context, val logger: DiagnosticsLogger, val crashReporter: CrashReporter) {

    private val appContext = context.applicationContext

    // ----- infrastructure -----
    val sessionRegistry: SessionRegistry by lazy { SessionRegistry() }
    val tokenStore: SecureTokenStore by lazy { KeystoreTokenStore(appContext) }
    val serverConfigStore: ServerConfigStore by lazy { ServerConfigStore(appContext) }
    val networkInfo: NetworkInfoProvider by lazy { NetworkInfoProvider(appContext) }
    val permissions: AndroidNetworkPermissionManager by lazy { AndroidNetworkPermissionManager(appContext) }
    val localNetworkGate: LocalNetworkAccessGate by lazy { LocalNetworkAccessGate(permissions) }
    val proxyServer: LocalProxyServer by lazy {
        LocalProxyServer(
            sessionRegistry,
            logger,
            contentResolver = appContext.contentResolver,
            requireLocalNetworkAccess = localNetworkGate::requireAccess,
        )
    }
    val diagnosticsPreferences: DiagnosticsPreferences by lazy { DiagnosticsPreferences(appContext) }
    val playbackPreferences: PlaybackPreferences by lazy { PlaybackPreferences(appContext) }
    val appearancePreferences: AppearancePreferences by lazy { AppearancePreferences(appContext) }
    val showLanguageStore: ShowLanguageStore by lazy { ShowLanguageStore(appContext) }
    val nightVolumeSettingsStore: NightVolumeSettingsStore by lazy { NightVolumeSettingsStore(appContext) }
    val physicalTvAssociations: PhysicalTvAssociationStore by lazy {
        PersistentPhysicalTvAssociationStore(appContext)
    }

    /**
     * Persists the redacted event log to disk so a shared diagnostics report survives app restarts (the
     * logger's in-memory ring is wiped on process death). Build-tagged; the export includes only the
     * current build's events. See [DiagnosticsEventLog].
     */
    val diagnosticsEventLog: DiagnosticsEventLog by lazy {
        DiagnosticsEventLog(appContext.filesDir, appVersionCode()) { logger.entries() }
    }

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private val deviceId: String by lazy {
        val prefs = appContext.getSharedPreferences(DEVICE_PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_DEVICE_ID, null) ?: UUID.randomUUID().toString()
            .also { prefs.edit().putString(KEY_DEVICE_ID, it).apply() }
    }

    // ----- Jellyfin -----
    val jellyfinClient: JellyfinClient by lazy {
        JellyfinClient(
            httpClient = httpClient,
            deviceId = deviceId,
            deviceName = Build.MODEL ?: "Android",
            appVersion = appVersion(),
            logger = logger,
        )
    }
    val authRepository: JellyfinAuthRepository by lazy {
        JellyfinAuthRepository(jellyfinClient, tokenStore, serverConfigStore, logger)
    }
    /** Reachability is intentionally separate from authentication so cache-only browsing can be explicit. */
    val jellyfinConnectionMonitor: JellyfinConnectionMonitor by lazy { JellyfinConnectionMonitor() }

    // ----- in-app poster images (Coil) -----
    /**
     * Coil image loader for in-app posters/thumbnails (gallery + detail). Uses a dedicated OkHttp client
     * that injects the Jellyfin Authorization header ONLY for image requests to the configured server
     * origin. Redirects are disabled here, so an image request can never carry that header elsewhere.
     * The token stays in a header, never embedded in the URL (hence never in a cache key or a
     * log). Both memory and disk caching are disabled: a tokenless image URL alone does not distinguish
     * two accounts on the same server, and a late in-flight response must not repopulate another account's
     * cache. Posters are shown on THIS phone only and are never given to the TV.
     */
    val imageLoader: ImageLoader by lazy {
        val imageClient = httpClient.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .addInterceptor { chain ->
                val req = chain.request()
                val header = jellyfinClient.imageAuthHeader()
                val authed = if (header != null && jellyfinClient.isTrustedServerUrl(req.url)) {
                    req.newBuilder().header("Authorization", header).build()
                } else {
                    req
                }
                chain.proceed(authed)
            }
            .build()
        ImageLoader.Builder(appContext)
            .okHttpClient(imageClient)
            .memoryCachePolicy(CachePolicy.DISABLED)
            .diskCachePolicy(CachePolicy.DISABLED)
            .crossfade(true)
            .build()
    }
    val mediaRepository: MediaLibraryRepository by lazy {
        DownloadedJellyfinMediaLibraryRepository(
            delegate = CachingMediaLibraryRepository(
                delegate = JellyfinMediaLibraryRepository(jellyfinClient, logger),
                cache = libraryCache,
                // Library/resume data is account-specific. A tokenless cached session has the same stable
                // scope but never enables a remote request; it only unlocks that account's local metadata.
                scope = {
                    (authRepository.currentUser.value ?: authRepository.cachedSession.value)
                        ?.let { JellyfinLibraryScope(it.serverId, it.userId).cacheKey }
                        ?: "unauthenticated"
                },
                // A cached session intentionally has no token installed. It may read disk metadata but
                // never cause the Jellyfin repository to issue a request until verification succeeds.
                isLiveSession = { authRepository.currentUser.value != null },
                connectionMonitor = jellyfinConnectionMonitor,
            ),
            downloadStore = downloadStore,
            owner = {
                (authRepository.currentUser.value ?: authRepository.cachedSession.value)
                    ?.let { DownloadOwner(it.serverId, it.userId) }
            },
        )
    }

    // ----- media sources (multi-source gallery) -----
    val localSourceStore: LocalSourceStore by lazy { LocalSourceStore(appContext) }
    val resumeStore: ResumeStore by lazy { ResumeStore(appContext) }
    val smartResumeStore: SmartResumeStore by lazy { SmartResumeStore(appContext) }
    val jellyfinWatchMutationStore: JellyfinWatchMutationStore by lazy { JellyfinWatchMutationStore(appContext) }
    val smartResumeTracker: SmartResumeSessionTracker by lazy { SmartResumeSessionTracker(smartResumeStore) }
    val jellyfinMediaSource: MediaSource by lazy { JellyfinMediaSource(mediaRepository) }
    val localMediaSource: LocalMediaSource by lazy {
        LocalMediaSource(
            appContext,
            localSourceStore,
            logger,
            hasAllMediaAccess = { permissions.hasReadMediaVideo() },
            hasSelectedMediaAccess = { permissions.hasSelectedMediaVideo() },
        )
    }
    val localSourceBackend: LocalSourceBackend by lazy { LocalSourceBackend(localMediaSource) }
    /** All browsable sources, in display order (Jellyfin first, then on-device). */
    val mediaSources: List<MediaSource> by lazy { listOf(jellyfinMediaSource, localMediaSource) }
    val libraryCache: LibraryCache by lazy { LibraryCache(appContext) }
    val downloadStore: DownloadStore by lazy { DownloadStore(appContext) }
    val downloadQueueStore: DownloadQueueStore by lazy { DownloadQueueStore(appContext) }
    private val jellyfinRepository: HttpJellyfinRepository by lazy {
        HttpJellyfinRepository(jellyfinClient, logger, httpClient)
    }
    private val reporter: DefaultJellyfinPlaybackReporter by lazy {
        DefaultJellyfinPlaybackReporter(jellyfinClient, deviceId, logger, jellyfinConnectionMonitor)
    }

    /**
     * Concrete source registration lives only in the application composition root. Rebuild the registry
     * after an account/server transition so its immutable source instance id always matches that session.
     */
    fun sourceRegistry(): SourceRegistry {
        val backends = mutableListOf<com.adsamcik.streamferry.source.api.SourceBackend>(localSourceBackend)
        (authRepository.currentUser.value ?: authRepository.cachedSession.value)?.let { session ->
            val identity = JellyfinSourceBackend.identity(
                serverId = session.serverId,
                userId = session.userId,
                displayName = "Jellyfin",
            )
            backends += JellyfinSourceBackend(
                identity = identity,
                delegate = jellyfinMediaSource,
                playback = JellyfinPlaybackProvider(
                    source = identity.id,
                    repository = jellyfinRepository,
                    reporter = reporter,
                    httpClient = httpClient,
                ),
            )
        }
        return SourceRegistry(backends)
    }
    private val coordinator: DefaultPlaybackSessionCoordinator by lazy {
        DefaultPlaybackSessionCoordinator(sessionRegistry, proxyServer, reporter, logger)
    }

    // ----- targets -----
    // CastContext's synchronous accessor is main-thread-only and may block while the Cast module loads.
    // Initialize it once from an Activity owner with the non-blocking Task API; diagnostics and background
    // work only read the cached completed value and never invoke a Cast SDK method themselves.
    @Volatile private var cachedCastContext: CastContext? = null
    private val castContextLock = Any()
    @Volatile private var castContextTask: Task<CastContext>? = null

    /** Starts asynchronous Cast initialization. Safe to call repeatedly from activity lifecycle hooks. */
    fun initializeCastContext() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Handler(Looper.getMainLooper()).post { initializeCastContext() }
            return
        }
        if (cachedCastContext != null) return
        synchronized(castContextLock) {
            if (cachedCastContext != null || castContextTask != null) return
            val task = runCatching {
                CastContext.getSharedInstance(appContext, ContextCompat.getMainExecutor(appContext))
            }.getOrElse { error ->
                logger.w("cast", "Couldn't start asynchronous Cast initialization", error)
                return
            }
            castContextTask = task
            val mainExecutor = ContextCompat.getMainExecutor(appContext)
            task.addOnSuccessListener(mainExecutor) { context ->
                cachedCastContext = context
                logger.event("cast", "Cast SDK initialized")
            }.addOnFailureListener(mainExecutor) { error ->
                synchronized(castContextLock) {
                    if (castContextTask === task) castContextTask = null
                }
                logger.w("cast", "Cast SDK is unavailable", error)
            }
        }
    }

    /**
     * Wait briefly for the activity-owned initialization task. This is used only for a user-triggered
     * discovery action; a timeout leaves Cast unavailable for this scan while DLNA remains usable.
     */
    suspend fun awaitCastContext(timeoutMillis: Long = CAST_INIT_TIMEOUT_MS): CastContext? =
        withContext(Dispatchers.Main.immediate) {
            cachedCastContext ?: run {
                initializeCastContext()
                val task = synchronized(castContextLock) { castContextTask } ?: return@run null
                withTimeoutOrNull(timeoutMillis) {
                    suspendCancellableCoroutine { continuation ->
                        val executor = ContextCompat.getMainExecutor(appContext)
                        task.addOnSuccessListener(executor) { context ->
                            if (continuation.isActive) continuation.resume(context)
                        }.addOnFailureListener(executor) {
                            if (continuation.isActive) continuation.resume(null)
                        }
                    }
                }
            }
        }

    /** Safe from any dispatcher: this only reads a completed cached value. */
    val castAvailable: Boolean get() = cachedCastContext != null
    // Pass a provider (not a captured value) so the controller always sees the current context once
    // Cast becomes available, instead of holding an early null for the whole process lifetime.
    val castController: CastTargetController by lazy { CastTargetController(appContext, { cachedCastContext }, logger) }
    val dlnaController: DlnaTargetController by lazy {
        DlnaTargetController(logger, networkInfo, httpClient, localNetworkGate::requireAccess)
    }

    // ----- on-device hardware transcoding (Media3) -----
    val onDeviceTranscoder: OnDeviceTranscoder by lazy { OnDeviceTranscoder(appContext, logger) }
    private val deviceEncodeCaps by lazy { MediaCodecCapabilityProbe.probe() }

    // ----- playback engine -----
    val rendererCapabilityStore: RendererCapabilityStore by lazy { PersistentRendererCapabilityStore(appContext) }

    val playbackEngine: PlaybackEngine by lazy {
        PlaybackEngine(
            jellyfin = jellyfinRepository,
            coordinator = coordinator,
            proxy = proxyServer,
            networkInfo = networkInfo,
            serviceController = AndroidPlaybackServiceController(appContext),
            logger = logger,
            appContext = appContext,
            onDeviceTranscoder = onDeviceTranscoder,
            deviceEncodeCapsProvider = { deviceEncodeCaps },
            rendererCaps = rendererCapabilityStore,
            smartResume = smartResumeTracker,
            nightVolumePolicyProvider = { nightVolumeSettingsStore.load() },
            requireLocalNetworkAccess = localNetworkGate::requireAccess,
        )
    }

    // ----- optional: offline downloads -----
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val downloader: MediaDownloader by lazy {
        MediaDownloader(
            jellyfin = jellyfinRepository,
            store = downloadStore,
            queue = downloadQueueStore,
            httpClient = httpClient,
            logger = logger,
            scope = ioScope,
            // JellyfinClient is a singleton mutable session. A download may make remote requests only
            // while its persisted owner is still the verified account installed in that client.
            activeOwnerProvider = {
                authRepository.currentUser.value?.let { user ->
                    DownloadOwner(serverId = user.serverId, userId = user.userId)
                }
            },
        )
    }

    /** Start the download foreground service so active downloads survive process backgrounding. */
    fun startDownloadService() { DownloadService.start(appContext) }

    // ----- media session / playback controls (notification + lock screen + media buttons) -----
    val mediaSessionController: MediaSessionController by lazy {
        val content = PendingIntent.getActivity(
            appContext, 0,
            Intent(appContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        MediaSessionController(
            appContext,
            object : MediaSessionController.Transport {
                override fun onPlay() = engineLaunch { playbackEngine.resume() }
                override fun onPause() = engineLaunch { playbackEngine.pause() }
                override fun onStop() = engineLaunch { playbackEngine.stop() }
                override fun onSeekTo(positionSeconds: Long) = engineLaunch { playbackEngine.seekTo(positionSeconds) }
                override fun onSkip(deltaSeconds: Long) = engineLaunch { playbackEngine.skip(deltaSeconds) }
                override fun onSetVolume(level: Float) = engineLaunch { playbackEngine.setVolume(level) }
                override fun onAdjustVolume(direction: Int) = engineLaunch { playbackEngine.adjustVolume(direction) }
            },
            content,
        )
    }

    private fun engineLaunch(block: suspend () -> Unit) {
        ioScope.launch {
            try {
                block()
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (e: Exception) {
                // System/notification actions have no Compose caller to surface an error, but they must
                // remain redacted and diagnosable rather than silently discarding a Cast command failure.
                logger.w("playback", "Media-session control command failed", e)
            }
        }
    }

    init {
        // Apply the persisted opt-in TV-tracing preference so detailed Cast/DLNA traffic is captured
        // from app start if the user previously enabled it.
        runCatching { logger.traceEnabled = diagnosticsPreferences.tvTracingEnabled }
        // Keep the media session + notification controls in sync with live playback. MediaSession and
        // notifications are main-thread framework objects, so observe on Main (this also creates the
        // lazy controller on the main thread).
        ioScope.launch(Dispatchers.Main) { playbackEngine.status.collect { mediaSessionController.update(it) } }
        registerDownloadAutoRecovery()
        // The public Android API exposes no process-wide permission-change listener here. Refresh the
        // cheap grant check frequently so an out-of-app revocation tears down local TV exposure promptly.
        ioScope.launch {
            while (isActive) {
                permissions.refreshLocalNetworkAccess()
                delay(LOCAL_NETWORK_PERMISSION_POLL_MS)
            }
        }
        // A grant may be revoked while a renderer is already streaming. The playback engine's local-first
        // teardown closes the proxy before attempting any remote control/reporting work.
        ioScope.launch {
            permissions.localNetworkAccess.drop(1).collect { granted ->
                if (!granted) runCatching { playbackEngine.onLocalNetworkPermissionRevoked() }
            }
        }
        // Persist the redacted event log to disk on a slow cadence so a shared report survives app
        // restarts (the in-memory ring is otherwise lost on process death). Change-detected, so it's a
        // no-op when idle; also flushed on demand before a report is built and when playback stops.
        ioScope.launch {
            while (isActive) {
                delay(DIAGNOSTICS_FLUSH_INTERVAL_MS)
                runCatching { diagnosticsEventLog.flush() }
            }
        }
    }

    /** Persist the current event log to disk now (before building a report, on playback stop, etc.). */
    fun flushDiagnostics() {
        // Off the main thread: callers include UI lifecycle (onStop) and the playback-stop path.
        ioScope.launch { runCatching { diagnosticsEventLog.flush() } }
    }

    /**
     * Save renderer-confirmed Smart Resume progress before lifecycle teardown continues. The store uses an
     * AtomicFile, so this small synchronous checkpoint is intentionally not left in a coroutine that a
     * background process kill could cancel before it runs.
     */
    fun checkpointSmartResume() {
        runCatching { playbackEngine.checkpointSmartResumeLifecycle() }
            .onFailure { logger.w("resume", "Couldn't persist lifecycle playback checkpoint", it) }
    }

    /**
     * Auto-recover downloads when connectivity returns. If a download exhausted its in-flight retries
     * while offline, re-enqueue it (resuming from its `.part` file) the moment a usable default network
     * is available again, and best-effort re-foreground the download service. Fully guarded so a
     * background foreground-service start (which the OS may reject) can never crash the app.
     */
    private fun registerDownloadAutoRecovery() {
        runCatching {
            val cm = appContext.getSystemService(ConnectivityManager::class.java) ?: return
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    ioScope.launch {
                        runCatching {
                            // A single Jellyfin client is configured for the active verified account.
                            // Keep other accounts' persisted queues dormant until that account is restored.
                            authRepository.currentUser.value?.let { user ->
                                val owner = DownloadOwner(serverId = user.serverId, userId = user.userId)
                                if (downloader.resumePending(owner)) runCatching { startDownloadService() }
                            }
                        }
                    }
                }
            })
        }
    }

    /**
     * Toggle opt-in detailed TV-communication tracing (persisted). When on, Cast/DLNA request/response
     * traffic is recorded — redacted — into the exportable diagnostics log to help trace playback
     * issues. Local only; nothing is sent anywhere.
     */
    fun setTvTracingEnabled(enabled: Boolean) {
        diagnosticsPreferences.tvTracingEnabled = enabled
        logger.traceEnabled = enabled
        logger.i("Diagnostics", if (enabled) "TV communication tracing enabled" else "TV communication tracing disabled")
    }

    /** Clears all secrets + in-memory state (Settings → Delete all app data, §13). */
    suspend fun deleteAllData() {
        runCatching { playbackEngine.stop() }
        runCatching { downloader.cancelAllAndJoin() } // stop downloads before wiping their store
        authRepository.deleteAllData()
        proxyServer.stop()
        sessionRegistry.revokeAll()
        tokenStore.clear()
        serverConfigStore.clear()
        libraryCache.clear()
        downloadStore.clear()
        runCatching { localMediaSource.clearPersistedAccess() }
            .onFailure { logger.w("privacy", "Could not fully clear local-media access") }
        runCatching { ReportShare.clearCachedReports(appContext) }
            .onFailure { logger.w("privacy", "Could not fully clear cached diagnostic reports") }
        runCatching {
            check(appContext.getSharedPreferences(DEVICE_PREFS, Context.MODE_PRIVATE).edit().clear().commit())
        }.onFailure { logger.w("privacy", "Could not clear the persisted installation identifier") }
        runCatching { resumeStore.clear() }
        runCatching { smartResumeStore.clear() }
        runCatching { jellyfinWatchMutationStore.clear() }
        runCatching { crashReporter.clear() } // crash reports are app data too
        runCatching { diagnosticsEventLog.clear() } // persisted event log is app data too
        runCatching { diagnosticsPreferences.clear() }
        runCatching { playbackPreferences.clear() }
        runCatching { appearancePreferences.clear() }
        runCatching { rendererCapabilityStore.clear() }
        runCatching { showLanguageStore.clear() }
        runCatching { nightVolumeSettingsStore.clear() }
        runCatching { physicalTvAssociations.clear() }
        logger.traceEnabled = false
        logger.clear()
    }

    fun appVersionName(): String = appVersion()
    fun appVersionCode(): String = runCatching {
        appContext.packageManager.getPackageInfo(appContext.packageName, 0).longVersionCode.toString()
    }.getOrNull() ?: "0"

    private fun appVersion(): String = runCatching {
        appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName
    }.getOrNull() ?: "0.1.0"

    private companion object {
        const val DEVICE_PREFS = "jellyfin_bridge_device"
        const val KEY_DEVICE_ID = "device_id"
        const val LOCAL_NETWORK_PERMISSION_POLL_MS = 1_000L
        const val DIAGNOSTICS_FLUSH_INTERVAL_MS = 10_000L
        const val CAST_INIT_TIMEOUT_MS = 2_000L
    }
}
