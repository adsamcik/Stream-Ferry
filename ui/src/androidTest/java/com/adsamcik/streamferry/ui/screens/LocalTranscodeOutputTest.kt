package com.adsamcik.streamferry.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.adsamcik.streamferry.core.transcode.*
import com.adsamcik.streamferry.data.proxy.ClientTranscodeSource
import com.adsamcik.streamferry.data.transcode.StreamingTranscodeSession
import com.adsamcik.streamferry.data.transcode.OnDeviceTranscoder
import com.adsamcik.streamferry.data.transcode.LocalMediaProbe
import com.adsamcik.streamferry.source.api.DiagnosticSink
import com.adsamcik.streamferry.ui.state.PlaybackUiState
import com.adsamcik.streamferry.ui.theme.StreamFerryTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock

/** Uses only generated 720p video and a generated sine wave; no personal media. */
class LocalTranscodeOutputTest {
    @get:Rule val compose = createComposeRule()

    @Test fun localOriginalOffersPhoneConversionFormatAndResolution() {
        val state = mutableStateOf(PlaybackUiState(
            targetName = "Living Room TV", protocol = "CAST",
            mediaTitle = "Synthetic local video", streamMode = "Direct play",
            localTranscodeMode = LocalTranscodeMode.AUTOMATIC,
            availableVideoCodecs = listOf("hevc", "h264"), automaticMaxVideoHeight = 2160,
            maxVideoHeight = 2160, sourceFormat = "H.264 · MP4 · 1080p",
        ))
        compose.setContent {
            StreamFerryTheme(dynamicColor = false) {
                Column(Modifier.systemBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
                    TvOutputCard(state.value, {}, { state.value = state.value.copy(preferredVideoCodec = it) },
                        { state.value = state.value.copy(maxVideoHeight = it ?: 2160, isManualMaxVideoHeight = it != null) },
                        { state.value = state.value.copy(localTranscodeMode = it) })
                }
            }
        }
        compose.onNodeWithContentDescription("Transcoding").performClick()
        compose.onNodeWithText("On device").performClick()
        compose.onNodeWithContentDescription("Output format").performClick()
        compose.onNodeWithText("H.264").performClick()
        compose.onNodeWithContentDescription("Output resolution").performClick()
        compose.onNodeWithText("720p").performClick()
        compose.onNodeWithText("720p").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(LocalTranscodeMode.ON_DEVICE, state.value.localTranscodeMode)
            assertEquals("h264", state.value.preferredVideoCodec)
            assertEquals(720, state.value.maxVideoHeight)
            state.value = state.value.copy(streamMode = "On-device transcode", outputFormat = "H.264 · 720p · on device")
        }
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(instrumentation.targetContext.filesDir, "local-transcode-controls.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }

    @Test fun realHardwareStreamingDecodesVideoAndAudioThroughLaterFragmentsFromDownloadedPath() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directory = File(context.cacheDir, "local-transcode-device-test").apply { mkdirs() }
        val input = File(directory, "downloaded sample.mp4")
        instrumentation.context.assets.open("six-seconds-av.mp4").use { source ->
            input.outputStream().use { source.copyTo(it) }
        }
        val logger = object : DiagnosticSink {
            override fun warn(tag: String, message: String, error: Throwable?) { android.util.Log.w(tag, message, error) }
            override fun event(category: String, message: String) { android.util.Log.i(category, message) }
        }
        val transcoder = OnDeviceTranscoder(context, logger)
        val target = TranscodeTarget(VideoCodec.H264, AudioCodec.AAC, StreamContainer.HLS_FMP4, ResolutionTier.SD_480P, 30)
        val runtime = requireNotNull(LocalMediaProbe.probeDurationSecondsExact(context, input.absolutePath))
        assertEquals(6.0, runtime, 0.001)
        val session = StreamingTranscodeSession(transcoder, input.absolutePath, target, logger, hls = true)
        try {
            // Readiness is the first short fragment; the export continues in the session coroutine.
            session.awaitReady()
            val first = session.mediaSegment(0, allowExport = true)
            assertTrue("First real encoded HLS fragment must be publishable: $first", first is ClientTranscodeSource.Resource.Ready)
            val init = session.initSegment(allowExport = true) as ClientTranscodeSource.Resource.Ready
            val info = Fmp4Splitter.codecInfo(init.bytes)
            assertTrue("Hardware output must use requested H.264: $info", info?.videoCodec?.startsWith("avc") == true)
            assertEquals("720p input must actually be encoded to 480p", 480, info?.height)
            val fragment = (first as ClientTranscodeSource.Resource.Ready).bytes
            assertTrue(Fmp4Splitter.isValidMediaSegment(fragment))
            val fragments = mutableListOf(fragment)
            for (index in 1..8) {
                when (val next = session.mediaSegment(index, allowExport = true)) {
                    is ClientTranscodeSource.Resource.Ready -> {
                        assertTrue("Later fragment $index is valid", Fmp4Splitter.isValidMediaSegment(next.bytes))
                        fragments += next.bytes
                    }
                    else -> {
                        assertTrue("Export must finish normally: $next", session.mediaPlaylist("http://synthetic").contains("#EXT-X-ENDLIST"))
                        break
                    }
                }
            }
            assertTrue("Inspect several actual fragments, including the end", fragments.size >= 3)
            val complete = File(directory, "streamed-output.mp4")
            complete.outputStream().use { output ->
                output.write(init.bytes)
                fragments.forEach { output.write(it) }
            }
            val video = decodeTrack(complete, "video/")
            val audio = decodeTrack(complete, "audio/")
            assertTrue("Video decoder reaches later fragments", video.frames >= 100 && video.lastPtsUs >= 5_800_000)
            assertTrue("AAC decoder reaches later fragments", audio.frames >= 100 && audio.lastPtsUs >= 5_800_000)
            assertTrue("Generated sine must decode to non-silent PCM", audio.hasNonzeroPcm)
            assertTrue("Audio/video end timestamps remain aligned", kotlin.math.abs(video.lastPtsUs - audio.lastPtsUs) < 150_000)
            assertTrue(session.mediaPlaylist("http://synthetic").contains("#EXTINF:"))
        } finally {
            session.release()
            transcoder.release()
            directory.deleteRecursively()
        }
    }

