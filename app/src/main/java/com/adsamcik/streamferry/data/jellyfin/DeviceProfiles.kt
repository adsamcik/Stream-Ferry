package com.adsamcik.streamferry.data.jellyfin

import com.adsamcik.streamferry.core.stream.TargetCapabilities

/**
 * Builds the Jellyfin DeviceProfile JSON sent in the PlaybackInfo request body. The profile encodes
 * the TARGET TV/renderer's real capabilities (not the phone's), so the server direct-plays when the
 * source is already compatible and otherwise **transcodes server-side to a format the target can
 * actually decode** (§9). The phone only ever proxies bytes.
 *
 * Transcode container is chosen per protocol:
 *  - Cast (supports HLS): an **HLS** H.264/AAC profile (segmented, seekable). The proxy rewrites the
 *    playlist so the TV only ever sees phone proxy URLs.
 *  - DLNA (no HLS): a **progressive MPEG-TS** H.264/AAC profile served as one stream through the
 *    range proxy.
 *
 * Field names follow the documented Jellyfin DeviceProfile schema. Codec/container lists come from the
 * target's [TargetCapabilities] rather than guesswork, so we never advertise a codec the TV can't play.
 * The H.264 transcode target is additionally capped (8-bit, <=1080p, <=L4.2) via CodecProfiles because
 * H.264 is only ever the fallback and 4K/10-bit/high-level H.264 is widely undecodable (Cast error 104).
 */
object DeviceProfiles {

