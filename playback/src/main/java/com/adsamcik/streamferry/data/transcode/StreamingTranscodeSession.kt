package com.adsamcik.streamferry.data.transcode

import android.annotation.SuppressLint
import android.media.MediaCodec
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Metadata
import androidx.media3.common.MimeTypes
import androidx.media3.muxer.BufferInfo
import androidx.media3.muxer.FragmentedMp4Muxer
import androidx.media3.muxer.Muxer
import com.adsamcik.streamferry.core.hls.MediaPlaylistPlanner
import com.adsamcik.streamferry.core.stream.MediaProfile
import com.adsamcik.streamferry.core.transcode.Fmp4Splitter
import com.adsamcik.streamferry.core.transcode.TranscodeTarget
import com.adsamcik.streamferry.core.transcode.VideoCodec
import com.adsamcik.streamferry.data.proxy.ClientTranscodeSource
import com.adsamcik.streamferry.source.api.DiagnosticSink
import com.google.common.collect.ImmutableList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.Locale

/** A single continuous hardware export, with bounded receiver-paced output for either receiver. */
@SuppressLint("UnsafeOptInUsageError")
class StreamingTranscodeSession(
    private val transcoder: OnDeviceTranscoder,
    private val sourceUri: String,
    private val target: TranscodeTarget,
    private val logger: DiagnosticSink,
    private val hls: Boolean,
    private val startPositionSeconds: Long = 0,
    private val sourceMimeType: String? = null,
    private val sourceProfile: MediaProfile? = null,
    private val onFailure: (Throwable) -> Unit = {},
    private val onReconnectRequired: () -> Unit = {},
    exportDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ClientTranscodeSource {
    private val lock = Object()
    private val scope = CoroutineScope(SupervisorJob() + exportDispatcher)
    private val owner = Any()
    private val ready = CompletableDeferred<Unit>()
    private var started = false
    @Volatile private var released = false
    private var finished = false
    private var failure: Throwable? = null
    private var init: ByteArray? = null
    private var codecInfo: Fmp4Splitter.CodecInfo? = null
    private val fragmentNormalizer = HlsFragmentNormalizer()
    private data class Segment(val index: Int, val bytes: ByteArray, val seconds: Double)
    private val segments = ArrayDeque<Segment>()
    private var nextIndex = 0
    private var receiverCursor = -1
    private var peakBandwidthBps = 0L
    private var bufferedBytes = 0L
    private val packets = ArrayDeque<ByteArray>()
    private var progressiveOpen = false
    private val startupReplay = ByteArrayOutputStream()
    private var replayAvailable = true
    private var reconnectRequested = false

    override val isProgressive: Boolean get() = !hls
    override val segmentContentType: String get() = "video/mp4"

    /** Returns as soon as the first playable media is published, while the encoder keeps running. */
    suspend fun awaitReady() {
        synchronized(lock) {
            check(!released) { "Transcode session is released" }
            if (!started) {
                started = true
                scope.launch {
                    try {
                        val factory = if (hls) fragmentedFactory() else Media3TsMuxer.Factory(::publishPacket)
                        transcoder.transcodeStream(
                            sourceUri, target, TimestampOriginFactory(factory) { track, ptsUs ->
                                if (hls) fragmentNormalizer.setTrackStart(track + 1, ptsUs)
                            },
                            startMs = startPositionSeconds.coerceAtLeast(0) * 1_000,
                            sourceMimeType = sourceMimeType, sourceProfile = sourceProfile,
                            owner = owner, abortIf = { released },
                        )
                        finish(null)
                    } catch (error: Exception) {
                        if (!released) logger.w("transcode", "Continuous on-device stream failed", error)
                        finish(error)
                        if (!released) runCatching { onFailure(error) }
                    }
                }
            }
        }
        try {
            // Without a seek map Media3 must read forward from the beginning. Keep startup bounded,
            // while allowing that catchup at real-time speed before the normal first-media budget.
            val catchupMs = startPositionSeconds.coerceIn(0, (Long.MAX_VALUE - STARTUP_TIMEOUT_MS) / 1_000) * 1_000
            withTimeout(STARTUP_TIMEOUT_MS + catchupMs) { ready.await() }
        } catch (error: Exception) {
            release()
            throw error
        }
    }

    private fun fragmentedFactory(): Muxer.Factory = object : Muxer.Factory {
        override fun create(path: String): Muxer = FragmentedMp4Muxer.Builder(
            FragmentOutput(::publishInit, ::publishSegment),
        // The muxer compares already queued sample timestamps before accepting the next keyframe.
        // A minimum below the encoder's two-second GOP prevents it from skipping that boundary.
        ).setFragmentDurationMs(1_000).build()

        override fun getSupportedSampleMimeTypes(trackType: Int): ImmutableList<String> = when (trackType) {
            C.TRACK_TYPE_VIDEO -> ImmutableList.of(MimeTypes.VIDEO_H264, MimeTypes.VIDEO_H265)
            C.TRACK_TYPE_AUDIO -> ImmutableList.of(MimeTypes.AUDIO_AAC)
            else -> ImmutableList.of()
        }
    }

    private fun publishInit(bytes: ByteArray) = synchronized(lock) {
        checkOpen()
        require(bytes.size <= MAX_INIT_BYTES && Fmp4Splitter.isValidInitSegment(bytes)) { "Invalid streaming MP4 init" }
        val info = Fmp4Splitter.codecInfo(bytes)
        val expected = if (target.videoCodec == VideoCodec.H264) setOf("avc1", "avc3") else setOf("hvc1", "hev1")
        require(info?.videoCodec?.substringBefore('.') in expected) { "Encoder changed the requested video format" }
        init = bytes
        fragmentNormalizer.setInitialization(bytes)
        codecInfo = info
        lock.notifyAll()
    }

    private fun publishSegment(encodedBytes: ByteArray) = synchronized(lock) {
        checkOpen()
        val bytes = fragmentNormalizer.normalize(encodedBytes)
        val header = init ?: throw IOException("Streaming fragment has no initialization")
        require(Fmp4Splitter.isValidMediaSegment(bytes)) { "Invalid streaming MP4 fragment" }
        val duration = Fmp4Splitter.mediaDurationSeconds(bytes, Fmp4Splitter.trackTimescales(header))
            ?: throw IOException("Streaming fragment has no sample duration")
        require(duration <= TARGET_DURATION_SECONDS) {
            "Streaming fragment duration ${duration}s exceeded its ${TARGET_DURATION_SECONDS}s keyframe bound"
        }
        require(bytes.size <= MAX_BUFFER_BYTES) { "Streaming fragment exceeds buffer budget" }
        while (!released && !finished && (segments.size >= MAX_SEGMENTS || bufferedBytes + bytes.size > MAX_BUFFER_BYTES)) {
            // Retrying the previous two segments is useful only while capacity permits. Retaining a
            // large delivered segment must never prevent production of the receiver's next segment.
            while (segments.peekFirst()?.index?.let { oldest ->
                    oldest < receiverCursor - 2 ||
                        (bufferedBytes + bytes.size > MAX_BUFFER_BYTES && oldest <= receiverCursor)
                } == true &&
                (segments.size >= MAX_SEGMENTS || bufferedBytes + bytes.size > MAX_BUFFER_BYTES)
            ) {
                bufferedBytes -= segments.removeFirst().bytes.size
            }
            if (segments.size < MAX_SEGMENTS && bufferedBytes + bytes.size <= MAX_BUFFER_BYTES) break
            lock.wait()
        }
        checkOpen()
        segments.addLast(Segment(nextIndex++, bytes, duration))
        peakBandwidthBps = maxOf(peakBandwidthBps, (bytes.size * 8.0 / duration * 1.1).toLong())
        bufferedBytes += bytes.size
        ready.complete(Unit)
        lock.notifyAll()
    }

    private fun publishPacket(bytes: ByteArray) = synchronized(lock) {
        while (!released && !finished && bufferedBytes + bytes.size > MAX_TS_BUFFER_BYTES) lock.wait()
        checkOpen()
        packets.addLast(bytes)
        bufferedBytes += bytes.size
        val pid = if (bytes.size >= 4) ((bytes[1].toInt() and 0x1f) shl 8) or (bytes[2].toInt() and 0xff) else -1
        if (pid == 0x100 || pid == 0x101) ready.complete(Unit)
        lock.notifyAll()
    }

    private fun finish(error: Throwable?) = synchronized(lock) {
        finished = true
        failure = error
        if (!ready.isCompleted) ready.completeExceptionally(error ?: IOException("The source produced no video"))
        lock.notifyAll()
    }

    override fun playlist(proxyBase: String, allowExport: Boolean): String = synchronized(lock) {
        val info = codecInfo
        MediaPlaylistPlanner().buildMasterPlaylist(
            "$proxyBase/stream?seg=media", info?.hlsCodecs ?: if (target.videoCodec == VideoCodec.H264) "avc1" else "hvc1",
            info?.width, info?.height,
            peakBandwidthBps.takeIf { it > 0 }?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt() ?: 100_000_000,
        )
    }

    override fun mediaPlaylist(proxyBase: String): String = synchronized(lock) {
        buildString {
            append("#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-TARGETDURATION:$TARGET_DURATION_SECONDS\n")
            append("#EXT-X-MEDIA-SEQUENCE:${segments.peekFirst()?.index ?: nextIndex}\n")
            append("#EXT-X-START:TIME-OFFSET=0,PRECISE=YES\n")
            append("#EXT-X-MAP:URI=\"$proxyBase/stream?seg=init\"\n")
            for (segment in segments) {
                append("#EXTINF:${String.format(Locale.US, "%.6f", segment.seconds)},\n")
                append("$proxyBase/stream?seg=${segment.index}\n")
            }
            if (finished && failure == null) append("#EXT-X-ENDLIST\n")
        }
    }

    override fun initSegment(allowExport: Boolean): ClientTranscodeSource.Resource = synchronized(lock) {
        init?.let { ClientTranscodeSource.Resource.Ready(it) } ?: ClientTranscodeSource.Resource.Unavailable
    }

    override fun initSegment(index: Int, allowExport: Boolean): ClientTranscodeSource.Resource = initSegment(allowExport)

    override fun mediaSegment(index: Int, allowExport: Boolean): ClientTranscodeSource.Resource = synchronized(lock) {
        val deadline = System.nanoTime() + READ_TIMEOUT_MS * 1_000_000
        while (allowExport && !released && !finished && index >= nextIndex) {
            val remainingMs = (deadline - System.nanoTime()) / 1_000_000
            if (remainingMs <= 0) return@synchronized ClientTranscodeSource.Resource.TimedOut
            lock.wait(remainingMs.coerceAtLeast(1))
        }
        val segment = segments.firstOrNull { it.index == index }
            ?: return@synchronized if (index < nextIndex) ClientTranscodeSource.Resource.NotFound
            else ClientTranscodeSource.Resource.Unavailable
        if (allowExport) {
            receiverCursor = maxOf(receiverCursor, index)
            // A receiver can start above zero or skip segments. Follow its progress with a bounded
            // two-segment retry window, rather than waiting forever for every earlier index.
            while (segments.peekFirst()?.index?.let { it < receiverCursor - 2 } == true) {
                bufferedBytes -= segments.removeFirst().bytes.size
            }
            lock.notifyAll()
        }
        ClientTranscodeSource.Resource.Ready(segment.bytes)
    }

    override fun openProgressive(): InputStream? {
        var requestReconnect = false
        val stream = synchronized(lock) {
            if (hls || released || progressiveOpen) return@synchronized null
            if (!replayAvailable) {
                // Reopening at byte zero requires a new encoder and URL once the bounded startup
                // replay is gone. A second request must never join an existing reader mid-stream.
                if (!reconnectRequested) {
                    reconnectRequested = true
                    requestReconnect = true
                }
                return@synchronized reconnectWaiter()
            }
            progressiveOpen = true
            object : InputStream() {
                private var current: ByteArray? = startupReplay.toByteArray().takeIf { it.isNotEmpty() }
                private var offset = 0
                private var closed = false
                override fun read(): Int {
                    val one = ByteArray(1)
                    return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
                }
                override fun read(bytes: ByteArray, off: Int, len: Int): Int = synchronized(lock) {
                    require(off >= 0 && len >= 0 && off <= bytes.size - len)
                    if (len == 0) return@synchronized 0
                    val deadline = System.nanoTime() + READ_TIMEOUT_MS * 1_000_000
                    while (!closed && !released && current == null && packets.isEmpty() && !finished) {
                        val remainingMs = (deadline - System.nanoTime()) / 1_000_000
                        if (remainingMs <= 0) throw IOException("Timed out waiting for encoded media")
                        lock.wait(remainingMs.coerceAtLeast(1))
                    }
                    if (closed || released) return@synchronized -1
                    if (current == null && packets.isEmpty()) {
                        failure?.let { throw IOException("On-device encoding failed", it) }
                        return@synchronized -1
                    }
                    var count = 0
                    while (count < len && (current != null || packets.isNotEmpty())) {
                        if (current == null) {
                            current = packets.removeFirst()
                            bufferedBytes -= current!!.size
                            if (replayAvailable) {
                                if (startupReplay.size().toLong() + current!!.size <= MAX_TS_BUFFER_BYTES) {
                                    startupReplay.write(current!!)
                                } else {
                                    replayAvailable = false
                                    startupReplay.reset()
                                }
                            }
                            offset = 0
                        }
                        val packet = current!!
                        val copied = minOf(len - count, packet.size - offset)
                        packet.copyInto(bytes, off + count, offset, offset + copied)
                        offset += copied
                        count += copied
                        if (offset == packet.size) current = null
                    }
                    lock.notifyAll()
                    count
                }
                override fun close() = synchronized(lock) {
                    if (closed) return@synchronized
                    closed = true
                    progressiveOpen = false
                    lock.notifyAll()
                }
            }
        }
        // Reloading can release this session and wait for the encoder, which needs this lock.
        if (requestReconnect) runCatching { onReconnectRequired() }.onFailure {
            logger.w("transcode", "Unable to restart the disconnected on-device stream", it)
        }
        return stream
    }

    private fun reconnectWaiter(): InputStream = object : InputStream() {
        private var closed = false

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
        }

        override fun read(bytes: ByteArray, off: Int, len: Int): Int = synchronized(lock) {
            require(off >= 0 && len >= 0 && off <= bytes.size - len)
            if (len == 0) return@synchronized 0
            val deadline = System.nanoTime() + READ_TIMEOUT_MS * 1_000_000
            // Keep the reconnect GET pending while the engine reads the TV's native play/pause
            // state. An immediate HTTP failure could turn PLAYING into STOPPED before that read.
            while (!closed && !released) {
                val remainingMs = (deadline - System.nanoTime()) / 1_000_000
                if (remainingMs <= 0) throw IOException("Timed out waiting for the replacement stream")
                lock.wait(remainingMs.coerceAtLeast(1))
            }
            -1
        }

        override fun close() = synchronized(lock) {
            closed = true
            lock.notifyAll()
        }
    }

    private fun checkOpen() {
        if (released || finished) throw IOException("Transcode stream closed")
    }

    override fun release() {
        synchronized(lock) {
            if (released) return
            released = true
            packets.clear()
            segments.clear()
            startupReplay.reset()
            bufferedBytes = 0
            if (!ready.isCompleted) ready.completeExceptionally(IOException("Transcode stopped"))
            lock.notifyAll()
        }
        transcoder.cancelOwnedInFlightAndAwait(owner)
        scope.cancel()
    }

    private companion object {
        const val STARTUP_TIMEOUT_MS = 45_000L
        const val READ_TIMEOUT_MS = 45_000L
        const val MAX_BUFFER_BYTES = 24 * 1024 * 1024L
        const val MAX_TS_BUFFER_BYTES = 2 * 1024 * 1024L
        const val MAX_INIT_BYTES = 4 * 1024 * 1024
        const val MAX_SEGMENTS = 8
        const val TARGET_DURATION_SECONDS = 3
    }
}

