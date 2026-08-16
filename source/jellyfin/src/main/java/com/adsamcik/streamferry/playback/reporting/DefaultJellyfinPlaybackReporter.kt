package com.adsamcik.streamferry.playback.reporting

import com.adsamcik.streamferry.core.resilience.UpstreamRetry
import com.adsamcik.streamferry.data.cache.JellyfinConnectionMonitor
import com.adsamcik.streamferry.data.jellyfin.HttpJellyfinRepository.Companion.TICKS_PER_SECOND
import com.adsamcik.streamferry.data.jellyfin.JellyfinApi
import com.adsamcik.streamferry.data.jellyfin.JellyfinHttpException
import com.adsamcik.streamferry.domain.JellyfinPlaybackReporter
import com.adsamcik.streamferry.domain.PlaybackInfo
import com.adsamcik.streamferry.source.api.DiagnosticSink
import java.io.IOException
import kotlinx.coroutines.CancellationException

/**
 * Reports playback lifecycle to Jellyfin and performs transcode/HLS cleanup (§8). Never invents
 * progress: callers pass real positions derived from target status; on unknown positions we report
 * the last known value rather than fabricating one.
 */
class DefaultJellyfinPlaybackReporter(
    private val api: JellyfinApi,
    private val deviceId: String,
    private val logger: DiagnosticSink,
    private val connectionMonitor: JellyfinConnectionMonitor? = null,
) : JellyfinPlaybackReporter {

    override suspend fun reportStart(info: PlaybackInfo) = reportStart(info, initialPositionSeconds = 0L)

    override suspend fun reportStart(info: PlaybackInfo, initialPositionSeconds: Long) = reportLifecycle("reportStart") {
        api.reportPlaying(
            playSessionId = info.playSessionId,
            itemId = info.itemId,
            mediaSourceId = info.mediaSourceId,
            positionTicks = ticks(initialPositionSeconds),
        )
    }

    override suspend fun reportProgress(info: PlaybackInfo, positionSeconds: Long, isPaused: Boolean) =
        reportLifecycle("reportProgress") {
            api.reportProgress(
                playSessionId = info.playSessionId,
                itemId = info.itemId,
                mediaSourceId = info.mediaSourceId,
                positionTicks = ticks(positionSeconds),
                isPaused = isPaused,
            )
        }

    override suspend fun reportStopped(info: PlaybackInfo, positionSeconds: Long) = reportLifecycle("reportStopped") {
        api.reportStopped(
            playSessionId = info.playSessionId,
            itemId = info.itemId,
            mediaSourceId = info.mediaSourceId,
            positionTicks = ticks(positionSeconds),
        )
    }

    override suspend fun stopTranscode(info: PlaybackInfo) = reportLifecycle("stopTranscode") {
        // DELETE /Videos/ActiveEncodings — only meaningful for transcode/HLS sessions; harmless
        // otherwise. Critical to avoid abandoned server-side transcodes after the user stops.
        api.stopActiveEncoding(info.playSessionId, deviceId)
    }

    private suspend fun reportLifecycle(label: String, block: suspend () -> Unit) {
        try {
            block()
            connectionMonitor?.markOnline()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (isConnectivityFailure(e)) connectionMonitor?.markUnavailable()
            logger.w(TAG, "$label failed", e)
        }
    }

    private fun ticks(positionSeconds: Long): Long = positionSeconds.coerceAtLeast(0L) * TICKS_PER_SECOND

    private fun isConnectivityFailure(error: Throwable): Boolean =
        generateSequence(error) { it.cause }.any { cause ->
            cause is IOException ||
                (cause is JellyfinHttpException && !cause.isUnauthorized && UpstreamRetry.isRetryableStatus(cause.code))
        }

    companion object { private const val TAG = "JellyfinReporter" }
}