    /**
     * @param caps the target's advertised/known capabilities.
     * @param maxBitrateBps streaming bitrate cap (drives adaptive quality); null -> default.
     * @param forceTranscode when true, advertise no DirectPlay so the server always transcodes.
     * @param allowSubtitleBurnIn allow burning complex/bitmap subtitles into the video (a transcode).
     */
    fun forTarget(
        caps: TargetCapabilities,
        maxBitrateBps: Long?,
        forceTranscode: Boolean,
        allowSubtitleBurnIn: Boolean,
        preferredVideoCodec: String? = null,
        maxVideoHeight: Int = 0,
    ): String {
        val bitrate = maxBitrateBps ?: DEFAULT_MAX_BITRATE
        val containers = caps.supportedContainers.ifEmpty { DEFAULT_CONTAINERS }
        val videoCodecs = caps.supportedVideoCodecs.ifEmpty { DEFAULT_VIDEO }
            .let { if (!caps.supportsHevc) it - HEVC_NAMES else it }
            .ifEmpty { setOf("h264") }
        val audioCodecs = caps.supportedAudioCodecs.ifEmpty { DEFAULT_AUDIO }

        val directPlay = if (forceTranscode) {
            "[]"
        } else {
            """[{"Container":"${containers.joinToString(",")}","Type":"Video",""" +
                """"VideoCodec":"${videoCodecs.joinToString(",")}","AudioCodec":"${audioCodecs.joinToString(",")}"}]"""
        }

        // H.264 transcode: the universal, always-decodable fallback (HLS `ts` for Cast, progressive `ts`
        // over `http` for DLNA). Capped to 8-bit/<=1080p/<=L4.2 via CodecProfiles below. Always offered last.
        // When the TV can decode more efficient codecs, offer them FIRST (best-first: AV1 > VP9 > HEVC) so a
        // forced transcode preserves 4K/10-bit/quality — H.264 can't carry HDR and is bitrate-hungry. If the
        // server's FFmpeg can't produce a listed codec, that attempt fails and playback recovers to the next.
        val tvCodecs = caps.supportedVideoCodecs.map { it.lowercase() }.toSet()
        fun tvHas(vararg names: String) = names.any { it in tvCodecs }
        val transcodeProfiles = buildList {
            if (tvHas("av1")) add(transcodeProfile("av1", caps.supportsHls))
            if (tvHas("vp9")) add(transcodeProfile("vp9", caps.supportsHls))
            if (caps.supportsHevc || tvHas("hevc", "h265")) add(transcodeProfile("hevc", caps.supportsHls))
            add(transcodeProfile("h264", caps.supportsHls))
        }.let { profiles ->
            // A user-preferred codec (manual override) is moved to the FRONT so the server transcodes to it
            // when a transcode is needed; the rest remain as ordered fallbacks. Ignored if the TV can't play it.
            val pref = preferredVideoCodec?.lowercase()
            if (pref == null) profiles
            else profiles.sortedByDescending { it.contains("\"VideoCodec\":\"$pref\"") }
        }
        val transcoding = "[${transcodeProfiles.joinToString(",")}]"

        val subtitles = if (allowSubtitleBurnIn) {
            // Burn-in mode: a subtitle was explicitly selected, so encode EVERY format into the video
            // (Method:"Encode"). Through a phone proxy this is the only universally reliable way to show a
            // subtitle — external text renditions aren't delivered to a DLNA renderer, and burning in works
            // for both Cast and DLNA. It forces a transcode, which is an accepted cost of enabling subtitles.
            """[{"Format":"srt","Method":"Encode"},{"Format":"subrip","Method":"Encode"},""" +
                """{"Format":"vtt","Method":"Encode"},{"Format":"webvtt","Method":"Encode"},""" +
                """{"Format":"ass","Method":"Encode"},{"Format":"ssa","Method":"Encode"},""" +
                """{"Format":"pgssub","Method":"Encode"},{"Format":"dvdsub","Method":"Encode"},""" +
                """{"Format":"dvbsub","Method":"Encode"}]"""
        } else {
            """[{"Format":"vtt","Method":"External"},{"Format":"srt","Method":"External"}]"""
        }

        // H.264 is only ever our TRANSCODE fallback, so constrain it to universally decodable parameters.
        // Transcoding a 4K / 10-bit / high-level source (e.g. HEVC) straight to 4K, High10, or L5+ H.264
        // produces a stream many receivers can't decode — the Chromecast built-in receiver buffers the
        // first segment then reports MEDIA_SRC_NOT_SUPPORTED (104), and playback dies. Cap the H.264 target
        // to 8-bit, <=1080p, <=L4.2 (4K H.264 is essentially never hardware-decodable regardless). A 4K/HDR
        // experience is only reachable via direct play of the original (HEVC), never an H.264 transcode.
        val codecProfileList = mutableListOf(
            """{"Type":"Video","Codec":"h264","Conditions":[""" +
                """{"Condition":"LessThanEqual","Property":"Width","Value":"1920","IsRequired":false},""" +
                """{"Condition":"LessThanEqual","Property":"Height","Value":"1080","IsRequired":false},""" +
                """{"Condition":"LessThanEqual","Property":"VideoBitDepth","Value":"8","IsRequired":false},""" +
                """{"Condition":"LessThanEqual","Property":"VideoLevel","Value":"42","IsRequired":false}]}""",
        )
        // When the target can't render >8-bit / HDR at all, also force every video source (any codec) to
        // 8-bit, so a 10-bit source direct-plays only if SDR and otherwise transcodes down.
        if (!caps.supports10Bit) {
            codecProfileList.add(
                """{"Type":"Video","Conditions":[{"Condition":"LessThanEqual","Property":"VideoBitDepth","Value":"8","IsRequired":false}]}""",
            )
        }
        // User-chosen maximum resolution (applies to EVERY codec, direct play + transcode): the server
        // downscales any source taller than this. 0 = no cap. 4K (2160) still allows a 4K passthrough.
        if (maxVideoHeight > 0) {
            codecProfileList.add(
                """{"Type":"Video","Conditions":[{"Condition":"LessThanEqual","Property":"Height","Value":"$maxVideoHeight","IsRequired":false}]}""",
            )
        }
        val codecProfiles = ""","CodecProfiles":[${codecProfileList.joinToString(",")}]"""

        return (
            """{"MaxStreamingBitrate":$bitrate,""" +
                """"DirectPlayProfiles":$directPlay,""" +
                """"TranscodingProfiles":$transcoding,""" +
                """"SubtitleProfiles":$subtitles$codecProfiles}"""
            )
    }