/** Parses complete top-level MP4 boxes as Media3 writes them; a partial mdat is never published. */
internal class FragmentOutput(
    private val onInit: (ByteArray) -> Unit,
    private val onMedia: (ByteArray) -> Unit,
) : OutputStream() {
    private val header = ByteArray(16)
    private var headerSize = 0
    private var boxSize = 0
    private var box: ByteArrayOutputStream? = null
    private val initialization = ByteArrayOutputStream()
    private var moof: ByteArray? = null
    private var initialized = false

    override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)

    override fun write(bytes: ByteArray, off: Int, len: Int) {
        var cursor = off
        val end = off + len
        while (cursor < end) {
            if (box == null) {
                val required = if (headerSize >= 8 && readUnsignedInt(header, 0) == 1L) 16 else 8
                val count = minOf(required - headerSize, end - cursor)
                bytes.copyInto(header, headerSize, cursor, cursor + count)
                cursor += count
                headerSize += count
                if (headerSize < required) continue
                val shortSize = readUnsignedInt(header, 0)
                if (shortSize == 1L && headerSize < 16) continue
                val size = if (shortSize == 1L) {
                    (readUnsignedInt(header, 8) shl 32) or readUnsignedInt(header, 12)
                } else shortSize
                require(size in headerSize.toLong()..MAX_BOX_BYTES.toLong()) { "Invalid or oversized streaming MP4 box" }
                boxSize = size.toInt()
                box = ByteArrayOutputStream(boxSize).apply { write(header, 0, headerSize) }
            }
            val output = box!!
            val count = minOf(boxSize - output.size(), end - cursor)
            output.write(bytes, cursor, count)
            cursor += count
            if (output.size() == boxSize) {
                val complete = output.toByteArray()
                val type = String(complete, 4, 4, Charsets.US_ASCII)
                when (type) {
                    "ftyp", "moov" -> {
                        require(!initialized) { "Duplicate MP4 init" }
                        require(initialization.size() + complete.size <= MAX_INIT_BYTES)
                        initialization.write(complete)
                        if (type == "moov") {
                            initialized = true
                            onInit(initialization.toByteArray())
                        }
                    }
                    "moof" -> { require(moof == null); moof = complete }
                    "mdat" -> {
                        val fragment = moof ?: throw IOException("MP4 mdat without fragment header")
                        require(fragment.size + complete.size <= MAX_BOX_BYTES)
                        onMedia(fragment + complete)
                        moof = null
                    }
                }
                box = null
                headerSize = 0
            }
        }
    }

    override fun close() {
        if (headerSize != 0 || box != null || moof != null) throw IOException("Truncated streaming MP4 output")
    }

    private fun readUnsignedInt(bytes: ByteArray, offset: Int): Long =
        ((bytes[offset].toLong() and 255) shl 24) or ((bytes[offset + 1].toLong() and 255) shl 16) or
            ((bytes[offset + 2].toLong() and 255) shl 8) or (bytes[offset + 3].toLong() and 255)

    private companion object {
        const val MAX_BOX_BYTES = 24 * 1024 * 1024
        const val MAX_INIT_BYTES = 4 * 1024 * 1024
    }
}

