package com.adsamcik.streamferry.data.jellyfin

import com.adsamcik.streamferry.core.stream.Protocol
import com.adsamcik.streamferry.core.stream.TargetCapabilities
import com.adsamcik.streamferry.domain.MediaTrack
import com.adsamcik.streamferry.source.api.DiagnosticSink
import com.adsamcik.streamferry.source.api.MediaRef
import com.adsamcik.streamferry.source.api.PlaybackChange
import com.adsamcik.streamferry.source.api.PlaybackRequest
import com.adsamcik.streamferry.source.api.SourceInstanceId
import com.adsamcik.streamferry.source.api.TrackRef
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JellyfinAudioPlaybackTest {
    private val source = SourceInstanceId(JellyfinSourceBackend.PROVIDER_ID, "server:user")

    @Test
    fun `explicit audio including the default requires a generated stream on both outputs`() = runTest {
        for (protocol in Protocol.entries) {
            for (audio in listOf(1, 2)) {
                val api = api()
                val repository = HttpJellyfinRepository(api, NoopLogger, OkHttpClient())

                repository.prepare(protocol, audio).getOrThrow()

                coVerify(exactly = 1) {
                    api.postPlaybackInfo(
                        "item", match { it.contains("\"DirectPlayProfiles\":[]") },
                        audio, -1, null, 450_000_000, true, "version",
                    )
                }
            }
        }
    }

    @Test
    fun `audio selection fails clearly for missing invalid or unmodified streams`() = runTest {
        val api = api()
        val repository = HttpJellyfinRepository(api, NoopLogger, OkHttpClient())
        assertTrue(repository.prepare(Protocol.CAST, -1).isFailure)
        coVerify(exactly = 0) { api.postPlaybackInfo(any(), any(), any(), any(), any(), any(), any(), any()) }

        assertTrue(repository.prepare(Protocol.DLNA, 9).isFailure)
        coEvery { api.postPlaybackInfo(any(), any(), any(), any(), any(), any(), any(), any()) } returns info(false)
        val failure = repository.playbackInfo(
            "item", capabilities(Protocol.CAST), null, false, true, 2, null, 45, mediaSourceId = "version",
        ).exceptionOrNull()
        assertTrue(failure?.message.orEmpty().contains("selected audio"))
    }

    @Test
    fun `audio ref version and subtitle state survive seek bitrate and codec replans`() = runTest {
        for (protocol in Protocol.entries) {
            val api = api()
            val provider = provider(api)
            val initial = provider.prepare(
                PlaybackRequest(
                    MediaRef(source, "item"), capabilities(protocol), audioTrack = TrackRef("version:2"),
                    subtitleTrack = TrackRef("version:3"), mediaVersion = "version",
                ),
            ).getOrThrow()
            assertEquals(TrackRef("version:2"), initial.descriptor.audioTracks[1].ref)
            val off = initial.replan(PlaybackChange(45, subtitlesDisabled = true)).getOrThrow()
            val changed = off.replan(
                PlaybackChange(90, maxBitrateBps = 3_000_000, preferredVideoCodec = "h264"),
            ).getOrThrow()
            changed.replan(PlaybackChange(120, audioTrack = TrackRef("version:1"))).getOrThrow()

            coVerify { api.postPlaybackInfo("item", any(), 2, 3, null, 0, true, "version") }
            coVerify { api.postPlaybackInfo("item", any(), 2, -1, null, 450_000_000, true, "version") }
            coVerify { api.postPlaybackInfo("item", any(), 2, -1, 3_000_000, 900_000_000, true, "version") }
            coVerify { api.postPlaybackInfo("item", any(), 1, -1, 3_000_000, 1_200_000_000, true, "version") }
        }
    }

    @Test
    fun `malformed mixed version and vanished audio cannot silently select a default`() = runTest {
        val api = api()
        val provider = provider(api)
        val request = PlaybackRequest(MediaRef(source, "item"), capabilities(Protocol.CAST), mediaVersion = "version")
        for (ref in listOf("bad", "-1", "2147483648", "other:1", ":1")) {
            val result = provider.prepare(request.copy(audioTrack = TrackRef(ref)))
            assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        }
        coVerify(exactly = 0) { api.postPlaybackInfo(any(), any(), any(), any(), any(), any(), any(), any()) }
        assertTrue(provider.prepare(request.copy(audioTrack = TrackRef("version:9"))).isFailure)
    }

    private fun provider(api: JellyfinApi) = JellyfinPlaybackProvider(
        source, HttpJellyfinRepository(api, NoopLogger, OkHttpClient()), mockk(relaxed = true), OkHttpClient(),
    )

    private suspend fun HttpJellyfinRepository.prepare(protocol: Protocol, audio: Int) = playbackInfo(
        "item", capabilities(protocol), null, false, true, audio, -1, 45, mediaSourceId = "version",
    )

    private fun api(): JellyfinApi = mockk<JellyfinApi>().apply {
        coEvery { postPlaybackInfo(any(), any(), any(), any(), any(), any(), any(), any()) } returns
            info()
    }

    private fun info(transcoding: Boolean = true) = JellyfinApi.PlaybackInfoResult(
        mediaSourceId = "version", playSessionId = "session", container = "mkv", videoCodec = "h264",
        audioCodec = "aac", isHdr = false, bitDepth = 8, runtimeSeconds = 200,
        upstream = upstream(transcoding),
        audioTracks = listOf(MediaTrack(1, "eng", "English", isDefault = true), MediaTrack(2, "ces", "Czech")),
        subtitleTracks = listOf(MediaTrack(3, "eng", "English ASS")),
    )

    private fun upstream(transcoding: Boolean) = JellyfinUpstreamSource(
        url = "https://server.example/video.ts", authHeader = null, contentType = "video/mp2t",
        outputContainer = "ts", isHls = false, isTranscoding = transcoding,
        isByteSeekable = !transcoding, totalLength = null,
    )

    private fun capabilities(protocol: Protocol) = TargetCapabilities(
        protocol = protocol, supportedContainers = setOf("mp4"), supportedVideoCodecs = setOf("h264"),
        supportedAudioCodecs = setOf("aac"), supportsHls = protocol == Protocol.CAST,
    )

    private object NoopLogger : DiagnosticSink
}
