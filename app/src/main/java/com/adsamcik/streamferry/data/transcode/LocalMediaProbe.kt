package com.adsamcik.streamferry.data.transcode

import android.content.Context
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.adsamcik.streamferry.core.stream.MediaProfile

/**
 * Probes a local video's first video+audio tracks into a [MediaProfile] (via [MediaExtractor]) so the
 * pure-JVM stream-selection / routing logic can decide direct-play vs on-device transcode. Returns null
 * if the source can't be read or has no video track. [container] is supplied by the caller (the
 * extractor doesn't expose it reliably).
 */
object LocalMediaProbe {

    fun probe(context: Context, uriString: String, container: String): MediaProfile? {
        val extractor = MediaExtractor()
        return try {
            setDataSource(extractor, context, uriString)
            var videoCodec: String? = null
            var width: Int? = null
            var height: Int? = null
            var bitDepth = 8
            var isHdr = false
            var rotationDegrees: Int? = null
            var pixelWidthHeightRatio: Float? = null
            var frameRate: Float? = null
            var audioCodec = "aac"
            var audioChannels = 2
            var foundAudio = false
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                when {
                    mime.startsWith("video/") && videoCodec == null -> {
                        videoCodec = shortVideoCodec(mime)
                        width = format.intOrNull(MediaFormat.KEY_WIDTH)
                        height = format.intOrNull(MediaFormat.KEY_HEIGHT)
                        // Color transfer describes how pixels are displayed, not how many bits they use.
                        // In particular, ordinary SDR transfer values must never make an 8-bit file look
                        // like Main10 and unnecessarily force a transcode.
                        val transfer = format.intOrNull(MediaFormat.KEY_COLOR_TRANSFER)
                        isHdr = transfer == COLOR_TRANSFER_ST2084 || transfer == COLOR_TRANSFER_HLG
                        bitDepth = bitDepthOf(format, mime)
                        rotationDegrees = format.intOrNull("rotation-degrees")
                        frameRate = format.floatOrNull(MediaFormat.KEY_FRAME_RATE)
                        val sarWidth = format.intOrNull("sar-width")
                        val sarHeight = format.intOrNull("sar-height")
                        pixelWidthHeightRatio = if (sarWidth != null && sarHeight != null && sarWidth > 0 && sarHeight > 0) {
                            sarWidth.toFloat() / sarHeight.toFloat()
                        } else {
                            null
                        }
                    }
                    // Preserve a deterministic first audio stream. The old loop overwrote this with every
                    // later track, so compatibility could depend on incidental container track order.
                    mime.startsWith("audio/") && !foundAudio -> {
                        audioCodec = shortAudioCodec(mime)
                        audioChannels = format.intOrNull(MediaFormat.KEY_CHANNEL_COUNT) ?: 2
                        foundAudio = true
                    }
                }
            }
            val codec = videoCodec ?: return null
            MediaProfile(
                container = container,
                videoCodec = codec,
                bitDepth = bitDepth,
                isHdr = isHdr,
                widthPx = width,
                heightPx = height,
                audioCodec = audioCodec,
                audioChannels = audioChannels,
                rotationDegrees = rotationDegrees,
                pixelWidthHeightRatio = pixelWidthHeightRatio,
                frameRate = frameRate,
            )
        } catch (e: Exception) {
            null
        } finally {
            runCatching { extractor.release() }
        }
    }

    /**
     * Best-effort total duration, rounded *up* to whole seconds, of a local video, or null if it can't be read. Used to
     * build the on-device transcode HLS playlist when the caller has no duration — a SAF-picked file, or a
     * MediaStore entry whose DURATION column was null. Without it the planner emits ZERO segments and the
     * TV sits in LOADING forever (the "local file won't start playing" bug); the duration lives in the
     * container the extractor already reads, so it costs one cheap open.
     */
    fun probeDurationSeconds(context: Context, uriString: String): Long? {
        val extractor = MediaExtractor()
        return try {
            setDataSource(extractor, context, uriString)
            var maxUs = -1L
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                if (format.containsKey(MediaFormat.KEY_DURATION)) {
                    maxUs = maxOf(maxUs, format.getLong(MediaFormat.KEY_DURATION))
                }
            }
            // A floor loses the partial final segment (e.g. 120.9 s became 120 s). The current
            // playback contract uses seconds, so round up rather than advertising a playlist shorter than
            // the source. Use (n - 1) first to avoid overflowing on a malformed very-large duration.
            if (maxUs > 0) ((maxUs - 1L) / MICROS_PER_SECOND) + 1L else null
        } catch (e: Exception) {
            null
        } finally {
            runCatching { extractor.release() }
        }
    }

    /** Open [uriString] on [extractor], handling both a `content://`/`file://` URI and a bare filesystem path. */
    private fun setDataSource(extractor: MediaExtractor, context: Context, uriString: String) {
        if (uriString.startsWith("content://") || uriString.startsWith("file://")) {
            extractor.setDataSource(context, Uri.parse(uriString), null)
        } else {
            extractor.setDataSource(uriString)
        }
    }

    private fun MediaFormat.intOrNull(key: String): Int? = if (containsKey(key)) getInteger(key) else null

    private fun MediaFormat.floatOrNull(key: String): Float? {
        if (!containsKey(key)) return null
        return runCatching { getFloat(key) }
            .recoverCatching { getInteger(key).toFloat() }
            .getOrNull()
    }

    /** Derive bit depth from explicit format/profile metadata; color transfer is intentionally excluded. */
    private fun bitDepthOf(format: MediaFormat, mime: String): Int {
        val declared = sequenceOf("bit-depth", "bit-depth-luma")
            .mapNotNull { format.intOrNull(it) }
            .firstOrNull { it >= 8 }
        if (declared != null) return declared
        val profile = format.intOrNull(MediaFormat.KEY_PROFILE)
        return if (
            mime.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, ignoreCase = true) &&
            profile == CodecProfileLevel.HEVCProfileMain10
        ) {
            10
        } else {
            8
        }
    }

    private fun shortVideoCodec(mime: String): String = when (mime.lowercase()) {
        "video/avc" -> "h264"
        "video/hevc" -> "hevc"
        "video/vp9", "video/x-vnd.on2.vp9" -> "vp9"
        "video/av01" -> "av1"
        "video/mp4v-es" -> "mpeg4"
        else -> mime.substringAfter('/')
    }

    private fun shortAudioCodec(mime: String): String = when (mime.lowercase()) {
        "audio/mp4a-latm" -> "aac"
        "audio/ac3" -> "ac3"
        "audio/eac3" -> "eac3"
        "audio/opus" -> "opus"
        "audio/vorbis" -> "vorbis"
        "audio/flac" -> "flac"
        "audio/raw" -> "pcm"
        else -> mime.substringAfter('/')
    }

    private const val MICROS_PER_SECOND = 1_000_000L
    // Android MediaFormat's public color-transfer values. Keep numeric values here so this code can
    // safely read metadata from older extractor implementations that expose only the string key.
    private const val COLOR_TRANSFER_ST2084 = 6
    private const val COLOR_TRANSFER_HLG = 7
}
