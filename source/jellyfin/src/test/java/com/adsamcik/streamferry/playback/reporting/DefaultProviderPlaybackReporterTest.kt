package com.adsamcik.streamferry.playback.reporting

import com.adsamcik.streamferry.core.stream.MediaProfile
import com.adsamcik.streamferry.data.cache.JellyfinConnectionMonitor
import com.adsamcik.streamferry.data.jellyfin.HttpJellyfinRepository.Companion.TICKS_PER_SECOND
import com.adsamcik.streamferry.data.jellyfin.DefaultJellyfinPlaybackReporter
import com.adsamcik.streamferry.data.jellyfin.JellyfinApi
import com.adsamcik.streamferry.data.jellyfin.JellyfinPlaybackInfo
import com.adsamcik.streamferry.data.jellyfin.JellyfinUpstreamSource
import com.adsamcik.streamferry.source.api.DiagnosticSink
import com.adsamcik.streamferry.domain.SourceAvailability
import java.io.IOException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class DefaultJellyfinPlaybackReporterTest {

    @Test
    fun reportsKeepTheLibraryItemAndSelectedMediaSourceDistinct() = runTest {
        val api = RecordingApi()
        val monitor = JellyfinConnectionMonitor()
        val reporter = DefaultJellyfinPlaybackReporter(api, "device", NoOpLogger, monitor)
        val info = playbackInfo()

        reporter.reportStart(info, initialPositionSeconds = 12L)
        reporter.reportProgress(info, positionSeconds = 34L, isPaused = false)
        reporter.reportStopped(info, positionSeconds = 56L)

        assertEquals(
            listOf(
                Call("start", "library-item", "source-version", 12L * TICKS_PER_SECOND, null),
                Call("progress", "library-item", "source-version", 34L * TICKS_PER_SECOND, false),
                Call("stopped", "library-item", "source-version", 56L * TICKS_PER_SECOND, null),
            ),
            api.calls,
        )
        assertEquals(SourceAvailability.ONLINE, monitor.status.value)
    }

    @Test
    fun transportFailureMarksJellyfinUnavailableWithoutEscapingPlaybackCleanup() = runTest {
        val api = RecordingApi(failure = IOException("network lost"))
        val monitor = JellyfinConnectionMonitor()
        val reporter = DefaultJellyfinPlaybackReporter(api, "device", NoOpLogger, monitor)

        reporter.reportProgress(playbackInfo(), positionSeconds = 7L, isPaused = true)

        assertEquals(SourceAvailability.UNAVAILABLE, monitor.status.value)
    }

    private fun playbackInfo() = JellyfinPlaybackInfo(
        mediaSourceId = "source-version",
        playSessionId = "play-session",
        profile = MediaProfile(container = "mkv", videoCodec = "h264", audioCodec = "aac"),
        runtimeSeconds = 600L,
        itemId = "library-item",
    )

    private data class Call(
        val kind: String,
        val itemId: String,
        val mediaSourceId: String?,
        val positionTicks: Long,
        val isPaused: Boolean?,
    )

    private class RecordingApi(private val failure: Throwable? = null) : JellyfinApi {
        val calls = mutableListOf<Call>()

        override suspend fun systemInfoPublic(): String? = null
        override suspend fun authenticateByName(username: String, password: String): JellyfinApi.AuthResult = error("unused")
        override suspend fun postPlaybackInfo(
            itemId: String,
            deviceProfileJson: String,
            audioStreamIndex: Int?,
            subtitleStreamIndex: Int?,
            maxBitrate: Long?,
            startTimeTicks: Long,
            requireTranscode: Boolean,
        ): JellyfinApi.PlaybackInfoResult = error("unused")

        override fun resolveUpstreamFor(mediaSourceId: String): JellyfinUpstreamSource = error("unused")

        override suspend fun reportPlaying(playSessionId: String?, itemId: String) = error("Expected rich report")

        override suspend fun reportPlaying(
            playSessionId: String?,
            itemId: String,
            mediaSourceId: String?,
            positionTicks: Long,
        ) {
            failIfNeeded()
            calls += Call("start", itemId, mediaSourceId, positionTicks, null)
        }

        override suspend fun reportProgress(
            playSessionId: String?,
            itemId: String,
            positionTicks: Long,
            isPaused: Boolean,
        ) = error("Expected rich report")

        override suspend fun reportProgress(
            playSessionId: String?,
            itemId: String,
            mediaSourceId: String?,
            positionTicks: Long,
            isPaused: Boolean,
        ) {
            failIfNeeded()
            calls += Call("progress", itemId, mediaSourceId, positionTicks, isPaused)
        }

        override suspend fun reportStopped(playSessionId: String?, itemId: String, positionTicks: Long) =
            error("Expected rich report")

        override suspend fun reportStopped(
            playSessionId: String?,
            itemId: String,
            mediaSourceId: String?,
            positionTicks: Long,
        ) {
            failIfNeeded()
            calls += Call("stopped", itemId, mediaSourceId, positionTicks, null)
        }

        override suspend fun stopActiveEncoding(playSessionId: String?, deviceId: String) = Unit

        private fun failIfNeeded() {
            failure?.let { throw it }
        }
    }

    private object NoOpLogger : DiagnosticSink
}
