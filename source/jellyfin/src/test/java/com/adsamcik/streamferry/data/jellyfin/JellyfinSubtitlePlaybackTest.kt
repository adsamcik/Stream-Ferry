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
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JellyfinSubtitlePlaybackTest {
    private val source = SourceInstanceId(JellyfinSourceBackend.PROVIDER_ID, "server:user")

    @Test
    fun `selected subtitles require server burn in for both outputs even without a manual transcode`() = runTest {
        for (protocol in Protocol.entries) {
            val api = api()
            val repository = HttpJellyfinRepository(api, NoopLogger, OkHttpClient())

            assertTrue(repository.prepare(protocol, subtitle = 3).isSuccess)

            coVerify {
                api.postPlaybackInfo(
                    "item", match { it.contains("\"DirectPlayProfiles\":[]") &&
                        it.contains("\"Format\":\"ass\",\"Method\":\"Encode\"") &&
                        it.contains("\"Format\":\"hdmv_pgs_subtitle\",\"Method\":\"Encode\"") },
                    null, 3, null, 450_000_000, true, null,
                )
            }
        }
    }

    @Test
    fun `selected subtitles fail clearly if burn in is forbidden or the server does not deliver it`() = runTest {
        val api = api()
        val repository = HttpJellyfinRepository(api, NoopLogger, OkHttpClient())
        assertTrue(repository.prepare(Protocol.CAST, subtitle = 3, allowBurnIn = false).isFailure)
        coVerify(exactly = 0) { api.postPlaybackInfo(any(), any(), any(), any(), any(), any(), any(), any()) }

        assertTrue(repository.prepare(Protocol.DLNA, subtitle = 8).isFailure)
        every { api.resolveUpstreamFor(any()) } returns upstream(transcoding = false)
        assertTrue(repository.prepare(Protocol.CAST, subtitle = 3).isFailure)
    }

    @Test
    fun `off overrides stale selection and malformed references never silently select a default`() = runTest {
        val repository = mockk<JellyfinPlaybackRepository>()
        val requests = mutableListOf<Int?>()
        coEvery {
            repository.playbackInfo(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } answers {
            requests += arg<Int?>(6)
            Result.failure(IllegalStateException("Recorded"))
        }
        val provider = JellyfinPlaybackProvider(source, repository, mockk(relaxed = true), OkHttpClient())
        val base = PlaybackRequest(MediaRef(source, "item"), capabilities(Protocol.DLNA))

        provider.prepare(base.copy(subtitleTrack = TrackRef("3"), subtitlesDisabled = true))
        assertEquals(listOf<Int?>(-1), requests)
        for (invalid in listOf("not-an-index", "-1", "2147483648")) {
            val result = provider.prepare(base.copy(subtitleTrack = TrackRef(invalid)))
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        }
        assertEquals(listOf<Int?>(-1), requests)
    }

    @Test
    fun `opaque track references pin a version and reject mixed version selections`() = runTest {
        val api = api()
        val provider = JellyfinPlaybackProvider(
            source, HttpJellyfinRepository(api, NoopLogger, OkHttpClient()), mockk(relaxed = true), OkHttpClient(),
        )
        val base = PlaybackRequest(MediaRef(source, "item"), capabilities(Protocol.CAST))

        val prepared = provider.prepare(base.copy(subtitleTrack = TrackRef("version:3"))).getOrThrow()
        assertEquals(TrackRef("version:3"), prepared.descriptor.subtitleTracks.single().ref)
        assertEquals("version", prepared.descriptor.mediaVersion)
        coVerify {
            api.postPlaybackInfo("item", any(), null, 3, null, 0, true, "version")
        }
        val mixed = provider.prepare(base.copy(audioTrack = TrackRef("other:1"), subtitleTrack = TrackRef("version:3")))
        assertTrue(mixed.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun `off without a track reference keeps the independently selected media version`() = runTest {
        val api = api()
        val provider = JellyfinPlaybackProvider(
            source, HttpJellyfinRepository(api, NoopLogger, OkHttpClient()), mockk(relaxed = true), OkHttpClient(),
        )
        val base = PlaybackRequest(
            MediaRef(source, "item"), capabilities(Protocol.DLNA), subtitlesDisabled = true, mediaVersion = "version",
        )

        val off = provider.prepare(base).getOrThrow()
        off.replan(PlaybackChange(30)).getOrThrow()
        val conflicting = provider.prepare(
            base.copy(subtitlesDisabled = false, subtitleTrack = TrackRef("different-version:3")),
        )

        assertEquals("version", off.descriptor.mediaVersion)
        assertTrue(conflicting.exceptionOrNull() is IllegalArgumentException)
        coVerify(exactly = 2) {
            api.postPlaybackInfo("item", any(), null, -1, null, any(), false, "version")
        }
        coVerify(exactly = 2) { api.postPlaybackInfo(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `session replans can select after off disable a stale selection and keep off while seeking`() = runTest {
        val api = api()
        val provider = JellyfinPlaybackProvider(
            source, HttpJellyfinRepository(api, NoopLogger, OkHttpClient()), mockk(relaxed = true), OkHttpClient(),
        )
        val off = provider.prepare(
            PlaybackRequest(MediaRef(source, "item"), capabilities(Protocol.DLNA), subtitlesDisabled = true),
        ).getOrThrow()
        val selected = off.replan(PlaybackChange(10, subtitleTrack = TrackRef("version:3"))).getOrThrow()
        val disabled = selected.replan(
            PlaybackChange(20, subtitleTrack = TrackRef("version:3"), subtitlesDisabled = true),
        ).getOrThrow()
        val seeked = disabled.replan(PlaybackChange(30)).getOrThrow()
        seeked.replan(PlaybackChange(40, subtitleTrack = TrackRef("version:3"))).getOrThrow()

        coVerify(exactly = 3) { api.postPlaybackInfo("item", any(), null, -1, null, any(), false, any()) }
        coVerify(exactly = 2) { api.postPlaybackInfo("item", any(), null, 3, null, any(), true, "version") }
    }

    @Test
    fun `off keeps an existing generated stream without selecting a default subtitle`() = runTest {
        for (protocol in Protocol.entries) {
            val api = api()
            val repository = HttpJellyfinRepository(api, NoopLogger, OkHttpClient())

            assertTrue(repository.prepare(protocol, subtitle = -1).isSuccess)

            coVerify {
                api.postPlaybackInfo("item", match { !it.contains("\"DirectPlayProfiles\":[]") }, null, -1, null, 450_000_000, false, null)
            }
        }
    }

    @Test
    fun `off replaces raw containers with a pinned subtitle free stream on both outputs`() = runTest {
        for (protocol in Protocol.entries) {
            val api = api()
            var transcoding = false
            coEvery { api.postPlaybackInfo(any(), any(), any(), any(), any(), any(), any(), any()) } answers {
                transcoding = arg(6)
                info()
            }
            every { api.resolveUpstreamFor(any()) } answers { upstream(transcoding) }
            val repository = HttpJellyfinRepository(api, NoopLogger, OkHttpClient())

            assertTrue(repository.prepare(protocol, subtitle = -1).isSuccess)

            coVerify(exactly = 1) {
                api.postPlaybackInfo("item", any(), null, -1, null, 450_000_000, false, null)
            }
            coVerify(exactly = 1) {
                api.postPlaybackInfo("item", match { it.contains("\"DirectPlayProfiles\":[]") }, null, -1, null, 450_000_000, true, "version")
            }
            assertTrue(transcoding)

            every { api.resolveUpstreamFor(any()) } returns upstream(transcoding = false)
            assertTrue(repository.prepare(protocol, subtitle = -1).isFailure)
        }
    }

    @Test
    fun `off preserves direct playback when the source has no subtitle tracks`() = runTest {
        val api = api()
        coEvery { api.postPlaybackInfo(any(), any(), any(), any(), any(), any(), any(), any()) } returns
            info().copy(subtitleTracks = emptyList())
        every { api.resolveUpstreamFor(any()) } returns upstream(transcoding = false)
        val repository = HttpJellyfinRepository(api, NoopLogger, OkHttpClient())

        assertTrue(repository.prepare(Protocol.DLNA, subtitle = -1).isSuccess)

        coVerify(exactly = 1) { api.postPlaybackInfo("item", any(), null, -1, null, any(), false, null) }
    }

    @Test
    fun `off preserves the original video when its subtitles are only external sidecars`() = runTest {
        for (protocol in Protocol.entries) {
            val api = api()
            coEvery { api.postPlaybackInfo(any(), any(), any(), any(), any(), any(), any(), any()) } returns
                info().copy(hasEmbeddedSubtitleTracks = false)
            every { api.resolveUpstreamFor(any()) } returns upstream(transcoding = false)
            val repository = HttpJellyfinRepository(api, NoopLogger, OkHttpClient())

            val prepared = repository.prepare(protocol, subtitle = -1).getOrThrow()

            assertEquals(1, prepared.subtitleTracks.size)
            assertEquals(false, prepared.hasEmbeddedSubtitleTracks)
            coVerify(exactly = 1) { api.postPlaybackInfo("item", any(), null, -1, null, any(), false, null) }
        }
    }

    private suspend fun HttpJellyfinRepository.prepare(protocol: Protocol, subtitle: Int, allowBurnIn: Boolean = true) =
        playbackInfo("item", capabilities(protocol), null, false, allowBurnIn, null, subtitle, 45)

    private fun api(): JellyfinApi = mockk<JellyfinApi>().apply {
        coEvery { postPlaybackInfo(any(), any(), any(), any(), any(), any(), any(), any()) } returns info()
        every { resolveUpstreamFor(any()) } returns upstream(transcoding = true)
    }

    private fun info() = JellyfinApi.PlaybackInfoResult(
        mediaSourceId = "version", playSessionId = "session", container = "mkv", videoCodec = "h264",
        audioCodec = "aac", isHdr = false, bitDepth = 8, runtimeSeconds = 120,
        upstreamUrl = "https://server.example/video.ts", contentType = "video/mp2t", isHls = false,
        totalLength = null, subtitleTracks = listOf(MediaTrack(3, "eng", "English ASS")),
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
