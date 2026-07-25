package com.adsamcik.streamferry.playback.reporting

import com.adsamcik.streamferry.data.jellyfin.HttpJellyfinRepository.Companion.TICKS_PER_SECOND
import com.adsamcik.streamferry.data.jellyfin.JellyfinApi
import com.adsamcik.streamferry.domain.JellyfinPlaybackReporter
import com.adsamcik.streamferry.domain.PlaybackInfo
import com.adsamcik.streamferry.logging.DiagnosticsLogger

/**
 * Reports playback lifecycle to Jellyfin and performs transcode/HLS cleanup (§8). Never invents
 * progress: callers pass real positions derived from target status; on unknown positions we report
 * the last known value rather than fabricating one.
 */
class DefaultJellyfinPlaybackReporter(
    private val api: JellyfinApi,
    private val deviceId: String,
    private val logger: DiagnosticsLogger,
) : JellyfinPlaybackReporter {

    override suspend fun reportStart(info: PlaybackInfo) {
        runCatching { api.reportPlaying(info.playSessionId, info.mediaSourceId) }
            .onFailure { logger.w(TAG, "reportStart failed", it) }
    }

    override suspend fun reportProgress(info: PlaybackInfo, positionSeconds: Long, isPaused: Boolean) {
        runCatching {
            api.reportProgress(info.playSessionId, info.mediaSourceId, positionSeconds * TICKS_PER_SECOND, isPaused)
        }.onFailure { logger.w(TAG, "reportProgress failed", it) }
    }

    override suspend fun reportStopped(info: PlaybackInfo, positionSeconds: Long) {
        runCatching {
            api.reportStopped(info.playSessionId, info.mediaSourceId, positionSeconds * TICKS_PER_SECOND)
        }.onFailure { logger.w(TAG, "reportStopped failed", it) }
    }

    override suspend fun stopTranscode(info: PlaybackInfo) {
        // DELETE /Videos/ActiveEncodings — only meaningful for transcode/HLS sessions; harmless
        // otherwise. Critical to avoid abandoned server-side transcodes after the user stops.
        runCatching { api.stopActiveEncoding(info.playSessionId, deviceId) }
            .onFailure { logger.w(TAG, "stopTranscode failed", it) }
    }

    companion object { private const val TAG = "JellyfinReporter" }
}
