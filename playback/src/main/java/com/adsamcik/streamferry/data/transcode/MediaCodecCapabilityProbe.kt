package com.adsamcik.streamferry.data.transcode

import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecList
import android.media.MediaFormat
import com.adsamcik.streamferry.core.transcode.DeviceEncodeCapabilities
import com.adsamcik.streamferry.core.transcode.ResolutionTier

/**
 * Probes this phone's video encoders (via [MediaCodecList] / [MediaCodecInfo.VideoCapabilities]) into the
 * pure-JVM [DeviceEncodeCapabilities] the planner/negotiator consume. Hardware-accelerated encoders are
 * reported per codec as the highest tier each advertises at >= 30 fps. This is capability admission, not
 * a physical realtime or thermal validation guarantee.
 */
object MediaCodecCapabilityProbe {

    fun probe(): DeviceEncodeCapabilities {
        val hevc = maxHardwareEncodeTier(MediaFormat.MIMETYPE_VIDEO_HEVC)
        return DeviceEncodeCapabilities(
            h264MaxResolution = maxHardwareEncodeTier(MediaFormat.MIMETYPE_VIDEO_AVC),
            hevcMaxResolution = hevc,
            hevcMain10 = hevc != null && supportsHevcMain10(),
            maxFps = 30,
        )
    }

    private fun maxHardwareEncodeTier(mime: String): ResolutionTier? {
        var best: ResolutionTier? = null
        forEachEncoder(mime) { vc ->
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

    private fun supportsHevcMain10(): Boolean {
        var found = false
        forEachHardwareEncoderInfo(MediaFormat.MIMETYPE_VIDEO_HEVC) { caps ->
            if (caps.profileLevels.any { it.profile == CodecProfileLevel.HEVCProfileMain10 }) found = true
        }
        return found
    }

    /** Iterate hardware-accelerated encoders for [mime]. */
    private inline fun forEachEncoder(mime: String, action: (MediaCodecInfo.VideoCapabilities) -> Unit) {
        val infos = runCatching { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos }.getOrNull() ?: return
        for (info in infos) {
            if (!info.isEncoder) continue
            if (runCatching { !info.isHardwareAccelerated }.getOrDefault(true)) continue
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

