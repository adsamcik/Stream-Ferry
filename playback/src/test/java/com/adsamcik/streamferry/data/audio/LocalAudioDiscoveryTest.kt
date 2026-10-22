package com.adsamcik.streamferry.data.audio

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.media3.common.MimeTypes
import com.adsamcik.streamferry.core.language.Languages
import com.adsamcik.streamferry.data.subtitle.LocalSubtitleReader
import java.io.File
import java.io.FileNotFoundException
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class LocalAudioDiscoveryTest {
    @Test
    fun `container audio identities distinguish languages and same-language commentary in deterministic order`() = runBlocking {
        val file = File.createTempFile("audio-tracks-", ".mkv")
        try {
            file.writeBytes(audioMatroska())
            val first = LocalSubtitleReader.discoverAudio(RuntimeEnvironment.getApplication(), file.toURI().toString(), "video/x-matroska")
            val reopened = LocalSubtitleReader.discoverAudio(RuntimeEnvironment.getApplication(), file.toURI().toString(), "video/x-matroska")
            assertEquals(first, reopened)
            assertEquals(listOf(2, 5, 7), first.map { it.extractorTrackId })
            assertEquals(listOf(0, 1, 2), first.map { it.index })
            assertEquals(listOf("2", "5", "7"), first.map { it.formatId })
            assertEquals(listOf("Czech", "English commentary", "English"), first.map { it.label })
            assertTrue(first[0].language in Languages.aliases("ces"))
            assertTrue(first[1].language in Languages.aliases("eng"))
            assertTrue(first[2].language in Languages.aliases("eng"))
            assertEquals(listOf(false, false, true), first.map { it.isDefault })
            assertEquals(listOf(2, 2, 6), first.map { it.channelCount })
            first.forEach { track ->
                assertEquals(MimeTypes.AUDIO_RAW, track.mimeType)
                assertEquals(48_000, track.sampleRate)
                assertNull(track.unsupportedReason)
            }
        } finally { file.delete() }
    }

    @Test
    fun `audio-only file discovers its audio stream without video or language metadata`() = runBlocking {
        val file = File.createTempFile("audio-only-", ".mkv")
        try {
            file.writeBytes(audioMatroska(audioOnly = true))
            val track = LocalSubtitleReader.discoverAudio(RuntimeEnvironment.getApplication(), file.toURI().toString(), "audio/x-matroska").single()
            assertEquals("Audio 1", track.label)
            assertNull(track.language)
            assertEquals("3", track.formatId)
            assertEquals(1, track.channelCount)
        } finally { file.delete() }
    }

    @Test
    fun `audio discovery cancellation interrupts a provider blocked in initial open`() = runBlocking {
        val entered = CountDownLatch(1)
        val interrupted = CountDownLatch(1)
        val release = CountDownLatch(1)
        val provider = object : ContentProvider() {
            override fun onCreate() = true
            override fun getType(uri: Uri) = "video/x-matroska"
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
            override fun insert(uri: Uri, values: ContentValues?): Uri? = null
            override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
            override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
            override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
                entered.countDown()
                try { release.await(5, TimeUnit.SECONDS) }
                catch (_: InterruptedException) { interrupted.countDown() }
                throw FileNotFoundException("Cancelled test provider")
            }
        }
        provider.attachInfo(RuntimeEnvironment.getApplication(), ProviderInfo().apply {
            authority = "audio-blocked"
            exported = true
        })
        ShadowContentResolver.registerProviderInternal("audio-blocked", provider)
        val discovery = launch(Dispatchers.Default) {
            LocalSubtitleReader.discoverAudio(RuntimeEnvironment.getApplication(), "content://audio-blocked/video", "video/x-matroska")
        }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS), "Discovery should reach provider open")
            withTimeout(2000) { discovery.cancelAndJoin() }
            assertTrue(interrupted.await(2, TimeUnit.SECONDS), "Cancellation should interrupt the provider worker")
        } finally { release.countDown(); discovery.cancelAndJoin() }
    }

    @Test
    fun `one header discovery retains ordered audio identities and local subtitle siblings`() = runBlocking {
        val directory = kotlin.io.path.createTempDirectory("combined-tracks-").toFile()
        try {
            val video = File(directory, "movie.mkv").apply { writeBytes(audioMatroska(withSubtitles = true)) }
            File(directory, "movie.en.srt").writeText("1\n00:00:00,000 --> 00:00:01,000\nCaption\n")
            val result = LocalSubtitleReader.discoverTracks(RuntimeEnvironment.getApplication(), video.toURI().toString(), "video/x-matroska")
            assertEquals(listOf(2, 5, 7), result.audio.map { it.extractorTrackId })
            assertEquals(listOf("Czech", "English commentary", "English"), result.audio.map { it.label })
            assertEquals(2, result.subtitles.size)
            assertTrue(result.subtitles.first().source is com.adsamcik.streamferry.data.subtitle.LocalSubtitleSource.Embedded)
            assertTrue(result.subtitles.last().source is com.adsamcik.streamferry.data.subtitle.LocalSubtitleSource.External)
            assertEquals("eng", result.subtitles.last().language)
        } finally { directory.deleteRecursively() }
    }

    /** A first packet precedes malformed later data: discovery must not scan the movie payload. */
    private fun audioMatroska(audioOnly: Boolean = false, withSubtitles: Boolean = false): ByteArray {
        fun bytes(vararg values: Int) = values.map { it.toByte() }.toByteArray()
        fun id(value: Int): ByteArray {
            val full = ByteBuffer.allocate(4).putInt(value).array()
            return full.dropWhile { it == 0.toByte() }.toByteArray()
        }
        fun el(value: Int, data: ByteArray): ByteArray {
            val length = if (data.size < 127) bytes(0x80 or data.size)
                else bytes(0x40 or (data.size shr 8), data.size and 0xff)
            return id(value) + length + data
        }
        fun uint(value: Int, number: Int) = el(value, if (number < 256) bytes(number) else bytes(number shr 8, number and 0xff))
        fun audio(number: Int, language: String?, name: String?, default: Boolean, channels: Int) = el(0xae,
            uint(0xd7, number) + uint(0x73c5, number) + uint(0x83, 2) + uint(0x88, if (default) 1 else 0) +
                el(0x86, "A_PCM/INT/LIT".toByteArray()) +
                (language?.let { el(0x22b59c, it.toByteArray()) } ?: el(0x22b59c, "und".toByteArray())) +
                (name?.let { el(0x536e, it.toByteArray()) } ?: ByteArray(0)) +
                el(0xe1, uint(0x9f, channels) + uint(0x6264, 16) + el(0xb5, ByteBuffer.allocate(8).putDouble(48_000.0).array())))
        val header = el(0x1a45dfa3, uint(0x4286, 1) + uint(0x42f7, 1) + uint(0x42f2, 4) +
            uint(0x42f3, 8) + el(0x4282, "matroska".toByteArray()) + uint(0x4285, 2))
        val tracks = if (audioOnly) audio(3, null, null, true, 1) else {
            val video = el(0xae, uint(0xd7, 1) + uint(0x73c5, 1) + uint(0x83, 1) +
                el(0x86, "V_VP8".toByteArray()) + el(0xe0, uint(0xb0, 16) + uint(0xba, 16)))
            // Encounter order intentionally differs from extractor ID order.
            video + audio(7, "eng", "English", true, 6) + audio(2, "ces", "Czech", false, 2) +
                audio(5, "eng", "English commentary", false, 2)
        }
        val text = if (withSubtitles) el(0xae, uint(0xd7, 9) + uint(0x73c5, 9) + uint(0x83, 17) +
            el(0x86, "S_TEXT/UTF8".toByteArray()) + el(0x22b59c, "eng".toByteArray())) else ByteArray(0)
        // MatroskaExtractor.read returns after its first sample, even when endTracks fired earlier
        // in the same call. Supply that valid packet before the unreadable tail.
        val firstPacket = el(0xa3, bytes(if (audioOnly) 0x83 else 0x81, 0, 0, 0x80, 0, 0))
        return header + el(0x18538067, el(0x1549a966, ByteArray(0)) + el(0x1654ae6b, tracks + text) +
            el(0x1f43b675, uint(0xe7, 0) + firstPacket + el(0xe7, ByteArray(9))))
    }
}
