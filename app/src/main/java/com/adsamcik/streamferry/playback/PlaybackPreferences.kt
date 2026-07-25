package com.adsamcik.streamferry.playback

import android.content.Context

/**
 * Local, app-private playback preferences. Currently the **Prefer original quality (direct play)**
 * toggle: when on (default), the app first tries to stream the original file directly — advertising a
 * broad codec profile so Jellyfin direct-plays/remuxes when the TV can decode it — and only falls back
 * to a server transcode if the receiver (Cast or DLNA) can't play it. When off, the safe conservative
 * transcode profile is always used (no direct-play attempt or fallback delay). Cleared by "Delete all
 * app data" (§13).
 */
class PlaybackPreferences(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    var preferDirectPlay: Boolean
        get() = prefs.getBoolean(KEY_PREFER_DIRECT_PLAY, true)
        set(value) {
            prefs.edit().putBoolean(KEY_PREFER_DIRECT_PLAY, value).apply()
        }

    /**
     * When on (default), an incompatible local 8-bit SDR file is transcoded on-device (HW) only for a
     * compatible Cast receiver; directly-playable files remain unchanged. HDR/10-bit and non-Cast paths
     * stay direct-play because the active local pipeline has no verified color or DLNA contract. When off,
     * all local files are handed to the TV unchanged.
     */
    var transcodeLocalOnDevice: Boolean
        get() = prefs.getBoolean(KEY_TRANSCODE_LOCAL, true)
        set(value) {
            prefs.edit().putBoolean(KEY_TRANSCODE_LOCAL, value).apply()
        }

    /**
     * Maximum video height (in pixels) to stream to the TV: 2160 (4K, the default), 1080, 720 or 480.
     * The server (or on-device transcoder) downscales anything taller. At 4K the app also passes HEVC
     * 10-bit / HDR through and lifts the bitrate cap so a high-bitrate 4K HDR source isn't force-transcoded
     * (H.264 can't carry HDR); a lower cap uses the compatibility-first path at that resolution.
     */
    var maxVideoHeight: Int
        get() = prefs.getInt(KEY_MAX_VIDEO_HEIGHT, 2160)
        set(value) {
            prefs.edit().putInt(KEY_MAX_VIDEO_HEIGHT, value).apply()
        }

    /**
     * Retained setting for the experimental remote phone-transcode path. It is currently safety-gated
     * while authenticated redirects cannot yet be enforced inside the Media3 source. Local files remain
     * eligible for on-device transcode (see [transcodeLocalOnDevice]).
     */
    var transcodeOnlineOnDevice: Boolean
        get() = prefs.getBoolean(KEY_TRANSCODE_ONLINE, false)
        set(value) {
            prefs.edit().putBoolean(KEY_TRANSCODE_ONLINE, value).apply()
        }

    /**
     * When on, on-device transcoding may use a **software (CPU)** encoder if no hardware encoder fits —
     * the lowest-priority engine. Off by default (hardware-only): CPU encode is slow and rarely keeps up
     * with live casting above low resolutions, so it's opt-in.
     */
    var onDeviceAllowCpu: Boolean
        get() = prefs.getBoolean(KEY_ONDEVICE_ALLOW_CPU, false)
        set(value) {
            prefs.edit().putBoolean(KEY_ONDEVICE_ALLOW_CPU, value).apply()
        }

    /**
     * When on (default), finishing an episode automatically starts the next episode in the series (on the
     * same TV). Off leaves the app on the gallery when an episode ends. Only applies to Jellyfin episodes.
     */
    var autoPlayNextEpisode: Boolean
        get() = prefs.getBoolean(KEY_AUTOPLAY_NEXT, true)
        set(value) {
            prefs.edit().putBoolean(KEY_AUTOPLAY_NEXT, value).apply()
        }

    /**
     * When on (default), the app auto-skips intro/outro/recap segments provided by Jellyfin's Media
     * Segments API (10.10+, Intro Skipper plugin): it seeks past a segment as soon as playback enters it
     * (once per segment). A manual "Skip" button is always offered while inside a segment regardless.
     */
    var autoSkipSegments: Boolean
        get() = prefs.getBoolean(KEY_AUTO_SKIP_SEGMENTS, true)
        set(value) {
            prefs.edit().putBoolean(KEY_AUTO_SKIP_SEGMENTS, value).apply()
        }

    /**
     * Preferred audio language (ISO code, e.g. "eng"/"jpn"), or empty for none. When set, playback
     * auto-selects the matching audio track if the media has one; otherwise the server default is kept.
     * A per-show memory (last language used for that series/movie) overrides this.
     */
    var preferredAudioLanguage: String
        get() = prefs.getString(KEY_PREFERRED_AUDIO_LANG, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_PREFERRED_AUDIO_LANG, value).apply()
        }

    /**
     * Preferred subtitle language (ISO code), or empty for none/off. When set, playback auto-enables the
     * matching subtitle (burned in) if the media has one. A per-show memory overrides this.
     */
    var preferredSubtitleLanguage: String
        get() = prefs.getString(KEY_PREFERRED_SUBTITLE_LANG, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_PREFERRED_SUBTITLE_LANG, value).apply()
        }

    fun clear() = prefs.edit().clear().apply()

    companion object {
        private const val FILE_NAME = "jellyfin_bridge_playback"
        private const val KEY_PREFER_DIRECT_PLAY = "prefer_direct_play"
        private const val KEY_TRANSCODE_LOCAL = "transcode_local_on_device"
        private const val KEY_ALLOW_4K_HDR = "allow_4k_hdr"
        private const val KEY_MAX_VIDEO_HEIGHT = "max_video_height"
        private const val KEY_TRANSCODE_ONLINE = "transcode_online_on_device"
        private const val KEY_ONDEVICE_ALLOW_CPU = "ondevice_allow_cpu"
        private const val KEY_AUTOPLAY_NEXT = "autoplay_next_episode"
        private const val KEY_AUTO_SKIP_SEGMENTS = "auto_skip_segments"
        private const val KEY_PREFERRED_AUDIO_LANG = "preferred_audio_language"
        private const val KEY_PREFERRED_SUBTITLE_LANG = "preferred_subtitle_language"
    }
}
