package com.adsamcik.streamferry.data.jellyfin

import com.adsamcik.streamferry.core.segments.MediaSegment
import com.adsamcik.streamferry.core.stream.MediaProfile
import com.adsamcik.streamferry.core.stream.TargetCapabilities
import com.adsamcik.streamferry.domain.HlsSegmentFormat
import com.adsamcik.streamferry.domain.MediaTrack

/** Jellyfin's prepared playback response. It never crosses the source-module boundary. */
data class JellyfinPlaybackInfo(
    val mediaSourceId: String,
    val playSessionId: String?,
    val profile: MediaProfile,
    val runtimeSeconds: Long?,
    val sourceBitrateBps: Long? = null,
    val audioTracks: List<MediaTrack> = emptyList(),
    val subtitleTracks: List<MediaTrack> = emptyList(),
    val itemId: String = mediaSourceId,
)

data class JellyfinDownloadTranscodeProfile(
    val maxBitrateBps: Long,
    val container: String,
    val videoCodec: String,
    val audioCodec: String,
)

/** Source-private repository for Jellyfin playback negotiation. */
interface JellyfinPlaybackRepository {
    suspend fun playbackInfo(
        itemId: String,
        capabilities: TargetCapabilities,
        maxBitrateBps: Long?,
        forceTranscode: Boolean,
        allowSubtitleBurnIn: Boolean,
        audioStreamIndex: Int?,
        subtitleStreamIndex: Int?,
        startPositionSeconds: Long,
        maxVideoHeight: Int = 0,
        preferredVideoCodec: String? = null,
        downloadProfile: JellyfinDownloadTranscodeProfile? = null,
    ): Result<JellyfinPlaybackInfo>

    suspend fun resolveUpstream(info: JellyfinPlaybackInfo): JellyfinUpstreamSource

    suspend fun mediaSegments(itemId: String): List<MediaSegment> = emptyList()
}

/** Secret Jellyfin locator and credential material, retained only inside this module. */
data class JellyfinUpstreamSource(
    val url: String,
    val authHeader: String?,
    val contentType: String,
    val outputContainer: String,
    val hlsSegmentFormat: HlsSegmentFormat? = null,
    val isHls: Boolean,
    val isTranscoding: Boolean,
    val isByteSeekable: Boolean,
    val totalLength: Long?,
) {
    init {
        require(isHls == (hlsSegmentFormat != null)) {
            "HLS streams must declare a segment format; progressive streams must not."
        }
        require(!isTranscoding || !isByteSeekable) {
            "Live transcoded streams must not advertise byte seeking."
        }
    }
}

/** Jellyfin lifecycle reporting and active-transcode cleanup. */
interface JellyfinPlaybackReporter {
    suspend fun reportStart(info: JellyfinPlaybackInfo)
    suspend fun reportStart(info: JellyfinPlaybackInfo, initialPositionSeconds: Long) = reportStart(info)
    suspend fun reportProgress(info: JellyfinPlaybackInfo, positionSeconds: Long, isPaused: Boolean)
    suspend fun reportStopped(info: JellyfinPlaybackInfo, positionSeconds: Long)
    suspend fun stopTranscode(info: JellyfinPlaybackInfo)
}
