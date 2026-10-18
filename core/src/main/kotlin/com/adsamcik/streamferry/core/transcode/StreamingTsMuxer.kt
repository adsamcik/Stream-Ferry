package com.adsamcik.streamferry.core.transcode

import java.io.ByteArrayOutputStream

/**
 * Single-program MPEG-TS for an ongoing hardware encode. Each callback owns a bounded, packet-aligned
 * chunk. Callbacks run synchronously, so the consumer can apply backpressure without retaining video.
 * Every sample is delivered before its write returns; no output waits for another sample or close.
 * Video samples must be in presentation/decode order (the encoder must disable B frames).
 */
class StreamingTsMuxer(
    private val videoCodec: VideoCodec,
    videoInitializationData: List<ByteArray>,
    private val audio: AacConfig?,
    private val output: (ByteArray) -> Unit,
) {
    data class AacConfig(val sampleRate: Int, val channelCount: Int, val audioObjectType: Int = 2) {
        init {
            require(sampleRate in AAC_SAMPLE_RATES) { "Unsupported AAC sample rate: $sampleRate" }
            require(channelCount in 1..6 || channelCount == 8) { "AAC requires a supported channel configuration" }
            require(audioObjectType in 1..4) { "MPEG-TS requires AAC Main, LC, SSR or LTP" }
        }

        companion object {
            /** ADTS describes the AAC core, including when HE-AAC adds SBR/PS above that core. */
            fun fromAudioSpecificConfig(bytes: ByteArray): AacConfig {
                var bit = 0
                fun read(count: Int): Int {
                    require(bit + count <= bytes.size * 8) { "Truncated AAC configuration" }
                    var result = 0
                    repeat(count) {
                        result = (result shl 1) or ((bytes[bit / 8].toInt() ushr (7 - bit % 8)) and 1)
                        bit++
                    }
                    return result
                }
                fun objectType(): Int = read(5).let { if (it == 31) 32 + read(6) else it }
                fun frequency(): Int = read(4).let {
                    if (it == 15) read(24) else AAC_SAMPLE_RATES.getOrNull(it)
                        ?: throw IllegalArgumentException("Invalid AAC sample rate")
                }
                var type = objectType()
                val coreRate = frequency()
                val channels = read(4)
                if (type == 5 || type == 29) {
                    frequency() // SBR output rate; ADTS retains the original AAC core rate.
                    type = objectType()
                }
                require(channels in 1..7) { "AAC program-configuration-element channels are unsupported" }
                return AacConfig(coreRate, if (channels == 7) 8 else channels, type)
            }
        }
    }

    private val continuity = IntArray(8192)
    private val configuration = parseVideoConfiguration(videoInitializationData)
    private var lastTablesUs: Long? = null
    private var lastPcrUs: Long? = null
    private var lastVideoUs: Long? = null
    private var lastAudioUs: Long? = null
    private var closed = false

    init {
        require(videoCodec == VideoCodec.H264 || videoCodec == VideoCodec.HEVC)
    }

    fun writeVideo(sample: ByteArray, presentationTimeUs: Long, keyFrame: Boolean) {
        checkOpen(presentationTimeUs)
        require(lastVideoUs == null || presentationTimeUs >= lastVideoUs!!) {
            "MPEG-TS output requires video without B-frame reordering"
        }
        lastVideoUs = presentationTimeUs
        writeTablesAndClock(presentationTimeUs, keyFrame)
        val accessUnit = ByteArrayOutputStream()
        accessUnit.write(START_CODE)
        // Access unit delimiters make sample boundaries explicit to transport-stream receivers.
        accessUnit.write(if (videoCodec == VideoCodec.H264) byteArrayOf(9, 0xf0.toByte()) else byteArrayOf(0x46, 1, 0x50))
        if (keyFrame) configuration.parameterSets.forEach { accessUnit.write(it) }
        accessUnit.write(toAnnexB(sample, configuration.lengthSize))
        writePes(VIDEO_PID, 0xe0, accessUnit.toByteArray(), presentationTimeUs, keyFrame)
    }

    fun writeAudio(sample: ByteArray, presentationTimeUs: Long) {
        checkOpen(presentationTimeUs)
        val config = checkNotNull(audio) { "No AAC track was declared" }
        require(lastAudioUs == null || presentationTimeUs >= lastAudioUs!!) { "AAC timestamps moved backwards" }
        require(sample.size + 7 <= 8191) { "AAC access unit exceeds the ADTS frame length" }
        lastAudioUs = presentationTimeUs
        writeTablesAndClock(presentationTimeUs, false)
        val frameLength = sample.size + 7
        val frequency = AAC_SAMPLE_RATES.indexOf(config.sampleRate)
        val channels = if (config.channelCount == 8) 7 else config.channelCount
        val header = byteArrayOf(
            0xff.toByte(), 0xf1.toByte(),
            (((config.audioObjectType - 1) shl 6) or (frequency shl 2) or (channels ushr 2)).toByte(),
            (((channels and 3) shl 6) or (frameLength ushr 11)).toByte(),
            (frameLength ushr 3).toByte(), (((frameLength and 7) shl 5) or 0x1f).toByte(), 0xfc.toByte(),
        )
        writePes(AUDIO_PID, 0xc0, header + sample, presentationTimeUs, false)
    }

    fun close() { closed = true }

    private fun checkOpen(timeUs: Long) {
        check(!closed) { "MPEG-TS muxer is closed" }
        // AAC priming can precede the first video frame. A one-second PTS lead preserves it.
        require(timeUs >= -500_000) { "Unexpected negative encoded timestamp" }
    }

    private fun writeTablesAndClock(timeUs: Long, keyFrame: Boolean) {
        if (lastTablesUs == null || keyFrame || timeUs - lastTablesUs!! >= 100_000) {
            writePsi(0, byteArrayOf(0, 0xb0.toByte(), 13, 0, 1, 0xc1.toByte(), 0, 0, 0, 1, 0xf0.toByte(), 0))
            val streams = byteArrayOf(
                if (videoCodec == VideoCodec.H264) 0x1b else 0x24,
                0xe1.toByte(), 0, 0xf0.toByte(), 0,
            ) + if (audio != null) byteArrayOf(0x0f, 0xe1.toByte(), 1, 0xf0.toByte(), 0) else byteArrayOf()
            val sectionLength = 13 + streams.size
            writePsi(PMT_PID, byteArrayOf(
                2, 0xb0.toByte(), sectionLength.toByte(), 0, 1, 0xc1.toByte(), 0, 0,
                0xe1.toByte(), 0, 0xf0.toByte(), 0,
            ) + streams)
            lastTablesUs = maxOf(lastTablesUs ?: timeUs, timeUs)
        }
        // PCR-only packets also cover low-frame-rate sources when AAC is advancing the clock.
        if (lastPcrUs == null || timeUs - lastPcrUs!! >= 40_000) {
            val packet = ByteArray(PACKET_SIZE) { 0xff.toByte() }
            packet[0] = 0x47
            packet[1] = (VIDEO_PID ushr 8).toByte()
            packet[2] = VIDEO_PID.toByte()
            // Adaptation-only packets repeat the previous payload continuity counter.
            packet[3] = (0x20 or ((continuity[VIDEO_PID] - 1).coerceAtLeast(0) and 15)).toByte()
            packet[4] = 183.toByte()
            packet[5] = 0x10
            val base = ticks(timeUs) + PCR_LEAD_TICKS
            packet[6] = (base ushr 25).toByte()
            packet[7] = (base ushr 17).toByte()
            packet[8] = (base ushr 9).toByte()
            packet[9] = (base ushr 1).toByte()
            packet[10] = (((base and 1) shl 7) or 0x7e).toByte()
            packet[11] = 0
            output(packet)
            lastPcrUs = timeUs
        }
    }

    private fun writePsi(pid: Int, section: ByteArray) {
        val crc = crc32(section)
        val payload = byteArrayOf(0) + section + byteArrayOf(
            (crc ushr 24).toByte(), (crc ushr 16).toByte(), (crc ushr 8).toByte(), crc.toByte(),
        )
        writePackets(pid, payload, randomAccess = false)
    }

    private fun writePes(pid: Int, streamId: Int, payload: ByteArray, timeUs: Long, randomAccess: Boolean) {
        val length = if (pid == VIDEO_PID) 0 else payload.size + 8
        val pts = (ticks(timeUs) + PTS_LEAD_TICKS) and 0x1ffffffffL
        val header = byteArrayOf(
            0, 0, 1, streamId.toByte(), (length ushr 8).toByte(), length.toByte(),
            0x84.toByte(), 0x80.toByte(), 5,
            (0x21 or (((pts ushr 30) and 7).toInt() shl 1)).toByte(),
            (pts ushr 22).toByte(), ((((pts ushr 15) and 0x7f) shl 1) or 1).toByte(),
            (pts ushr 7).toByte(), (((pts and 0x7f) shl 1) or 1).toByte(),
        )
        writePackets(pid, header + payload, randomAccess)
    }

    private fun writePackets(pid: Int, payload: ByteArray, randomAccess: Boolean) {
        var position = 0
        while (position < payload.size) {
            val remainingPackets = (payload.size.toLong() - position + 183 +
                (if (position == 0 && randomAccess) 2 else 0)) / 184
            val packetCount = minOf(MAX_PACKETS_PER_CHUNK.toLong(), remainingPackets).toInt()
            val chunk = ByteArray(packetCount * PACKET_SIZE) { 0xff.toByte() }
            repeat(packetCount) { packetIndex ->
                val packetStart = packetIndex * PACKET_SIZE
                val first = position == 0
                val capacity = if (first && randomAccess) 182 else 184
                val count = minOf(capacity, payload.size - position)
                val adaptation = count < 184
                chunk[packetStart] = 0x47
                chunk[packetStart + 1] = ((pid ushr 8) or if (first) 0x40 else 0).toByte()
                chunk[packetStart + 2] = pid.toByte()
                chunk[packetStart + 3] = ((if (adaptation) 0x30 else 0x10) or (continuity[pid]++ and 15)).toByte()
                val offset = if (adaptation) {
                    val length = 183 - count
                    chunk[packetStart + 4] = length.toByte()
                    if (length > 0) chunk[packetStart + 5] = if (first && randomAccess) 0x40 else 0
                    5 + length
                } else 4
                payload.copyInto(chunk, packetStart + offset, position, position + count)
                position += count
            }
            output(chunk)
        }
    }

    private data class VideoConfiguration(val lengthSize: Int, val parameterSets: List<ByteArray>)

    private fun parseVideoConfiguration(data: List<ByteArray>): VideoConfiguration {
        val sets = mutableListOf<ByteArray>()
        var lengthSize = 4
        for (bytes in data) {
            if (bytes.isEmpty()) continue
            if (hasStartCode(bytes)) {
                sets += bytes.copyOf()
            } else if (bytes[0] == 1.toByte() && videoCodec == VideoCodec.H264 && bytes.size >= 7) {
                lengthSize = (bytes[4].toInt() and 3) + 1
                var cursor = 6
                repeat(bytes[5].toInt() and 31) {
                    val size = readUnsigned(bytes, cursor, 2); cursor += 2
                    require(size > 0 && cursor + size <= bytes.size) { "Truncated AVC configuration" }
                    sets += START_CODE + bytes.copyOfRange(cursor, cursor + size); cursor += size
                }
                require(cursor < bytes.size) { "Missing AVC PPS count" }
                val count = bytes[cursor++].toInt() and 0xff
                repeat(count) {
                    val size = readUnsigned(bytes, cursor, 2); cursor += 2
                    require(size > 0 && cursor + size <= bytes.size) { "Truncated AVC configuration" }
                    sets += START_CODE + bytes.copyOfRange(cursor, cursor + size); cursor += size
                }
            } else if (bytes[0] == 1.toByte() && videoCodec == VideoCodec.HEVC && bytes.size >= 23) {
                lengthSize = (bytes[21].toInt() and 3) + 1
                var cursor = 23
                repeat(bytes[22].toInt() and 0xff) {
                    require(cursor < bytes.size) { "Truncated HEVC configuration" }
                    cursor++ // array completeness and NAL unit type
                    val count = readUnsigned(bytes, cursor, 2); cursor += 2
                    repeat(count) {
                        val size = readUnsigned(bytes, cursor, 2); cursor += 2
                        require(size > 0 && cursor + size <= bytes.size) { "Truncated HEVC configuration" }
                        sets += START_CODE + bytes.copyOfRange(cursor, cursor + size); cursor += size
                    }
                }
            } else {
                // MediaCodec also exposes individual, unframed SPS/PPS/VPS buffers on some codecs.
                sets += START_CODE + bytes
            }
        }
        return VideoConfiguration(lengthSize, sets)
    }

    private fun toAnnexB(bytes: ByteArray, lengthSize: Int): ByteArray {
        require(bytes.isNotEmpty()) { "Empty video access unit" }
        if (hasStartCode(bytes)) return bytes
        val output = ByteArrayOutputStream(bytes.size + 16)
        var position = 0
        while (position < bytes.size) {
            val length = readUnsigned(bytes, position, lengthSize); position += lengthSize
            require(length > 0 && length <= bytes.size - position) { "Invalid length-prefixed video access unit" }
            output.write(START_CODE)
            output.write(bytes, position, length)
            position += length
        }
        return output.toByteArray()
    }

    companion object {
        const val PACKET_SIZE = 188
        private const val MAX_PACKETS_PER_CHUNK = 32
        const val VIDEO_PID = 0x100
        const val AUDIO_PID = 0x101
        const val PMT_PID = 0x1000
        private const val PTS_LEAD_TICKS = 90_000L
        private const val PCR_LEAD_TICKS = 45_000L
        private val START_CODE = byteArrayOf(0, 0, 0, 1)
        private val AAC_SAMPLE_RATES = listOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)
        private fun ticks(timeUs: Long): Long = timeUs / 1000 * 90 + timeUs % 1000 * 90 / 1000
        private fun hasStartCode(bytes: ByteArray) = bytes.size >= 3 && bytes[0] == 0.toByte() &&
            bytes[1] == 0.toByte() && (bytes[2] == 1.toByte() || (bytes.size >= 4 && bytes[2] == 0.toByte() && bytes[3] == 1.toByte()))

        private fun readUnsigned(bytes: ByteArray, offset: Int, count: Int): Int {
            require(offset >= 0 && count in 1..4 && offset + count <= bytes.size) { "Truncated video configuration" }
            var value = 0L
            repeat(count) { value = (value shl 8) or (bytes[offset + it].toLong() and 0xff) }
            require(value <= Int.MAX_VALUE) { "Oversized video access unit" }
            return value.toInt()
        }

        /** MPEG-2 section CRC: polynomial 0x04c11db7, initial all ones, no reflected bits or final xor. */
        fun crc32(bytes: ByteArray): Int {
            var crc = -1
            for (byte in bytes) {
                crc = crc xor ((byte.toInt() and 0xff) shl 24)
                repeat(8) { crc = if (crc < 0) (crc shl 1) xor 0x04c11db7 else crc shl 1 }
            }
            return crc
        }
    }
}
