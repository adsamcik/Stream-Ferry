package com.adsamcik.streamferry.playback.session

import com.adsamcik.streamferry.core.session.ProxySession
import com.adsamcik.streamferry.core.session.SessionRegistry
import com.adsamcik.streamferry.core.resume.ProgressEstimate
import com.adsamcik.streamferry.core.resume.ProgressConfidence
import com.adsamcik.streamferry.data.proxy.ClientTranscodeSource
import com.adsamcik.streamferry.data.proxy.LocalProxyServer
import com.adsamcik.streamferry.source.api.DiagnosticSink
import com.adsamcik.streamferry.source.api.PlaybackEvent
import com.adsamcik.streamferry.source.api.PlaybackStopReason
import com.adsamcik.streamferry.source.api.ProviderPlaybackSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Ties together the three session identities (§8): source play session ↔ phone proxy session ↔
 * Cast/DLNA session. Owns the lifecycle: start proxy + create session + report start; stop proxy +
 * revoke session + report stop + stop transcode.
 *
 * The phone proxy URL that the TV receives is the ONLY externally shared locator and is built here
 * from the LAN bind address + the high-entropy session id.
 */
class DefaultPlaybackSessionCoordinator(
    private val sessions: SessionRegistry,
    private val proxy: LocalProxyServer,
    private val logger: DiagnosticSink,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val reportingScope = CoroutineScope(SupervisorJob() + dispatcher)

    private val _active = MutableStateFlow<ProxySession?>(null)
    val active = _active.asStateFlow()

    private data class ReportingSession(
        val token: Long,
        val providerSession: ProviderPlaybackSession,
        val positionSeconds: Long,
        val shouldReport: () -> Boolean,
        val startReported: CompletableDeferred<Unit> = CompletableDeferred(),
        val estimate: ProgressEstimate? = null,
    )

    private val reportStateLock = Any()
    private var nextReportToken = 0L
    private var reportingSession: ReportingSession? = null
    /** Dedicated authenticated source endpoint retained while transformed output replaces it. */
    private var transcodeInputSession: ProxySession? = null
    private val mutex = Mutex()

    /** Opaque identity for the active remote playback report; null for local/offline streams. */
    fun activeReportToken(): Long? = synchronized(reportStateLock) { reportingSession?.token }

    /**
     * Record the latest renderer-confirmed absolute position. A stale callback may only update the session
     * whose [expectedToken] it belongs to, never a later item reusing the same TV connection.
     */
    fun notePosition(seconds: Long, expectedToken: Long? = null) {
        if (seconds < 0) return
        synchronized(reportStateLock) {
            val active = reportingSession ?: return
            if (expectedToken != null && active.token != expectedToken) return
            reportingSession = active.copy(positionSeconds = seconds, estimate = null)
        }
    }

    fun noteEstimate(estimate: ProgressEstimate?, expectedToken: Long) = synchronized(reportStateLock) {
        val active = reportingSession ?: return@synchronized
        if (active.token != expectedToken) return@synchronized
        // Expiry freezes the previously saved checkpoint; it must not rewind user data to a stale
        // confirmation on the next lifecycle heartbeat. Only new TV truth or a seek clears it.
        val eligible = estimate?.takeIf {
            it.isValid() && it.anchorPositionSeconds == active.positionSeconds &&
                it.confidence == ProgressConfidence.DELIVERY && it.anchorAgeMillis <= 120_000 &&
                it.uncertaintySeconds <= 15 && it.recoveryPositionSeconds() > active.positionSeconds
        } ?: return@synchronized
        if (eligible.recoveryPositionSeconds() >= (active.estimate?.recoveryPositionSeconds() ?: 0L)) {
            reportingSession = active.copy(estimate = eligible)
        }
    }

    fun clearEstimate(expectedToken: Long) = synchronized(reportStateLock) {
        reportingSession?.takeIf { it.token == expectedToken }?.let { reportingSession = it.copy(estimate = null) }
    }

    /** Report progress for the exact active session; queued work from a prior item is rejected by token. */
    suspend fun reportProgress(isPaused: Boolean, expectedToken: Long? = null) = mutex.withLock {
        val initial = reportingSnapshot(expectedToken) ?: return@withLock
        initial.startReported.await()
        val active = reportingSnapshot(initial.token) ?: return@withLock
        if (!active.shouldReport()) return@withLock
        runBoundedRemoteCleanup("report progress") {
            active.providerSession.report(
                PlaybackEvent.Progress(active.positionSeconds, isPaused, active.estimate),
            )
        }
    }

    /** Drain an already-started lifecycle report before a manual mutation writes server truth. */
    suspend fun awaitPendingReports() = mutex.withLock {
        reportingSnapshot(null)?.startReported?.await()
        Unit
    }

    /** @return the phone proxy URL the TV should load (host:port + /session/{id}/stream). */
    suspend fun startAndBuildUrl(
        providerSession: ProviderPlaybackSession,
        phoneLanIp: String,
        initialPositionSeconds: Long = 0L,
        shouldReport: () -> Boolean = { true },
    ): Pair<ProxySession, String> = mutex.withLock {
        val hostPort = proxy.start(phoneLanIp)
        val stream = providerSession.descriptor.stream
        val session = sessions.create(
            contentType = stream.contentType,
            isHls = stream.isHls,
            totalLength = stream.totalLength,
        )
        proxy.registerStreamLease(session.id, providerSession.upstream)
        val reporting = beginReporting(providerSession, initialPositionSeconds, shouldReport)
        _active.value = session
        // Server reporting must not delay the renderer load (a slow first request used to add
        // several seconds here). Preserve Started -> Progress -> Stopped ordering for this token.
        reportStarted(reporting)
        val url = "http://$hostPort/session/${session.id}/stream"
        logger.event("session", "Session started (online); proxy URL issued to TV (redacted)")
        session to url
    }

    /**
     * Give the phone encoder an opaque source URL using the same provider lease as direct playback.
     * Progressive ranges, authenticated resource resolution and HLS rewriting stay source-owned.
     * The input remains alive until [stop], including while a prepared output is being produced.
     */
    suspend fun prepareTranscodeInput(
        providerSession: ProviderPlaybackSession,
        phoneLanIp: String,
        initialPositionSeconds: Long = 0L,
        shouldReport: () -> Boolean = { true },
    ): String = mutex.withLock {
        val reporting = beginReporting(providerSession, initialPositionSeconds, shouldReport)
        try {
            val hostPort = proxy.start(phoneLanIp)
            val stream = providerSession.descriptor.stream
            val session = sessions.create(stream.contentType, stream.isHls, totalLength = stream.totalLength)
            transcodeInputSession = session
            _active.value = session
            proxy.registerStreamLease(session.id, providerSession.upstream, rendererOutput = false)
            reportStarted(reporting)
            val endpoint = if (stream.isHls) "playlist.m3u8" else "stream"
            "http://$hostPort/session/${session.id}/$endpoint"
        } catch (error: Throwable) {
            // Allow the caller's normal stop/error path to close the owned provider even when
            // binding or registration failed before an output session could be installed.
            reporting.startReported.complete(Unit)
            throw error
        }
    }

    private fun reportStarted(reporting: ReportingSession) {
        reportingScope.launch {
            try {
                if (reporting.shouldReport()) {
                    runBoundedRemoteCleanup("report started") {
                        reporting.providerSession.report(PlaybackEvent.Started(reporting.positionSeconds))
                    }
                }
            } finally {
                reporting.startReported.complete(Unit)
            }
        }
    }

    /** Start a proxy session that serves an app-private downloaded file (offline playback). */
    suspend fun startLocalAndBuildUrl(
        filePath: String,
        contentType: String,
        phoneLanIp: String,
    ): Pair<ProxySession, String> = mutex.withLock {
        val hostPort = proxy.start(phoneLanIp)
        val session = sessions.create(
            contentType = contentType,
            isHls = false,
            localFilePath = filePath,
        )
        clearReportingSession()
        _active.value = session
        val url = "http://$hostPort/session/${session.id}/stream"
        logger.event("session", "Session started (offline/local); proxy URL issued to TV")
        session to url
    }

    /** Publish continuous phone output, retaining source authentication and reporting for either protocol. */
    suspend fun startClientTranscodeAndBuildUrl(
        source: ClientTranscodeSource,
        phoneLanIp: String,
    ): Pair<ProxySession, String> = mutex.withLock {
        val hostPort = proxy.start(phoneLanIp)
        val session = sessions.create(
            contentType = if (source.isProgressive) "video/mp2t" else "application/vnd.apple.mpegurl",
            isHls = false,
        )
        proxy.registerClientTranscode(session.id, source)
        if (transcodeInputSession == null) clearReportingSession()
        _active.value = session
        val url = "http://$hostPort/session/${session.id}/stream"
        logger.event("session", "Session started (on-device transcode); playlist URL issued to TV")
        session to url
    }

    suspend fun stop(reason: String) = mutex.withLock {
        val reporting = takeReportingSession()
        val session = _active.value
        // End renderer access first. A remote source report can wait or fail, but it must never keep
        // an opaque proxy URL, accepted renderer socket, or upstream relay alive.
        listOfNotNull(session, transcodeInputSession).distinctBy { it.id }.forEach {
            sessions.revoke(it.id)
            proxy.unregisterStreamLease(it.id)
        }
        transcodeInputSession = null
        proxy.stop()
        _active.value = null

        if (reporting != null) {
            reporting.startReported.await()
            if (reporting.shouldReport()) {
                runBoundedRemoteCleanup("report stopped") {
                    reporting.providerSession.report(PlaybackEvent.Stopped(reporting.positionSeconds, reporting.estimate))
                }
            }
            runBoundedRemoteCleanup("close source playback") {
                reporting.providerSession.close(stopReason(reason))
            }
        }
        logger.event("session", "Session stopped ($reason); local proxy revoked before remote cleanup")
    }

    private fun beginReporting(
        providerSession: ProviderPlaybackSession,
        initialPositionSeconds: Long,
        shouldReport: () -> Boolean,
    ): ReportingSession =
        synchronized(reportStateLock) {
            ReportingSession(
                token = ++nextReportToken,
                providerSession = providerSession,
                positionSeconds = initialPositionSeconds.coerceAtLeast(0L),
                shouldReport = shouldReport,
            ).also { reportingSession = it }
        }

    private fun clearReportingSession() = synchronized(reportStateLock) {
        reportingSession = null
    }

    private fun takeReportingSession(): ReportingSession? = synchronized(reportStateLock) {
        reportingSession.also { reportingSession = null }
    }

    private fun reportingSnapshot(expectedToken: Long?): ReportingSession? = synchronized(reportStateLock) {
        reportingSession?.takeIf { expectedToken == null || it.token == expectedToken }
    }

    private suspend fun runBoundedRemoteCleanup(label: String, block: suspend () -> Unit) {
        try {
            val completed = withTimeoutOrNull(REMOTE_CLEANUP_TIMEOUT_MS) { block(); true } ?: false
            if (!completed) logger.w(TAG, "Timed out while attempting source $label")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.w(TAG, "Source $label failed", e)
        }
    }

    private fun stopReason(reason: String): PlaybackStopReason = when {
        reason.contains("complete", ignoreCase = true) -> PlaybackStopReason.COMPLETED
        reason.contains("replace", ignoreCase = true) || reason.contains("restart", ignoreCase = true) -> PlaybackStopReason.REPLACED
        reason.contains("error", ignoreCase = true) || reason.contains("fail", ignoreCase = true) -> PlaybackStopReason.ERROR
        reason.contains("shutdown", ignoreCase = true) -> PlaybackStopReason.SHUTDOWN
        else -> PlaybackStopReason.USER
    }

    companion object {
        private const val TAG = "PlaybackSessionCoordinator"
        private const val REMOTE_CLEANUP_TIMEOUT_MS = 3_000L
    }
}
