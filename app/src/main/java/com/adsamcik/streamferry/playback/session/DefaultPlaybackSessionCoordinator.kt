package com.adsamcik.streamferry.playback.session

import com.adsamcik.streamferry.core.session.ProxySession
import com.adsamcik.streamferry.core.session.SessionRegistry
import com.adsamcik.streamferry.data.proxy.ClientTranscodeSource
import com.adsamcik.streamferry.data.proxy.LocalProxyServer
import com.adsamcik.streamferry.domain.JellyfinPlaybackReporter
import com.adsamcik.streamferry.domain.PlaybackInfo
import com.adsamcik.streamferry.domain.PlaybackSessionCoordinator
import com.adsamcik.streamferry.domain.UpstreamSource
import com.adsamcik.streamferry.logging.DiagnosticsLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Ties together the three session identities (§8): Jellyfin play session ↔ phone proxy session ↔
 * Cast/DLNA session. Owns the lifecycle: start proxy + create session + report start; stop proxy +
 * revoke session + report stop + stop transcode.
 *
 * The phone proxy URL that the TV receives is the ONLY externally shared locator and is built here
 * from the LAN bind address + the high-entropy session id.
 */
class DefaultPlaybackSessionCoordinator(
    private val sessions: SessionRegistry,
    private val proxy: LocalProxyServer,
    private val reporter: JellyfinPlaybackReporter,
    private val logger: DiagnosticsLogger,
) : PlaybackSessionCoordinator {

    private val _active = MutableStateFlow<ProxySession?>(null)
    override val active = _active.asStateFlow()

    private var currentInfo: PlaybackInfo? = null
    private var lastPositionSeconds: Long = 0L
    private val mutex = Mutex()

    /** Record the latest known absolute playback position (used for progress + stop reporting). */
    fun notePosition(seconds: Long) {
        if (seconds >= 0) lastPositionSeconds = seconds
    }

    /** Report progress to Jellyfin for the active session at the last known position. */
    suspend fun reportProgress(isPaused: Boolean) {
        val info = currentInfo ?: return
        runCatching { reporter.reportProgress(info, lastPositionSeconds, isPaused) }
    }

    /** @return the phone proxy URL the TV should load (host:port + /session/{id}/stream). */
    suspend fun startAndBuildUrl(
        info: PlaybackInfo,
        upstream: UpstreamSource,
        phoneLanIp: String,
    ): Pair<ProxySession, String> = mutex.withLock {
        val hostPort = proxy.start(phoneLanIp)
        val session = sessions.create(
            upstreamUrl = upstream.url,
            upstreamAuthHeader = upstream.authHeader,
            contentType = upstream.contentType,
            playSessionId = info.playSessionId,
            isHls = upstream.isHls,
            totalLength = upstream.totalLength,
        )
        currentInfo = info
        _active.value = session
        reporter.reportStart(info)
        val url = "http://$hostPort/session/${session.id}/stream"
        logger.event("session", "Session started (online); proxy URL issued to TV (redacted)")
        session to url
    }

    override suspend fun start(info: PlaybackInfo, upstream: UpstreamSource, phoneLanIp: String): ProxySession =
        startAndBuildUrl(info, upstream, phoneLanIp).first

    /** Start a proxy session that serves an app-private downloaded file (offline playback). */
    suspend fun startLocalAndBuildUrl(
        filePath: String,
        contentType: String,
        phoneLanIp: String,
    ): Pair<ProxySession, String> = mutex.withLock {
        val hostPort = proxy.start(phoneLanIp)
        val session = sessions.create(
            upstreamUrl = "local-file",
            upstreamAuthHeader = null,
            contentType = contentType,
            playSessionId = null,
            isHls = false,
            localFilePath = filePath,
        )
        currentInfo = null
        lastPositionSeconds = 0
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
            upstreamUrl = "client-transcode",
            upstreamAuthHeader = null,
            contentType = "application/vnd.apple.mpegurl",
            playSessionId = null,
            isHls = false,
        )
        proxy.registerClientTranscode(session.id, source)
        currentInfo = null
        lastPositionSeconds = 0
        _active.value = session
        val url = "http://$hostPort/session/${session.id}/stream"
        logger.event("session", "Session started (on-device transcode); playlist URL issued to TV")
        session to url
    }

    override suspend fun stop(reason: String) = mutex.withLock {
        val info = currentInfo
        val session = _active.value
        // End renderer access first. A remote Jellyfin report can wait or fail, but it must never keep
        // an opaque proxy URL, accepted renderer socket, or upstream relay alive.
        session?.let { sessions.revoke(it.id) }
        proxy.stop()
        _active.value = null
        currentInfo = null

        if (info != null && session != null) {
            runBoundedRemoteCleanup("report stopped") { reporter.reportStopped(info, lastPositionSeconds) }
            runBoundedRemoteCleanup("stop transcode") { reporter.stopTranscode(info) }
        }
        logger.event("session", "Session stopped ($reason); local proxy revoked before remote cleanup")
    }

    private suspend fun runBoundedRemoteCleanup(label: String, block: suspend () -> Unit) {
        try {
            val completed = withTimeoutOrNull(REMOTE_CLEANUP_TIMEOUT_MS) { block(); true } ?: false
            if (!completed) logger.w(TAG, "Timed out while attempting Jellyfin $label")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.w(TAG, "Jellyfin $label failed", e)
        }
    }

    companion object {
        private const val TAG = "PlaybackSessionCoordinator"
        private const val REMOTE_CLEANUP_TIMEOUT_MS = 3_000L
    }
}
