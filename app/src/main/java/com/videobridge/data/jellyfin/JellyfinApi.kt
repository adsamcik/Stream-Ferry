package com.videobridge.data.jellyfin

import com.videobridge.domain.MediaTrack
import com.videobridge.domain.UpstreamSource

/**
 * Thin, auditable HTTP boundary to the documented Jellyfin endpoints (see [JellyfinApiContract]).
 * Implemented over OkHttp + kotlinx.serialization. Kept as an interface so it can be faked in tests
 * (fake Jellyfin server) and so the playback path is fully testable without a live server.
 */
interface JellyfinApi {

    data class AuthResult(val accessToken: String, val userId: String, val serverVersion: String?)

    data class PlaybackInfoResult(
        val mediaSourceId: String,
        val playSessionId: String?,
        val container: String,
        val videoCodec: String,
        val audioCodec: String,
        val isHdr: Boolean,
        val bitDepth: Int,
        val runtimeSeconds: Long?,
        /** Secret upstream locator (DirectStreamUrl or TranscodingUrl) — never exposed to UI/TV. */
        val upstreamUrl: String,
        val contentType: String,
        val isHls: Boolean,
        val totalLength: Long?,
        /** Source media bitrate (bits/sec), when the server reports it. */
        val sourceBitrateBps: Long? = null,
        /** Source video resolution + video-stream bitrate (bits/sec), when reported. */
        val videoWidth: Int? = null,
        val videoHeight: Int? = null,
        val videoBitrateBps: Long? = null,
        /** Selectable audio tracks reported by the server. */
        val audioTracks: List<MediaTrack> = emptyList(),
        /** Selectable subtitle tracks reported by the server. */
        val subtitleTracks: List<MediaTrack> = emptyList(),
    )

    suspend fun systemInfoPublic(): String?
    suspend fun authenticateByName(username: String, password: String): AuthResult

    suspend fun postPlaybackInfo(
        itemId: String,
        deviceProfileJson: String,
        audioStreamIndex: Int?,
        subtitleStreamIndex: Int?,
        maxBitrate: Long?,
        startTimeTicks: Long,
        requireTranscode: Boolean,
    ): PlaybackInfoResult

    /** Resolve the upstream source for a previously fetched media source id (held in memory). */
    fun resolveUpstreamFor(mediaSourceId: String): UpstreamSource

    /** Skippable media segments (intro/outro/recap/…) for an item; empty when none/unsupported. */
    suspend fun mediaSegments(itemId: String): List<com.videobridge.core.segments.MediaSegment> = emptyList()

    // Reporting / cleanup (§8).
    suspend fun reportPlaying(playSessionId: String?, itemId: String)
    suspend fun reportProgress(playSessionId: String?, itemId: String, positionTicks: Long, isPaused: Boolean)
    suspend fun reportStopped(playSessionId: String?, itemId: String, positionTicks: Long)
    suspend fun stopActiveEncoding(playSessionId: String?, deviceId: String)
}