    /**
     * Returns a DeviceProfile that forces a single-file progressive transcode (Protocol "http", not
     * HLS) suitable for download: no DirectPlay, exactly the requested container/codecs. Jellyfin
     * will return a non-HLS transcodingUrl which the downloader can fetch as a plain byte stream.
     */
    fun forDownload(maxBitrateBps: Long, container: String, videoCodec: String, audioCodec: String): String {
        val subtitles = """[{"Format":"vtt","Method":"External"},{"Format":"srt","Method":"External"}]"""
        return (
            """{"MaxStreamingBitrate":$maxBitrateBps,""" +
                """"DirectPlayProfiles":[],""" +
                """"TranscodingProfiles":[{"Container":"$container","Type":"Video","Protocol":"http",""" +
                """"VideoCodec":"$videoCodec","AudioCodec":"$audioCodec","MaxAudioChannels":"2"}],""" +
                """"SubtitleProfiles":$subtitles}"""
            )
    }

    /**
     * A permissive profile that direct-streams the ORIGINAL file (broad container/codec DirectPlay, no
     * transcoding) so [resolveUpstream] returns the source's own stream URL. Used to feed the untouched
     * Jellyfin original to the ON-DEVICE transcoder (the phone then re-encodes it): the source bytes and
     * auth stay on the phone; the TV only ever gets the phone's transcoded HLS.
     */
    fun forOriginalDirectStream(): String {
        val subtitles = """[{"Format":"vtt","Method":"External"},{"Format":"srt","Method":"External"}]"""
        return (
            """{"MaxStreamingBitrate":200000000,""" +
                """"DirectPlayProfiles":[{"Container":"mp4,mkv,webm,ts,mov,m4v,avi,flv,3gp,m2ts","Type":"Video",""" +
                """"VideoCodec":"h264,hevc,h265,vp9,av1,mpeg4,mpeg2video,vc1","AudioCodec":"aac,mp3,ac3,eac3,opus,flac,vorbis,dts,truehd,pcm,alac"}],""" +
                """"TranscodingProfiles":[],"SubtitleProfiles":$subtitles}"""
            )
    }

    private val DEFAULT_CONTAINERS = setOf("mp4")
    private val DEFAULT_VIDEO = setOf("h264")
    private val DEFAULT_AUDIO = setOf("aac")
    private val HEVC_NAMES = setOf("hevc", "h265")

    /**
     * A single Jellyfin TranscodingProfile for [codec]. Packaging preserves what each receiver decodes
     * best: H.264 uses MPEG-TS (universal). HEVC uses fMP4 for a Cast/HLS target (the Default Receiver
     * decodes HEVC better in CMAF) but MPEG-TS for progressive DLNA (TVs decode HEVC/TS natively). AV1/VP9
     * can't go in MPEG-TS, so they always use fMP4. `hls` protocol for Cast (segmented + seekable through
     * the proxy), else progressive `http` for DLNA.
     */
    private fun transcodeProfile(codec: String, supportsHls: Boolean): String {
        val protocol = if (supportsHls) "hls" else "http"
        val container = when (codec) {
            "h264" -> "ts"
            "hevc" -> if (supportsHls) "mp4" else "ts"
            else -> "mp4" // av1 / vp9: fMP4 only (never MPEG-TS)
        }
        return """{"Container":"$container","Type":"Video","Protocol":"$protocol",""" +
            """"VideoCodec":"$codec","AudioCodec":"aac","MaxAudioChannels":"2"}"""
    }

    private const val DEFAULT_MAX_BITRATE = 20_000_000L
}
