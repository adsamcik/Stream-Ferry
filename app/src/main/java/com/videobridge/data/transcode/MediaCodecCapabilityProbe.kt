package com.videobridge.data.transcode

import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecList
import android.media.MediaFormat
import com.videobridge.core.transcode.DeviceEncodeCapabilities
import com.videobridge.core.transcode.ResolutionTier
import com.videobridge.core.transcode.VideoCodec

/**
 * Probes this phone's video encoders (via [MediaCodecList] / [MediaCodecInfo.VideoCapabilities]) into the
 * pure-JVM [DeviceEncodeCapabilities] the planner/negotiator consume. Hardware-accelerated encoders are
 * reported per codec as the highest tier each sustains at >= 30 fps (realtime). Separately, codecs with a
 * SOFTWARE encoder are reported for the opt-in CPU band (bounded to 720p — CPU encode rarely keeps up
 * above that).
 */
object MediaCodecCapabilityProbe {

    fun probe(): DeviceEncodeCapabilities {
        val hevc = maxHardwareEncodeTier(MediaFormat.MIMETYPE_VIDEO_HEVC)
        return DeviceEncodeCapabilities(
            h264MaxResolution = maxHardwareEncodeTier(MediaFormat.MIMETYPE_VIDEO_AVC),
            hevcMaxResolution = hevc,
            vp9MaxResolution = maxHardwareEncodeTier(MediaFormat.MIMETYPE_VIDEO_VP9),
            av1MaxResolution = maxHardwareEncodeTier(MediaFormat.MIMETYPE_VIDEO_AV1),
            hevcMain10 = hevc != null && supportsHevcMain10(),
            maxFps = 30,
            softwareCodecs = softwareEncodableCodecs(),
            // CPU encode rarely sustains realtime above 720p; bound the opt-in CPU band conservatively.
            softwareMaxResolution = ResolutionTier.HD_720P,
        )
    }

    private fun maxHardwareEncodeTier(mime: String): ResolutionTier? {
        var best: ResolutionTier? = null
        forEachEncoder(mime, hardwareOnly = true) { vc ->
            val tier = when {
                sizeAndRate(vc, 3840, 2160) -> ResolutionTier.UHD_4K
                sizeAndRate(vc, 1920, 1080) -> ResolutionTier.FHD_1080P
                sizeAndRate(vc, 1280, 720) -> ResolutionTier.HD_720P
                sizeAndRate(vc, 854, 480) -> ResolutionTier.SD_480P
                else -> null
            }
            if (tier != null && (best == null || tier.maxHeightPx > best!!.maxHeightPx)) best = tier
        }
        return best
    }

    /** Codecs the phone has a SOFTWARE encoder for (CPU band). */
    private fun softwareEncodableCodecs(): Set<VideoCodec> = buildSet {
        if (hasSoftwareEncoder(MediaFormat.MIMETYPE_VIDEO_AVC)) add(VideoCodec.H264)
        if (hasSoftwareEncoder(MediaFormat.MIMETYPE_VIDEO_HEVC)) add(VideoCodec.HEVC)
        if (hasSoftwareEncoder(MediaFormat.MIMETYPE_VIDEO_VP9)) add(VideoCodec.VP9)
        if (hasSoftwareEncoder(MediaFormat.MIMETYPE_VIDEO_AV1)) add(VideoCodec.AV1)
    }

    private fun hasSoftwareEncoder(mime: String): Boolean {
        var found = false
        forEachEncoder(mime, hardwareOnly = false) { found = true }
        return found
    }

    private fun supportsHevcMain10(): Boolean {
        var found = false
        forEachHardwareEncoderInfo(MediaFormat.MIMETYPE_VIDEO_HEVC) { caps ->
            if (caps.profileLevels.any { it.profile == CodecProfileLevel.HEVCProfileMain10 }) found = true
        }
        return found
    }

    /** Iterate encoders for [mime]; if [hardwareOnly], only hardware-accelerated ones. */
    private inline fun forEachEncoder(mime: String, hardwareOnly: Boolean, action: (MediaCodecInfo.VideoCapabilities) -> Unit) {
        val infos = runCatching { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos }.getOrNull() ?: return
        for (info in infos) {
            if (!info.isEncoder) continue
            val isHw = runCatching { info.isHardwareAccelerated }.getOrDefault(false)
            if (hardwareOnly && !isHw) continue
            if (!hardwareOnly && isHw) continue // software = not hardware-accelerated
            if (info.supportedTypes.none { it.equals(mime, ignoreCase = true) }) continue
            val caps = runCatching { info.getCapabilitiesForType(mime) }.getOrNull() ?: continue
            caps.videoCapabilities?.let(action)
        }
    }

    private inline fun forEachHardwareEncoderInfo(mime: String, action: (MediaCodecInfo.CodecCapabilities) -> Unit) {
        val infos = runCatching { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos }.getOrNull() ?: return
        for (info in infos) {
            if (!info.isEncoder) continue
            if (runCatching { !info.isHardwareAccelerated }.getOrDefault(true)) continue
            if (info.supportedTypes.none { it.equals(mime, ignoreCase = true) }) continue
            val caps = runCatching { info.getCapabilitiesForType(mime) }.getOrNull() ?: continue
            action(caps)
        }
    }

    private fun sizeAndRate(vc: MediaCodecInfo.VideoCapabilities, width: Int, height: Int): Boolean =
        runCatching { vc.areSizeAndRateSupported(width, height, 30.0) }.getOrDefault(false)
}

