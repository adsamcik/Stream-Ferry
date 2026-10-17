package com.adsamcik.streamferry.data.jellyfin

import com.adsamcik.streamferry.core.stream.MediaProfile
import com.adsamcik.streamferry.core.stream.TargetCapabilities
import com.adsamcik.streamferry.source.api.DiagnosticSink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/**
 * Concrete [JellyfinPlaybackRepository] over the documented server HTTP API (see [JellyfinApiContract]).
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
    private val logger: DiagnosticSink,
    @Suppress("unused") private val httpClient: OkHttpClient,
) : JellyfinPlaybackRepository {

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
        preferredVideoCodec: String?,
        downloadProfile: JellyfinDownloadTranscodeProfile?,
        mediaSourceId: String?,
    ): Result<JellyfinPlaybackInfo> = withContext(Dispatchers.IO) {
        runCatching {
            require(subtitleStreamIndex == null || subtitleStreamIndex >= -1) { "Invalid subtitle track" }
            val burnSelectedSubtitle = subtitleStreamIndex?.let { it >= 0 } == true
            require(!burnSelectedSubtitle || allowSubtitleBurnIn) {
                "The selected subtitle needs video transcoding for this output"
            }
            val requireTranscode = forceTranscode || burnSelectedSubtitle
            fun deviceProfile(requiresTranscode: Boolean) = downloadProfile?.let { profile ->
                DeviceProfiles.forDownload(
                    maxBitrateBps = profile.maxBitrateBps,
                    container = profile.container,
                    videoCodec = profile.videoCodec,
                    audioCodec = profile.audioCodec,
                )
            } ?: DeviceProfiles.forTarget(
                caps = capabilities,
                maxBitrateBps = maxBitrateBps,
                forceTranscode = requiresTranscode,
                allowSubtitleBurnIn = allowSubtitleBurnIn,
                preferredVideoCodec = preferredVideoCodec,
                maxVideoHeight = maxVideoHeight,
            )
            var response = api.postPlaybackInfo(
                itemId = itemId,
                deviceProfileJson = deviceProfile(requireTranscode),
                audioStreamIndex = audioStreamIndex,
                subtitleStreamIndex = subtitleStreamIndex,
                maxBitrate = maxBitrateBps,
                startTimeTicks = startPositionSeconds * TICKS_PER_SECOND,
                requireTranscode = requireTranscode,
                mediaSourceId = mediaSourceId,
            )
            val stripSubtitles = subtitleStreamIndex == -1 &&
                (response.hasEmbeddedSubtitleTracks ?: response.subtitleTracks.isNotEmpty())
            if (stripSubtitles && !api.resolveUpstreamFor(response.mediaSourceId).isTranscoding) {
                // A raw container can still auto-enable default/forced captions on the TV. A
                // server-generated stream maps only video/audio when SubtitleStreamIndex is -1.
                response = api.postPlaybackInfo(
                    itemId = itemId,
                    deviceProfileJson = deviceProfile(true),
                    audioStreamIndex = audioStreamIndex,
                    subtitleStreamIndex = -1,
                    maxBitrate = maxBitrateBps,
                    startTimeTicks = startPositionSeconds * TICKS_PER_SECOND,
                    requireTranscode = true,
                    mediaSourceId = response.mediaSourceId,
                )
            }
            if (stripSubtitles) {
                check(api.resolveUpstreamFor(response.mediaSourceId).isTranscoding) {
                    "Jellyfin did not remove subtitle tracks from the video"
                }
            }
            if (burnSelectedSubtitle) {
                check(response.subtitleTracks.any { it.index == subtitleStreamIndex }) {
                    "The selected subtitle track is no longer available"
                }
                check(api.resolveUpstreamFor(response.mediaSourceId).isTranscoding) {
                    "Jellyfin did not transcode the selected subtitle into the video"
                }
            }
            // (The enriched "PlaybackInfo resolved …" event is logged in JellyfinClient.postPlaybackInfo.)
            JellyfinPlaybackInfo(
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
                hasEmbeddedSubtitleTracks = response.hasEmbeddedSubtitleTracks,
            )
        }.onFailure { logger.e("jellyfin", "PlaybackInfo request failed (${it.javaClass.simpleName})", it) }
    }

    override suspend fun resolveUpstream(info: JellyfinPlaybackInfo): JellyfinUpstreamSource = withContext(Dispatchers.IO) {
        // The upstream URL + auth header are secrets resolved from the cached PlaybackInfo response.
        api.resolveUpstreamFor(info.mediaSourceId)
    }

    override suspend fun mediaSegments(itemId: String): List<com.adsamcik.streamferry.core.segments.MediaSegment> =
        api.mediaSegments(itemId)

    companion object {
        const val TICKS_PER_SECOND = 10_000_000L
    }
}
