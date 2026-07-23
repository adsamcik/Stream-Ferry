package com.videobridge.playback.session

import com.videobridge.core.session.ProxySession
import com.videobridge.core.session.SessionRegistry
import com.videobridge.data.proxy.ClientTranscodeSource
import com.videobridge.data.proxy.LocalProxyServer
import com.videobridge.domain.JellyfinPlaybackReporter
import com.videobridge.domain.PlaybackInfo
import com.videobridge.domain.PlaybackSessionCoordinator
import com.videobridge.domain.UpstreamSource
import com.videobridge.logging.DiagnosticsLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
        try {
            if (info != null && session != null) {
                runCatching { reporter.reportStopped(info, lastPositionSeconds) }
                runCatching { reporter.stopTranscode(info) } // §8 cleanup: avoid abandoned transcodes
            }
        } finally {
            session?.let { sessions.revoke(it.id) }
            proxy.stop()
            _active.value = null
            currentInfo = null
            logger.event("session", "Session stopped ($reason); proxy down, transcode cleanup attempted")
        }
    }

    companion object { private const val TAG = "PlaybackSessionCoordinator" }
}
