package com.adsamcik.streamferry.data.subtitle

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.text.Cue
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.DiscardingTrackOutput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.text.CueDecoder
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser
import com.adsamcik.streamferry.core.language.Languages
import com.adsamcik.streamferry.data.audio.LocalAudioTrack
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine

/** Media3 demuxing is necessary here: Android MediaExtractor omits several MKV subtitle codecs. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
object LocalSubtitleReader {
    private const val MAX_SIDECAR_BYTES = 16 * 1024 * 1024

    fun tracks(context: Context, videoUri: String, sourceMimeType: String?): List<LocalSubtitleTrack> {
        return tracks(context, videoUri, sourceMimeType, ReadCancellation())
    }

    /** Cancellation can close a provider even while its initial open/sniff is still pending. */
    suspend fun discover(context: Context, videoUri: String, sourceMimeType: String?): List<LocalSubtitleTrack> =
        suspendCancellableCoroutine { continuation ->
            val cancellation = ReadCancellation()
            val worker = Thread({
                val result = runCatching { tracks(context, videoUri, sourceMimeType, cancellation) }
                if (continuation.isActive) continuation.resumeWith(result)
            }, "subtitle-discovery").apply { isDaemon = true }
            continuation.invokeOnCancellation { cancellation.cancel(); worker.interrupt() }
            worker.start()
        }

    /** Reads container headers only; cancellation also interrupts a blocked document-provider open. */
    suspend fun discoverAudio(context: Context, videoUri: String, sourceMimeType: String?): List<LocalAudioTrack> =
        suspendCancellableCoroutine { continuation ->
            val cancellation = ReadCancellation()
            val worker = Thread({
                val result = runCatching { audioTracks(context, videoUri, sourceMimeType, cancellation) }
                if (continuation.isActive) continuation.resumeWith(result)
            }, "audio-discovery").apply { isDaemon = true }
            continuation.invokeOnCancellation { cancellation.cancel(); worker.interrupt() }
            worker.start()
        }

    private fun audioTracks(
        context: Context,
        videoUri: String,
        sourceMimeType: String?,
        cancellation: ReadCancellation,
    ): List<LocalAudioTrack> {
        val discovered = linkedMapOf<Int, LocalAudioTrack>()
        var ended = false
        val output = object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput = object : TrackOutput by DiscardingTrackOutput() {
                override fun format(format: Format) {
                    if (type != C.TRACK_TYPE_AUDIO) return
                    val index = discovered[id]?.index ?: discovered.size
                    val language = format.language?.takeUnless { it.isBlank() || it == "und" }
                    discovered[id] = LocalAudioTrack(
                        index = index,
                        extractorTrackId = id,
                        formatId = format.id,
                        language = language,
                        label = format.label ?: Languages.nameFor(language) ?: "Audio ${index + 1}",
                        isDefault = format.selectionFlags and C.SELECTION_FLAG_DEFAULT != 0,
                        roleFlags = format.roleFlags,
                        mimeType = format.sampleMimeType,
                        codecs = format.codecs,
                        channelCount = format.channelCount,
                        sampleRate = format.sampleRate,
                        unsupportedReason = when {
                            format.drmInitData != null -> "Encrypted audio cannot be converted on this device"
                            format.sampleMimeType == null -> "Unknown audio format"
                            else -> null
                        },
                    )
                }
            }
            override fun endTracks() { ended = true }
            override fun seekMap(seekMap: SeekMap) = Unit
        }
        ExtractorSession(context, videoUri, sourceMimeType, output, cancellation).use { session ->
            while (!ended && session.read()) Unit
        }
        // ProgressiveMediaPeriod groups extractor tracks by ID, independent of header encounter order.
        return discovered.values.sortedBy { it.extractorTrackId }.mapIndexed { index, track -> track.copy(index = index) }
    }

    private fun tracks(context: Context, videoUri: String, sourceMimeType: String?, cancellation: ReadCancellation): List<LocalSubtitleTrack> {
        val discovered = ArrayList<LocalSubtitleTrack>()
        var ended = false
        val output = object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput = object : TrackOutput by DiscardingTrackOutput() {
                override fun format(format: Format) {
                    if (type != C.TRACK_TYPE_TEXT) return
                    val supported = format.sampleMimeType == MimeTypes.APPLICATION_MEDIA3_CUES ||
                        DefaultSubtitleParserFactory().supportsFormat(format)
                    discovered.removeAll { (it.source as? LocalSubtitleSource.Embedded)?.trackId == id }
                    discovered += LocalSubtitleTrack(
                        index = discovered.size,
                        language = format.language,
                        label = format.label ?: format.language ?: "Subtitle ${discovered.size + 1}",
                        isDefault = format.selectionFlags and C.SELECTION_FLAG_DEFAULT != 0,
                        isForced = format.selectionFlags and C.SELECTION_FLAG_FORCED != 0,
                        source = LocalSubtitleSource.Embedded(id),
                        unsupportedReason = if (supported) null else "Unsupported subtitle format: ${format.sampleMimeType}",
                    )
                }
            }
            override fun endTracks() { ended = true }
            override fun seekMap(seekMap: SeekMap) = Unit
        }
        ExtractorSession(context, videoUri, sourceMimeType, output, cancellation).use { session ->
            while (!ended && session.read()) Unit
        }
        // File siblings require no extra permission. A content URI never grants access to sibling documents.
        val uri = sourceUri(videoUri)
        if (uri.scheme == "file") {
            val file = File(requireNotNull(uri.path))
            file.parentFile?.listFiles()?.filter { sibling ->
                sibling.isFile && sibling != file &&
                    (sibling.nameWithoutExtension == file.nameWithoutExtension ||
                        sibling.nameWithoutExtension.startsWith("${file.nameWithoutExtension}.", ignoreCase = true)) &&
                    mimeFromName(sibling.name) != null
            }?.sortedBy { it.name }?.forEach { sibling ->
                discovered += external(Uri.fromFile(sibling).toString(), null, sibling.name)
                    .copy(index = discovered.size)
            }
        }
        return discovered
    }

    private class ReadCancellation {
        @Volatile private var cancelled = false
        @Volatile private var dataSource: DataSource? = null
        fun bind(source: DataSource) {
            dataSource = source
            check()
        }
        fun check() {
            if (cancelled || Thread.currentThread().isInterrupted) {
                runCatching { dataSource?.close() }
                throw CancellationException("Subtitle read cancelled")
            }
        }
        fun cancel() {
            cancelled = true
            runCatching { dataSource?.close() }
        }
    }

    fun external(uri: String, mimeType: String?, label: String): LocalSubtitleTrack {
        val mime = normalizeMime(mimeType) ?: mimeFromName(label) ?: mimeFromName(Uri.parse(uri).lastPathSegment.orEmpty())
            ?: throw IOException("Choose an SRT, WebVTT, ASS/SSA or TTML subtitle file")
        if (!DefaultSubtitleParserFactory().supportsFormat(Format.Builder().setSampleMimeType(mime).build())) {
            throw IOException("Unsupported subtitle format: $mime")
        }
        val filename = if (mimeFromName(label) != null) label else Uri.parse(uri).lastPathSegment.orEmpty()
        val suffixes = filename.substringBeforeLast('.').split('.').drop(1).flatMap { it.split('_', '-') }
        val language = suffixes.asReversed().firstNotNullOfOrNull { suffix ->
            Languages.COMMON.firstOrNull { suffix.lowercase() in Languages.aliases(it.code) }?.code
        }
        return LocalSubtitleTrack(0, language, label, isForced = suffixes.any { it.equals("forced", true) },
            source = LocalSubtitleSource.External(uri, mime))
    }

    /** Resolves the picker display name: document IDs commonly contain no filename or extension. */
    fun external(context: Context, uri: String): LocalSubtitleTrack {
        val parsed = sourceUri(uri)
        require(parsed.scheme == "content" || parsed.scheme == "file") { "Choose a subtitle file on this device" }
        if (parsed.scheme == "file") {
            val file = File(requireNotNull(parsed.path))
            return external(parsed.toString(), null, file.name)
        }
        val resolver = context.contentResolver
        val name = resolver.query(parsed, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
        } ?: parsed.lastPathSegment.orEmpty()
        return external(parsed.toString(), resolver.getType(parsed), name)
    }

    fun open(
        context: Context,
        videoUri: String,
        sourceMimeType: String?,
        track: LocalSubtitleTrack,
    ): SubtitleCueSource {
        track.unsupportedReason?.let { throw IOException(it) }
        return when (val source = track.source) {
            is LocalSubtitleSource.Embedded -> EmbeddedCueSource(context.applicationContext, videoUri, sourceMimeType, source.trackId)
            is LocalSubtitleSource.External -> ExternalCueSource(context.applicationContext, source)
        }
    }

    internal fun parseExternal(bytes: ByteArray, mimeType: String): SubtitleCueSource {
        val parser = DefaultSubtitleParserFactory().create(Format.Builder().setSampleMimeType(mimeType).build())
        val timeline = SubtitleCueTimeline(parser.cueReplacementBehavior == Format.CUE_REPLACEMENT_BEHAVIOR_REPLACE)
        var decodedCues = 0
        try {
            parser.parse(bytes, SubtitleParser.OutputOptions.allCues()) {
                decodedCues += it.cues.size
                timeline.add(it)
            }
        } catch (error: RuntimeException) {
            throw IOException("Could not decode subtitle file", error)
        }
        if (decodedCues == 0) throw IOException("Subtitle file contains no readable captions")
        return object : SubtitleCueSource {
            private var closed = false
            override fun cuesAt(sourceTimeUs: Long): List<Cue> {
                check(!closed) { "Subtitle source is closed" }
                return timeline.cuesAt(sourceTimeUs)
            }
            override fun close() { closed = true }
        }
    }

    private fun normalizeMime(mime: String?): String? = when (mime?.substringBefore(';')?.trim()?.lowercase()) {
        null, "", "application/octet-stream", "text/plain" -> null
        "application/x-subrip", "application/srt", "text/srt" -> MimeTypes.APPLICATION_SUBRIP
        "text/vtt", "application/webvtt" -> MimeTypes.TEXT_VTT
        "text/x-ass", "text/x-ssa", "application/x-ass", "application/x-ssa" -> MimeTypes.TEXT_SSA
        "application/ttml+xml", "application/dfxp+xml" -> MimeTypes.APPLICATION_TTML
        else -> mime?.substringBefore(';')?.trim()?.lowercase()
    }

    private fun mimeFromName(name: String): String? = when (name.substringAfterLast('.', "").lowercase()) {
        "srt" -> MimeTypes.APPLICATION_SUBRIP
        "vtt" -> MimeTypes.TEXT_VTT
        "ass", "ssa" -> MimeTypes.TEXT_SSA
        "ttml", "dfxp", "xml" -> MimeTypes.APPLICATION_TTML
        else -> null
    }

    private fun sourceUri(value: String): Uri = Uri.parse(value).let {
        if (it.scheme == null) Uri.fromFile(File(value)) else it
    }

    /** The caller gets an owner immediately, so stopping also cancels a slow document-provider read. */
    private class ExternalCueSource(context: Context, source: LocalSubtitleSource.External) : SubtitleCueSource {
        private val lock = Object()
        private val cancellation = ReadCancellation()
        private val dataSource = DefaultDataSource.Factory(context).createDataSource()
            .also { cancellation.bind(it) }
        @Volatile private var closed = false
        private var decoded: SubtitleCueSource? = null
        private var failure: Throwable? = null
        private val worker = Thread({
            try {
                val bytes = ByteArrayOutputStream()
                cancellation.check()
                val length = dataSource.open(DataSpec(sourceUri(source.uri)))
                cancellation.check()
                if (length > MAX_SIDECAR_BYTES) throw IOException("Subtitle file exceeds 16 MiB")
                val buffer = ByteArray(8192)
                while (!closed) {
                    cancellation.check()
                    val count = dataSource.read(buffer, 0, buffer.size)
                    if (count == C.RESULT_END_OF_INPUT) break
                    if (bytes.size() + count > MAX_SIDECAR_BYTES) throw IOException("Subtitle file exceeds 16 MiB")
                    bytes.write(buffer, 0, count)
                }
                if (!closed) {
                    val result = parseExternal(bytes.toByteArray(), source.mimeType)
                    synchronized(lock) {
                        if (closed) result.close() else decoded = result
                        lock.notifyAll()
                    }
                }
            } catch (error: Throwable) {
                synchronized(lock) { if (!closed) failure = error; lock.notifyAll() }
            } finally { runCatching { dataSource.close() } }
        }, "subtitle-sidecar").apply { isDaemon = true; start() }

        override fun cuesAt(sourceTimeUs: Long): List<Cue> = synchronized(lock) {
            check(!closed) { "Subtitle source is closed" }
            val deadline = System.nanoTime() + 30_000_000_000L
            while (!closed && decoded == null && failure == null) {
                val remainingMs = (deadline - System.nanoTime()) / 1_000_000
                if (remainingMs <= 0) throw IOException("Timed out reading subtitle file")
                lock.wait(remainingMs.coerceAtMost(1000))
            }
            if (closed) throw CancellationException("Subtitle source closed")
            failure?.let { throw IOException("Could not read subtitle file", it) }
            requireNotNull(decoded).cuesAt(sourceTimeUs)
        }

        override fun close() {
            closed = true
            synchronized(lock) { decoded?.close(); lock.notifyAll() }
            cancellation.cancel()
            worker.interrupt()
        }
    }

    /** Owns one seekable data source. Extractor-requested seeks (e.g. MP4 moov) never read the whole file. */
    private class ExtractorSession(
        context: Context,
        uri: String,
        mimeType: String?,
        output: ExtractorOutput,
        private val cancellation: ReadCancellation = ReadCancellation(),
    ) : AutoCloseable {
        private val dataSource = DefaultDataSource.Factory(context).createDataSource()
            .also { cancellation.bind(it) }
        private val uri = sourceUri(uri)
        private var input: DefaultExtractorInput
        private val extractor: Extractor
        @Volatile private var closed = false
        init {
            input = reopen(0)
            val headers = if (mimeType == null) emptyMap() else mapOf("Content-Type" to listOf(mimeType))
            val candidates = DefaultExtractorsFactory().setTextTrackTranscodingEnabled(true).createExtractors(this.uri, headers)
            try {
                extractor = candidates.firstOrNull {
                    try { it.sniff(input) } catch (_: EOFException) { false } finally { input.resetPeekPosition() }
                } ?: throw IOException("Cannot read subtitle tracks from this media container")
                candidates.filter { it !== extractor }.forEach { it.release() }
                extractor.init(output)
            } catch (failure: Throwable) {
                candidates.forEach { it.release() }
                dataSource.close()
                throw failure
            }
        }
        private fun reopen(position: Long): DefaultExtractorInput {
            dataSource.close()
            cancellation.check()
            if (closed) throw CancellationException("Subtitle source closed")
            try {
                val length = dataSource.open(DataSpec.Builder().setUri(uri).setPosition(position).build())
                cancellation.check()
                return DefaultExtractorInput(dataSource, position, if (length == C.LENGTH_UNSET.toLong()) length else position + length)
            } catch (error: Throwable) {
                runCatching { dataSource.close() }
                throw error
            }
        }
        fun read(): Boolean {
            if (closed) throw CancellationException("Subtitle source closed")
            cancellation.check()
            val seek = PositionHolder()
            return when (extractor.read(input, seek)) {
                Extractor.RESULT_END_OF_INPUT -> false
                Extractor.RESULT_SEEK -> { input = reopen(seek.position); true }
                else -> true
            }
        }
        fun seek(timeUs: Long, seekMap: SeekMap) {
            val points = seekMap.getSeekPoints(timeUs)
            val point = if (points.first.timeUs <= points.second.timeUs) points.first else points.second
            extractor.seek(point.position, point.timeUs)
            input = reopen(point.position)
        }
        fun cancel() {
            closed = true
            cancellation.cancel()
        }
        override fun close() {
            cancel()
            extractor.release()
        }
    }

    /** Demuxes only as far as the current frame needs; no full-video conversion or subtitle pre-scan. */
    private class EmbeddedCueSource(
        private val context: Context,
        private val uri: String,
        private val mimeType: String?,
        private val trackId: Int,
    ) : SubtitleCueSource {
        private val lock = Object()
        private var requestedUs = -1L
        private var readThroughUs = -1L
        private var lastQueryUs = -1L
        private var finished = false
        private var failure: Throwable? = null
        private var timeline = SubtitleCueTimeline(false)
        private val cancellation = ReadCancellation()
        @Volatile private var closed = false
        @Volatile private var session: ExtractorSession? = null
        private val worker = Thread({ extract() }, "subtitle-demux").apply { isDaemon = true; start() }

        override fun cuesAt(sourceTimeUs: Long): List<Cue> = synchronized(lock) {
            check(!closed) { "Subtitle source is closed" }
            require(sourceTimeUs >= lastQueryUs) { "Reopen subtitles after seeking backwards" }
            lastQueryUs = sourceTimeUs
            requestedUs = sourceTimeUs
            lock.notifyAll()
            val deadline = System.nanoTime() + 30_000_000_000L
            while (!closed && !finished && failure == null && readThroughUs < sourceTimeUs + READ_AHEAD_US) {
                val remainingMs = (deadline - System.nanoTime()) / 1_000_000
                if (remainingMs <= 0) throw IOException("Timed out reading embedded subtitles")
                lock.wait(remainingMs.coerceAtMost(1000))
            }
            if (closed) throw CancellationException("Subtitle source closed")
            failure?.let { throw IOException("Could not read embedded subtitles", it) }
            timeline.discardBefore(sourceTimeUs)
            timeline.cuesAt(sourceTimeUs)
        }

        private fun extract() {
            try {
                var found = false
                var seekMap: SeekMap? = null
                var initialSeekDone = false
                val output = object : ExtractorOutput {
                    override fun track(id: Int, type: Int): TrackOutput {
                        val selected = id == trackId && type == C.TRACK_TYPE_TEXT
                        return SampleOutput(selected, { format ->
                            if (selected) {
                                found = true
                                if (format.sampleMimeType != MimeTypes.APPLICATION_MEDIA3_CUES) {
                                    throw IOException("Unsupported embedded subtitle format: ${format.sampleMimeType}")
                                }
                                synchronized(lock) {
                                    timeline = SubtitleCueTimeline(format.cueReplacementBehavior == Format.CUE_REPLACEMENT_BEHAVIOR_REPLACE)
                                }
                            }
                        }, { timeUs, bytes ->
                            synchronized(lock) {
                                if (selected) {
                                    timeline.add(CueDecoder().decode(timeUs, bytes, 0, bytes.size))
                                    timeline.discardBefore(requestedUs)
                                }
                                // A sparse subtitle track cannot serve as a demux watermark. Use A/V packets too.
                                if (type == C.TRACK_TYPE_VIDEO || type == C.TRACK_TYPE_AUDIO) {
                                    readThroughUs = maxOf(readThroughUs, timeUs)
                                    lock.notifyAll()
                                }
                            }
                        })
                    }
                    override fun endTracks() {
                        if (!found) throw IOException("Selected subtitle track is no longer available")
                    }
                    override fun seekMap(value: SeekMap) { seekMap = value }
                }
                ExtractorSession(context, uri, mimeType, output, cancellation).use { reader ->
                    session = reader
                    while (!closed) {
                        synchronized(lock) {
                            while (!closed && (requestedUs < 0 || readThroughUs >= requestedUs + READ_AHEAD_US)) lock.wait()
                        }
                        val map = seekMap
                        if (!initialSeekDone && map != null) {
                            initialSeekDone = true
                            val target = synchronized(lock) { requestedUs }
                            if (map.isSeekable && target > SEEK_PREROLL_US) {
                                // Like progressive ExoPlayer, use the extractor's index and retain decoder setup.
                                // Preroll restores captions beginning before the requested frame; already-known
                                // longer overlapping captions stay in the timeline as well.
                                reader.seek(target - SEEK_PREROLL_US, map)
                                synchronized(lock) { readThroughUs = -1 }
                            }
                        }
                        if (closed || !reader.read()) break
                    }
                }
                synchronized(lock) { finished = true; lock.notifyAll() }
            } catch (error: Throwable) {
                synchronized(lock) { if (!closed) failure = error; lock.notifyAll() }
            } finally { session = null }
        }

        override fun close() {
            closed = true
            synchronized(lock) { lock.notifyAll() }
            // Close the underlying read before interrupting: content/network I/O may ignore interruption.
            cancellation.cancel()
            worker.interrupt()
        }

        companion object {
            private const val READ_AHEAD_US = 2_000_000L
            private const val SEEK_PREROLL_US = 30_000_000L
        }
    }

    /** Retains only the selected subtitle sample; audio/video payloads are discarded immediately. */
    private class SampleOutput(
        private val retain: Boolean,
        private val onFormat: (Format) -> Unit,
        private val onSample: (Long, ByteArray) -> Unit,
    ) : TrackOutput {
        private var bytes = ByteArrayOutputStream()
        private val scratch = ByteArray(8192)
        override fun format(format: Format) = onFormat(format)
        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
            val count = input.read(scratch, 0, minOf(length, scratch.size))
            if (count == C.RESULT_END_OF_INPUT) {
                if (!allowEndOfInput) throw EOFException()
            } else if (retain) append(scratch, 0, count)
            return count
        }
        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            if (retain) append(data.data, data.position, length)
            data.skipBytes(length)
        }
        private fun append(data: ByteArray, offset: Int, length: Int) {
            if (bytes.size() + length > MAX_SIDECAR_BYTES) throw IOException("Subtitle sample exceeds 16 MiB")
            bytes.write(data, offset, length)
        }
        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
            if (!retain) { onSample(timeUs, ByteArray(0)); return }
            if (cryptoData != null) throw IOException("Encrypted subtitles cannot be decoded")
            val data = bytes.toByteArray()
            val end = data.size - offset
            if (size > end || end < 0) throw IOException("Invalid embedded subtitle sample")
            onSample(timeUs, data.copyOfRange(end - size, end))
            bytes.reset()
            if (offset > 0) bytes.write(data, end, offset)
        }
    }
}
