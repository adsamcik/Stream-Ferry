package com.adsamcik.streamferry.data.transcode

import android.annotation.SuppressLint
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Metadata
import androidx.media3.common.MimeTypes
import androidx.media3.muxer.BufferInfo
import androidx.media3.muxer.Muxer
import androidx.media3.muxer.MuxerException
import com.adsamcik.streamferry.core.transcode.StreamingTsMuxer
import com.adsamcik.streamferry.core.transcode.VideoCodec
import com.google.common.collect.ImmutableList
import java.nio.ByteBuffer

/** Streams Media3's encoded samples directly to the receiver; no output file is created. */
@SuppressLint("UnsafeOptInUsageError")
internal class Media3TsMuxer private constructor(
    private val output: (ByteArray) -> Unit,
    private val onClose: () -> Unit,
) : Muxer {
    class Factory(
        private val output: (ByteArray) -> Unit,
        private val onClose: () -> Unit = {},
    ) : Muxer.Factory {
        override fun create(path: String): Muxer = Media3TsMuxer(output, onClose)

        override fun getSupportedSampleMimeTypes(trackType: Int): ImmutableList<String> = when (trackType) {
            C.TRACK_TYPE_VIDEO -> ImmutableList.of(MimeTypes.VIDEO_H264, MimeTypes.VIDEO_H265)
            C.TRACK_TYPE_AUDIO -> ImmutableList.of(MimeTypes.AUDIO_AAC)
            else -> ImmutableList.of()
        }
    }

    private val tracks = mutableListOf<Format>()
    private var transport: StreamingTsMuxer? = null
    private var closed = false

    @Synchronized
    override fun addTrack(format: Format): Int = checked {
        check(!closed && transport == null) { "Tracks must be declared before samples" }
        require(format.sampleMimeType in setOf(MimeTypes.VIDEO_H264, MimeTypes.VIDEO_H265, MimeTypes.AUDIO_AAC))
        require(tracks.none { MimeTypes.getTrackType(it.sampleMimeType) == MimeTypes.getTrackType(format.sampleMimeType) }) {
            "Only one video and one AAC track are supported"
        }
        tracks += format
        tracks.lastIndex
    }

    @Synchronized
    override fun writeSampleData(trackId: Int, byteBuffer: ByteBuffer, bufferInfo: BufferInfo) = checked {
        check(!closed) { "MPEG-TS muxer is closed" }
        require(trackId in tracks.indices) { "Unknown encoded track" }
        // Media3 places codec-specific buffers in Format.initializationData, not sample data.
        if (bufferInfo.size == 0) return@checked
        require(bufferInfo.size > 0 && bufferInfo.size <= byteBuffer.remaining()) { "Truncated encoded sample" }
        val muxer = transport ?: createTransport().also { transport = it }
        val bytes = ByteArray(bufferInfo.size)
        byteBuffer.get(bytes)
        when (tracks[trackId].sampleMimeType) {
            MimeTypes.VIDEO_H264, MimeTypes.VIDEO_H265 -> muxer.writeVideo(
                bytes, bufferInfo.presentationTimeUs, bufferInfo.flags and C.BUFFER_FLAG_KEY_FRAME != 0,
            )
            MimeTypes.AUDIO_AAC -> muxer.writeAudio(bytes, bufferInfo.presentationTimeUs)
        }
    }

    private fun createTransport(): StreamingTsMuxer {
        // Transformer declares all tracks before forwarding samples, including video-only sources.
        val video = tracks.single { MimeTypes.isVideo(it.sampleMimeType) }
        val audio = tracks.singleOrNull { MimeTypes.isAudio(it.sampleMimeType) }
        val aac = audio?.let {
            val config = it.initializationData.firstOrNull()
            if (config != null && config.isNotEmpty()) StreamingTsMuxer.AacConfig.fromAudioSpecificConfig(config)
            else StreamingTsMuxer.AacConfig(it.sampleRate, it.channelCount)
        }
        return StreamingTsMuxer(
            if (video.sampleMimeType == MimeTypes.VIDEO_H264) VideoCodec.H264 else VideoCodec.HEVC,
            video.initializationData, aac, output,
        )
    }

    override fun addMetadataEntry(metadataEntry: Metadata.Entry) = Unit

    @Synchronized
    override fun close() = checked {
        if (!closed) {
            closed = true
            transport?.close()
            onClose()
        }
    }

    private inline fun <T> checked(action: () -> T): T = try {
        action()
    } catch (error: Exception) {
        throw MuxerException("Cannot stream MPEG-TS: ${error.message}", error)
    }
}
