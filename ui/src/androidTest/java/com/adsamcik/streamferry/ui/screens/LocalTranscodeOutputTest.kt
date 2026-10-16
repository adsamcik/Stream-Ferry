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
import com.adsamcik.streamferry.data.transcode.ClientTranscodeSession
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

    @Test fun realHardwareExportPublishesSeekableSegmentsFromDownloadedPath() = runBlocking {
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
        val session = ClientTranscodeSession(transcoder, input.absolutePath, runtime, target, File(directory, "session"), logger)
        try {
            val exported = File(context.filesDir, "synthetic-transcode.mp4")
            transcoder.transcodeSegment(input.absolutePath, 0, 2000, target, exported)
            val encoded = exported.readBytes()
            val info = Fmp4Splitter.codecInfo(Fmp4Splitter.initSegment(encoded))
            assertTrue("Hardware output must use requested H.264: $info", info?.videoCodec?.startsWith("avc") == true)
            assertEquals("720p input must actually be encoded to 480p", 480, info?.height)
            val first = session.mediaSegment(0, allowExport = true)
            assertTrue("First real encoded HLS segment must be publishable: $first", first is ClientTranscodeSource.Resource.Ready)
            assertTrue(session.initSegment(allowExport = true) is ClientTranscodeSource.Resource.Ready)
            assertTrue("Seeking to the last segment must encode it", session.mediaSegment(2, allowExport = true) is ClientTranscodeSource.Resource.Ready)
            assertTrue("Out-of-order segment must also work", session.mediaSegment(1, allowExport = true) is ClientTranscodeSource.Resource.Ready)
            val published = File(context.filesDir, "synthetic-hls").apply { mkdirs() }
            for (index in 0..2) {
                File(published, "init_$index.mp4").writeBytes((session.initSegment(index, true) as ClientTranscodeSource.Resource.Ready).bytes)
                File(published, "seg_$index.m4s").writeBytes((session.mediaSegment(index, true) as ClientTranscodeSource.Resource.Ready).bytes)
            }
            val base = "http://synthetic/stream"
            File(published, "media.m3u8").writeText(session.mediaPlaylist("http://synthetic")
                .replace(Regex("${Regex.escape(base)}\\?seg=init_(\\d+)"), "init_$1.mp4")
                .replace(Regex("${Regex.escape(base)}\\?seg=(\\d+)"), "seg_$1.m4s"))
        } finally {
            session.release()
            transcoder.release()
            directory.deleteRecursively()
        }
    }
}
