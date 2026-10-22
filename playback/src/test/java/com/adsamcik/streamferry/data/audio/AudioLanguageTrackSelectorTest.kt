package com.adsamcik.streamferry.data.audio

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Timeline
import androidx.media3.common.TrackGroup
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import androidx.media3.exoplayer.source.TrackGroupArray
import io.mockk.every
import io.mockk.mockk
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class AudioLanguageTrackSelectorTest {
    private fun format(id: String?, language: String, label: String, default: Boolean = false) = Format.Builder()
        .setId(id).setLanguage(language).setLabel(label).setSampleMimeType("audio/mp4a-latm")
        .setChannelCount(2).setSampleRate(48_000).setSelectionFlags(if (default) C.SELECTION_FLAG_DEFAULT else 0).build()

    private fun chosen(index: Int, format: Format) = LocalAudioTrack(
        index, index + 1, format.id, format.language, format.label ?: "Audio", false, format.roleFlags,
        format.sampleMimeType, format.codecs, format.channelCount, format.sampleRate,
    )

    private fun select(formats: List<Format>, track: LocalAudioTrack, handled: Boolean = true): Format {
        val selector = AudioLanguageTrackSelector(RuntimeEnvironment.getApplication(), track)
        val renderer = mockk<RendererCapabilities>()
        every { renderer.trackType } returns C.TRACK_TYPE_AUDIO
        every { renderer.name } returns "Audio"
        every { renderer.supportsMixedMimeTypeAdaptation() } returns RendererCapabilities.ADAPTIVE_NOT_SUPPORTED
        every { renderer.supportsFormat(any()) } returns RendererCapabilities.create(
            if (handled) C.FORMAT_HANDLED else C.FORMAT_UNSUPPORTED_SUBTYPE,
        )
        selector.init(mockk(relaxed = true), mockk(relaxed = true))
        return try {
            val groups = TrackGroupArray(*formats.mapIndexed { i, f -> TrackGroup("audio-$i", f) }.toTypedArray())
            val result = selector.selectTracks(arrayOf(renderer), groups, MediaPeriodId("period"), Timeline.EMPTY)
            assertNotNull(result.selections[0]).selectedFormat
        } finally { selector.release() }
    }

    @Test fun `exact commentary id wins over language default and encounter order`() {
        val main = format("7", "eng", "English", true)
        val commentary = format("10", "eng", "English commentary")
        val czech = format("9", "ces", "Czech")
        val selected = select(listOf(czech, main, commentary), chosen(2, commentary))
        assertEquals("10", selected.id)
        assertEquals("English commentary", selected.label)
    }

    @Test fun `missing and unsupported selected tracks fail instead of silently replacing the language`() {
        val english = format("7", "eng", "English", true)
        val czech = format("9", "ces", "Czech")
        assertFailsWith<IllegalStateException> { select(listOf(english), chosen(1, czech)) }
        assertFailsWith<IllegalStateException> { select(listOf(english, czech), chosen(1, czech), handled = false) }
    }

    @Test fun `idless tracks retain ordinal and metadata while changed tracks are rejected`() {
        val english = format(null, "eng", "English", true)
        val czech = format(null, "ces", "Czech")
        val selection = chosen(1, czech)
        assertEquals("Czech", select(listOf(english, czech), selection).label)
        assertEquals(-1, findAudioTrack(listOf(czech, english), selection))
        assertEquals(-1, findAudioTrack(listOf(czech.buildUpon().setId("unexpected").build()), selection.copy(index = 0)))
    }

    @Test fun `duplicate or changed ids never fall back to a same language stream`() {
        val english = format("7", "eng", "English")
        assertEquals(-1, findAudioTrack(listOf(english, english), chosen(0, english)))
        assertEquals(-1, findAudioTrack(listOf(english), chosen(0, english).copy(formatId = "missing")))
    }
}