/** Rebases both tracks against one origin, preserving their A/V offset after a source seek. */
@SuppressLint("UnsafeOptInUsageError")
internal class TimestampOriginFactory(
    private val factory: Muxer.Factory,
    private val onFirstSample: (Int, Long) -> Unit = { _, _ -> },
) : Muxer.Factory {
    private data class Sample(val track: Int, val bytes: ByteArray, val info: BufferInfo)
    override fun getSupportedSampleMimeTypes(trackType: Int): ImmutableList<String> = factory.getSupportedSampleMimeTypes(trackType)
    override fun create(path: String): Muxer = object : Muxer {
        private val muxer = factory.create(path)
        private val tracks = mutableSetOf<Int>()
        private val seen = mutableSetOf<Int>()
        private val pending = mutableListOf<Sample>()
        private var pendingBytes = 0
        private var originUs: Long? = null
        private val publishedTracks = mutableSetOf<Int>()
        override fun addTrack(format: Format): Int = muxer.addTrack(format).also { tracks += it }
        override fun addMetadataEntry(metadataEntry: Metadata.Entry) = muxer.addMetadataEntry(metadataEntry)
        override fun writeSampleData(trackId: Int, byteBuffer: ByteBuffer, bufferInfo: BufferInfo) {
            if (bufferInfo.size == 0) {
                if (originUs != null && bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    muxer.writeSampleData(trackId, byteBuffer, rebased(bufferInfo))
                }
                return
            }
            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) return
            if (originUs == null) {
                require(pendingBytes + bufferInfo.size <= 24 * 1024 * 1024) { "Encoded tracks did not start together" }
                val bytes = ByteArray(bufferInfo.size).also { byteBuffer.get(it) }
                pending += Sample(trackId, bytes, BufferInfo(bufferInfo.presentationTimeUs, bufferInfo.size, bufferInfo.flags))
                pendingBytes += bytes.size
                seen += trackId
                if (seen.containsAll(tracks)) flushPending()
            } else {
                writeRebased(trackId, byteBuffer, bufferInfo)
            }
        }
        private fun rebased(info: BufferInfo) = BufferInfo((info.presentationTimeUs - originUs!!).coerceAtLeast(0), info.size, info.flags)
        private fun writeRebased(track: Int, bytes: ByteBuffer, info: BufferInfo) {
            val adjusted = rebased(info)
            if (publishedTracks.add(track)) onFirstSample(track, adjusted.presentationTimeUs)
            muxer.writeSampleData(track, bytes, adjusted)
        }
        private fun flushPending() {
            originUs = pending.minOfOrNull { it.info.presentationTimeUs } ?: 0
            pending.forEach { writeRebased(it.track, ByteBuffer.wrap(it.bytes), it.info) }
            pending.clear()
            pendingBytes = 0
        }
        override fun close() {
            if (pending.isNotEmpty()) flushPending()
            muxer.close()
        }
    }
}
