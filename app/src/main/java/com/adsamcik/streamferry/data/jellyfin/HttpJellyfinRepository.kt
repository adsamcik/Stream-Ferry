package com.adsamcik.streamferry.data.jellyfin

import com.adsamcik.streamferry.core.stream.MediaProfile
import com.adsamcik.streamferry.core.stream.TargetCapabilities
import com.adsamcik.streamferry.domain.JellyfinRepository
import com.adsamcik.streamferry.domain.PlaybackInfo
import com.adsamcik.streamferry.domain.UpstreamSource
import com.adsamcik.streamferry.logging.DiagnosticsLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/**
 * Concrete [JellyfinRepository] over the documented Jellyfin HTTP API (see [JellyfinApiContract]).
 *
 * Responsibilities (§8):
 *   - Build a PlaybackInfo request whose DeviceProfile is derived from the target's real
 *     [TargetCapabilities] ([DeviceProfiles.forTarget]), so Jellyfin direct-plays when compatible and
 *     otherwise transcodes server-side to a TV-compatible format — NO guessed stream URLs.
 *   - Preserve PlaySessionId from the PlaybackInfo response.
 *   - Resolve the secret upstream URL (DirectStreamUrl / TranscodingUrl) + auth header for the proxy
 *     layer only. The UI never sees these.
 *
 * The HTTP/JSON plumbing (auth header construction, DeviceProfile JSON, response parsing) lives in
 * [JellyfinApi]; this class maps it to domain types and applies stream-selection parameters.
 */
class HttpJellyfinRepository(
    private val api: JellyfinApi,
    private val logger: DiagnosticsLogger,
    @Suppress("unused") private val httpClient: OkHttpClient,
) : JellyfinRepository {

    override suspend fun playbackInfo(
        itemId: String,
        capabilities: TargetCapabilities,
        maxBitrateBps: Long?,
        forceTranscode: Boolean,
        allowSubtitleBurnIn: Boolean,
        audioStreamIndex: Int?,
        subtitleStreamIndex: Int?,
        startPositionSeconds: Long,
        maxVideoHeight: Int,
        deviceProfileOverride: String?,
    ): Result<PlaybackInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val deviceProfile = deviceProfileOverride ?: DeviceProfiles.forTarget(
                caps = capabilities,
                maxBitrateBps = maxBitrateBps,
                forceTranscode = forceTranscode,
                allowSubtitleBurnIn = allowSubtitleBurnIn,
                maxVideoHeight = maxVideoHeight,
            )
            val response = api.postPlaybackInfo(
                itemId = itemId,
                deviceProfileJson = deviceProfile,
                audioStreamIndex = audioStreamIndex,
                subtitleStreamIndex = subtitleStreamIndex,
                maxBitrate = maxBitrateBps,
                startTimeTicks = startPositionSeconds * TICKS_PER_SECOND,
                requireTranscode = forceTranscode,
            )
            // (The enriched "PlaybackInfo resolved …" event is logged in JellyfinClient.postPlaybackInfo.)
            PlaybackInfo(
                mediaSourceId = response.mediaSourceId,
                playSessionId = response.playSessionId,
                profile = MediaProfile(
                    container = response.container,
                    videoCodec = response.videoCodec,
                    audioCodec = response.audioCodec,
                    isHdr = response.isHdr,
                    bitDepth = response.bitDepth,
                    widthPx = response.videoWidth,
                    heightPx = response.videoHeight,
                    videoBitrateBps = response.videoBitrateBps,
                ),
                runtimeSeconds = response.runtimeSeconds,
                sourceBitrateBps = response.sourceBitrateBps,
                audioTracks = response.audioTracks,
                subtitleTracks = response.subtitleTracks,
                itemId = itemId,
            )
        }.onFailure { logger.e("jellyfin", "PlaybackInfo request failed (${it.javaClass.simpleName})", it) }
    }

    override suspend fun resolveUpstream(info: PlaybackInfo): UpstreamSource = withContext(Dispatchers.IO) {
        // The upstream URL + auth header are secrets resolved from the cached PlaybackInfo response.
        api.resolveUpstreamFor(info.mediaSourceId)
    }

    override suspend fun mediaSegments(itemId: String): List<com.adsamcik.streamferry.core.segments.MediaSegment> =
        api.mediaSegments(itemId)

    companion object {
        const val TICKS_PER_SECOND = 10_000_000L
    }
}
