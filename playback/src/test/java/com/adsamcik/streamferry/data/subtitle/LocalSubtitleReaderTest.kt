package com.adsamcik.streamferry.data.subtitle

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.media3.common.MimeTypes
import com.adsamcik.streamferry.core.language.Languages
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.io.FileNotFoundException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LocalSubtitleReaderTest {
    @Test
    fun `sidecar formats use source timestamps including after seek and preserve text styles`() {
        val documents = listOf(
            MimeTypes.APPLICATION_SUBRIP to "1\n00:00:10,000 --> 00:00:12,000\n<b>Hello</b>\n",
            MimeTypes.TEXT_VTT to "WEBVTT\n\n00:10.000 --> 00:12.000\n<b>Hello</b>\n",
            MimeTypes.TEXT_SSA to "[Script Info]\nScriptType: v4.00+\n[V4+ Styles]\nFormat: Name, Fontname, Fontsize, PrimaryColour, Bold, Italic, Alignment\nStyle: Default,Arial,20,&H00FFFFFF,-1,0,2\n[Events]\nFormat: Layer, Start, End, Style, Text\nDialogue: 0,0:00:10.00,0:00:12.00,Default,Hello\n",
            MimeTypes.APPLICATION_TTML to """<tt xmlns="http://www.w3.org/ns/ttml"><body><div><p begin="10s" end="12s">Hello</p></div></body></tt>""",
        )
        documents.forEach { (mime, document) ->
            LocalSubtitleReader.parseExternal(document.toByteArray(), mime).use { source ->
                assertTrue(source.cuesAt(9_999_999).isEmpty(), mime)
                assertEquals("Hello", source.cuesAt(10_000_000).single().text.toString(), mime)
                assertTrue(source.cuesAt(12_000_000).isEmpty(), mime)
                // Sidecars are indexed: moving back on their source timeline works as well.
                assertEquals("Hello", source.cuesAt(11_000_000).single().text.toString(), mime)
            }
        }
    }

    @Test
    fun `selected unreadable and malformed sidecars fail rather than silently dropping subtitles`() {
        val context = RuntimeEnvironment.getApplication()
        val track = LocalSubtitleReader.external("file:///missing/movie.en.srt", null, "English")
        LocalSubtitleReader.open(context, "unused", null, track).use { source ->
            assertFailsWith<IOException> { source.cuesAt(0) }
        }
        assertFailsWith<IOException> {
            LocalSubtitleReader.parseExternal("not a subtitle".toByteArray(), MimeTypes.APPLICATION_SUBRIP)
        }
        assertFailsWith<IOException> { LocalSubtitleReader.external("file:///movie.unsupported", null, "Unknown") }
    }

    @Test
    fun `generic document MIME infers subtitle type from extension and sources enforce close`() {
        val track = LocalSubtitleReader.external("content://documents/1042", "application/octet-stream", "movie.EN.SRT")
        assertEquals(MimeTypes.APPLICATION_SUBRIP, (track.source as LocalSubtitleSource.External).mimeType)
        assertEquals("eng", track.language)
        val source = LocalSubtitleReader.parseExternal("WEBVTT\n\n00:00.000 --> 00:01.000\nHello\n".toByteArray(), MimeTypes.TEXT_VTT)
        source.close()
        assertFailsWith<IllegalStateException> { source.cuesAt(0) }
    }

    @Test
    fun `Matroska embedded subtitles are discovered and decoded incrementally without Android MediaExtractor`() {
        val context = RuntimeEnvironment.getApplication()
        val directory = kotlin.io.path.createTempDirectory("subtitle-mkv-").toFile()
        try {
            val video = File(directory, "movie.mkv").apply { writeBytes(subtitledMatroska()) }
            File(directory, "movie.en.srt").writeText("1\n00:00:00,000 --> 00:00:01,000\nSidecar\n")
            val tracks = LocalSubtitleReader.tracks(context, video.toURI().toString(), "video/x-matroska")
            assertEquals(2, tracks.size)
            assertTrue(tracks[0].language in Languages.aliases("eng"))
            assertTrue(tracks[0].isDefault)
            assertTrue(tracks[1].source is LocalSubtitleSource.External)
            assertEquals("eng", tracks[1].language)
            val source = LocalSubtitleReader.open(context, video.toURI().toString(), "video/x-matroska", tracks[0])
            try {
                assertTrue(source.cuesAt(500_000).isEmpty())
                assertEquals("Embedded", source.cuesAt(1_000_000).single().text.toString())
                assertEquals("Embedded", source.cuesAt(2_999_999).single().text.toString())
                assertTrue(source.cuesAt(3_000_000).isEmpty())
                assertFailsWith<IllegalArgumentException> { source.cuesAt(1_000_000) }
            } finally { source.close() }
            assertFailsWith<IllegalStateException> { source.cuesAt(3_000_000) }
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun `embedded reader stops at read ahead and surfaces later demux failure only when demanded`() {
        val file = File.createTempFile("subtitle-read-ahead-", ".mkv")
        try {
            file.writeBytes(subtitledMatroska(malformedTail = true))
            val context = RuntimeEnvironment.getApplication()
            val tracks = LocalSubtitleReader.tracks(context, file.toURI().toString(), "video/x-matroska")
            LocalSubtitleReader.open(context, file.toURI().toString(), "video/x-matroska", tracks.single()).use { source ->
                assertEquals("Embedded", source.cuesAt(1_000_000).single().text.toString())
                assertFailsWith<IOException> { source.cuesAt(20_000_000) }
            }
        } finally { file.delete() }
    }

    @Test
    fun `late embedded seek uses container cue index instead of scanning earlier movie payload`() {
        val file = File.createTempFile("subtitle-indexed-seek-", ".mkv")
        try {
            // The first cluster is deliberately unreadable. A two-hour seek must jump over it.
            file.writeBytes(subtitledMatroska(indexedLateSeek = true))
            val context = RuntimeEnvironment.getApplication()
            val track = LocalSubtitleReader.tracks(context, file.toURI().toString(), "video/x-matroska").single()
            LocalSubtitleReader.open(context, file.toURI().toString(), "video/x-matroska", track).use { source ->
                assertEquals("Late caption", source.cuesAt(7_201_500_000).single().text.toString())
            }
        } finally { file.delete() }
    }

    @Test
    fun `discovery cancellation interrupts a provider blocked during initial open`() = runBlocking {
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
            authority = "subtitle-blocked"
            exported = true
        })
        ShadowContentResolver.registerProviderInternal("subtitle-blocked", provider)
        val discovery = launch(Dispatchers.Default) {
            LocalSubtitleReader.discover(RuntimeEnvironment.getApplication(), "content://subtitle-blocked/video", "video/x-matroska")
        }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS), "Discovery should reach provider open")
            withTimeout(2000) { discovery.cancelAndJoin() }
            assertTrue(interrupted.await(2, TimeUnit.SECONDS), "Cancellation should interrupt the provider worker")
        } finally { release.countDown(); discovery.cancelAndJoin() }
    }

    /** Tiny, real Matroska container: one VP8 video track and an SRT BlockGroup with duration. */
    private fun subtitledMatroska(malformedTail: Boolean = false, indexedLateSeek: Boolean = false): ByteArray {
        fun bytes(vararg values: Int) = values.map { it.toByte() }.toByteArray()
        fun element(id: ByteArray, data: ByteArray): ByteArray {
            val length = if (data.size < 127) bytes(0x80 or data.size)
                else bytes(0x40 or (data.size shr 8), data.size and 0xff)
            return id + length + data
        }
        fun el(id: Int, data: ByteArray) = element(bytes(id), data)
        fun uint(id: Int, value: Int) = el(id, if (value < 256) bytes(value) else bytes(value shr 8, value and 0xff))
        val header = element(bytes(0x1a, 0x45, 0xdf, 0xa3),
            element(bytes(0x42, 0x86), bytes(1)) + element(bytes(0x42, 0xf7), bytes(1)) +
                element(bytes(0x42, 0xf2), bytes(4)) + element(bytes(0x42, 0xf3), bytes(8)) +
                element(bytes(0x42, 0x82), "matroska".toByteArray()) + element(bytes(0x42, 0x85), bytes(2)))
        val videoTrack = el(0xae, uint(0xd7, 1) + element(bytes(0x73, 0xc5), bytes(1)) + uint(0x83, 1) +
            el(0x86, "V_VP8".toByteArray()) + el(0xe0, uint(0xb0, 16) + uint(0xba, 16)))
        val textTrack = el(0xae, uint(0xd7, 2) + element(bytes(0x73, 0xc5), bytes(2)) + uint(0x83, 17) +
            el(0x86, "S_TEXT/UTF8".toByteArray()) + uint(0x88, 1) + element(bytes(0x22, 0xb5, 0x9c), "eng".toByteArray()))
        val tracks = element(bytes(0x16, 0x54, 0xae, 0x6b), videoTrack + textTrack)
        fun video(timeMs: Int) = el(0xa3, bytes(0x81, timeMs shr 8, timeMs and 0xff, 0x80, 0))
        if (indexedLateSeek) {
            fun u32(value: Int) = ByteBuffer.allocate(4).putInt(value).array()
            val info = element(bytes(0x15, 0x49, 0xa9, 0x66),
                element(bytes(0x44, 0x89), ByteBuffer.allocate(8).putDouble(7_205_000.0).array()))
            val earlyCluster = element(bytes(0x1f, 0x43, 0xb6, 0x75), uint(0xe7, 0) + video(0) + el(0xe7, ByteArray(9)))
            val lateSubtitle = el(0xa0, el(0xa1, bytes(0x82, 31_000 shr 8, 31_000 and 0xff, 0) +
                "Late caption".toByteArray()) + uint(0x9b, 2000))
            val lateCluster = element(bytes(0x1f, 0x43, 0xb6, 0x75), el(0xe7, u32(7_170_000)) +
                video(0) + lateSubtitle + video(32_760))
            fun cue(time: Int, position: Int) = el(0xbb, el(0xb3, u32(time)) +
                el(0xb7, uint(0xf7, 1) + el(0xf1, u32(position))))
            fun cues(early: Int, late: Int) = element(bytes(0x1c, 0x53, 0xbb, 0x6b), cue(0, early) + cue(7_170_000, late))
            val earlyPosition = info.size + tracks.size + cues(0, 0).size
            return header + element(bytes(0x18, 0x53, 0x80, 0x67), info + tracks +
                cues(earlyPosition, earlyPosition + earlyCluster.size) + earlyCluster + lateCluster)
        }
        val subtitle = el(0xa0, el(0xa1, bytes(0x82, 3, 0xe8, 0) + "Embedded".toByteArray()) + uint(0x9b, 2000))
        val cluster = element(bytes(0x1f, 0x43, 0xb6, 0x75), uint(0xe7, 0) + video(0) + subtitle + video(4000) + video(8000) +
            if (malformedTail) el(0xe7, ByteArray(9)) else ByteArray(0))
        val info = element(bytes(0x15, 0x49, 0xa9, 0x66), ByteArray(0))
        return header + element(bytes(0x18, 0x53, 0x80, 0x67), info + tracks + cluster)
    }
}