    private data class Decoded(val frames: Int, val lastPtsUs: Long, val hasNonzeroPcm: Boolean)

    /** Real platform decoding of the assembled continuous stream; receiver qualification is separate. */
    private fun decodeTrack(file: File, mimePrefix: String): Decoded {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        try {
            extractor.setDataSource(file.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith(mimePrefix) == true
            } ?: error("Encoded output is missing $mimePrefix")
            val format = extractor.getTrackFormat(track)
            extractor.selectTrack(track)
            val codec = MediaCodec.createDecoderByType(requireNotNull(format.getString(MediaFormat.KEY_MIME)))
            decoder = codec
            codec.configure(format, null, null, 0)
            codec.start()
            var inputEnded = false
            var outputEnded = false
            var frames = 0
            var lastPtsUs = -1L
            var nonzero = false
            val info = MediaCodec.BufferInfo()
            val deadline = SystemClock.elapsedRealtime() + 30_000
            while (!outputEnded && SystemClock.elapsedRealtime() < deadline) {
                if (!inputEnded) {
                    val index = codec.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val size = extractor.readSampleData(requireNotNull(codec.getInputBuffer(index)), 0)
                        if (size < 0) {
                            codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        } else {
                            codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val index = codec.dequeueOutputBuffer(info, 10_000)
                if (index >= 0) {
                    if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        frames++
                        assertTrue("Decoded timestamps must be monotonic", info.presentationTimeUs >= lastPtsUs)
                        lastPtsUs = info.presentationTimeUs
                        if (mimePrefix == "audio/") {
                            val pcm = requireNotNull(codec.getOutputBuffer(index))
                            for (offset in info.offset until info.offset + info.size) {
                                if (pcm.get(offset).toInt() != 0) { nonzero = true; break }
                            }
                        }
                    }
                    outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    codec.releaseOutputBuffer(index, false)
                }
            }
            assertTrue("Decoder must reach EOS instead of hanging", outputEnded)
            return Decoded(frames, lastPtsUs, nonzero)
        } finally {
            decoder?.let { runCatching { it.stop() }; it.release() }
            extractor.release()
        }
    }
}
