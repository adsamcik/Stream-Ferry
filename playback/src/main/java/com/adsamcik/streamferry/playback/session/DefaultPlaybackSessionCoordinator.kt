package com.adsamcik.streamferry.playback.session

import com.adsamcik.streamferry.core.session.ProxySession
import com.adsamcik.streamferry.core.session.SessionRegistry
import com.adsamcik.streamferry.data.proxy.ClientTranscodeSource
import com.adsamcik.streamferry.data.proxy.LocalProxyServer
import com.adsamcik.streamferry.source.api.DiagnosticSink
import com.adsamcik.streamferry.source.api.PlaybackEvent
import com.adsamcik.streamferry.source.api.PlaybackStopReason
import com.adsamcik.streamferry.source.api.ProviderPlaybackSession
import kotlinx.coroutines.CancellationException
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
) {

    private val _active = MutableStateFlow<ProxySession?>(null)
    val active = _active.asStateFlow()

    private data class ReportingSession(
        val token: Long,
        val providerSession: ProviderPlaybackSession,
        val positionSeconds: Long,
    )

    private val reportStateLock = Any()
    private var nextReportToken = 0L
    private var reportingSession: ReportingSession? = null
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
            reportingSession = active.copy(positionSeconds = seconds)
        }
    }

    /** Report progress for the exact active session; queued work from a prior item is rejected by token. */
    suspend fun reportProgress(isPaused: Boolean, expectedToken: Long? = null) = mutex.withLock {
        val active = reportingSnapshot(expectedToken) ?: return@withLock
        runBoundedRemoteCleanup("report progress") {
            active.providerSession.report(
                PlaybackEvent.Progress(active.positionSeconds, isPaused),
            )
        }
    }

    /** @return the phone proxy URL the TV should load (host:port + /session/{id}/stream). */
    suspend fun startAndBuildUrl(
        providerSession: ProviderPlaybackSession,
        phoneLanIp: String,
        initialPositionSeconds: Long = 0L,
    ): Pair<ProxySession, String> = mutex.withLock {
        val hostPort = proxy.start(phoneLanIp)
        val stream = providerSession.descriptor.stream
        val session = sessions.create(
            contentType = stream.contentType,
            isHls = stream.isHls,
            totalLength = stream.totalLength,
        )
        proxy.registerStreamLease(session.id, providerSession.upstream)
        val reporting = beginReporting(providerSession, initialPositionSeconds)
        _active.value = session
        providerSession.report(PlaybackEvent.Started(reporting.positionSeconds))
        val url = "http://$hostPort/session/${session.id}/stream"
        logger.event("session", "Session started (online); proxy URL issued to TV (redacted)")
        session to url
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

    /** Start a proxy session that serves an on-device-transcoded HLS/CMAF origin (the TV gets only the playlist URL). */
    suspend fun startClientTranscodeAndBuildUrl(
        source: ClientTranscodeSource,
        phoneLanIp: String,
    ): Pair<ProxySession, String> = mutex.withLock {
        val hostPort = proxy.start(phoneLanIp)
        val session = sessions.create(
            contentType = "application/vnd.apple.mpegurl",
            isHls = false,
        )
        proxy.registerClientTranscode(session.id, source)
        clearReportingSession()
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
        session?.let {
            sessions.revoke(it.id)
            proxy.unregisterStreamLease(it.id)
        }
        proxy.stop()
        _active.value = null

        if (reporting != null && session != null) {
            runBoundedRemoteCleanup("report stopped") {
                reporting.providerSession.report(PlaybackEvent.Stopped(reporting.positionSeconds))
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
    ): ReportingSession =
        synchronized(reportStateLock) {
            ReportingSession(
                token = ++nextReportToken,
                providerSession = providerSession,
                positionSeconds = initialPositionSeconds.coerceAtLeast(0L),
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
