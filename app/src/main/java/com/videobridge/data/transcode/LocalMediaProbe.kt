package com.videobridge.data.transcode

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.videobridge.core.stream.MediaProfile

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
            var audioCodec = "aac"
            var audioChannels = 2
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                when {
                    mime.startsWith("video/") && videoCodec == null -> {
                        videoCodec = shortVideoCodec(mime)
                        width = format.intOrNull(MediaFormat.KEY_WIDTH)
                        height = format.intOrNull(MediaFormat.KEY_HEIGHT)
                        if ((format.intOrNull("color-transfer") ?: 0) != 0) bitDepth = 10
                    }
                    mime.startsWith("audio/") -> {
                        audioCodec = shortAudioCodec(mime)
                        audioChannels = format.intOrNull(MediaFormat.KEY_CHANNEL_COUNT) ?: 2
                    }
                }
            }
            val codec = videoCodec ?: return null
            MediaProfile(
                container = container,
                videoCodec = codec,
                bitDepth = bitDepth,
                widthPx = width,
                heightPx = height,
                audioCodec = audioCodec,
                audioChannels = audioChannels,
            )
        } catch (e: Exception) {
            null
        } finally {
            runCatching { extractor.release() }
        }
    }

    /**
     * Best-effort total duration (whole seconds) of a local video, or null if it can't be read. Used to
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
            if (maxUs > 0) maxUs / 1_000_000L else null
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
}
