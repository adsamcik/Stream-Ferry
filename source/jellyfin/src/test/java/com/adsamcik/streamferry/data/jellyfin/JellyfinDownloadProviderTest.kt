package com.adsamcik.streamferry.data.jellyfin

import com.adsamcik.streamferry.core.stream.MediaProfile
import com.adsamcik.streamferry.core.stream.TargetCapabilities
import com.adsamcik.streamferry.source.api.DownloadFormat
import com.adsamcik.streamferry.source.api.MediaRef
import com.adsamcik.streamferry.source.api.SourceInstanceId
import com.adsamcik.streamferry.source.api.SourceProviderId
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JellyfinDownloadProviderTest {

    @Test
    fun `transcode preparation stays behind the source download contract`() = runTest {
        val source = SourceInstanceId(JellyfinSourceBackend.PROVIDER_ID, "server:user")
        val repository = RecordingRepository()
        val provider = JellyfinDownloadProvider(source, repository, OkHttpClient())
        val format = DownloadFormat.Transcode("Small", "mp4", "h264", "aac", 2_000_000)

        val prepared = provider.prepareDownload(MediaRef(source, "item-7"), format).getOrThrow()

        assertTrue(repository.forceTranscode)
        assertEquals(2_000_000, repository.downloadProfile?.maxBitrateBps)
        assertEquals("item-7", prepared.media.nativeId)
        assertEquals("mp4", prepared.container)
        assertFalse(prepared.stream.descriptor.isHls)
    }

    @Test
    fun `foreign media identity is rejected before source negotiation`() = runTest {
        val source = SourceInstanceId(JellyfinSourceBackend.PROVIDER_ID, "server:user")
        val repository = RecordingRepository()
        val provider = JellyfinDownloadProvider(source, repository, OkHttpClient())
        val foreign = MediaRef(SourceInstanceId(SourceProviderId("other"), "account"), "item-7")

        assertTrue(provider.prepareDownload(foreign, DownloadFormat.Original).isFailure)
        assertFalse(repository.called)
    }

    private class RecordingRepository : JellyfinPlaybackRepository {
        var called = false
        var forceTranscode = false
        var downloadProfile: JellyfinDownloadTranscodeProfile? = null

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
        ): Result<JellyfinPlaybackInfo> {
            called = true
            this.forceTranscode = forceTranscode
            this.downloadProfile = downloadProfile
            return Result.success(
                JellyfinPlaybackInfo(
                    mediaSourceId = "media-source",
                    playSessionId = "play-session",
                    profile = MediaProfile("mp4", "h264", audioCodec = "aac"),
                    runtimeSeconds = 90,
                    itemId = itemId,
                ),
            )
        }

        override suspend fun resolveUpstream(info: JellyfinPlaybackInfo) = JellyfinUpstreamSource(
            url = "https://media.example/video.mp4",
            authHeader = "private-credential",
            contentType = "video/mp4",
            outputContainer = "mp4",
            isHls = false,
            isTranscoding = forceTranscode,
            isByteSeekable = !forceTranscode,
            totalLength = 1024,
        )
    }
}
