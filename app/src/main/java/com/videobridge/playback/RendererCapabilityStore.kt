package com.videobridge.playback

import android.content.Context
import com.videobridge.core.stream.MediaProfile

/**
 * Remembers qualified direct-play decode failures per renderer and media format, so the next matching
 * playback can skip a proven-bad attempt. The key deliberately includes the format tuple rather than only
 * a container: a TV that cannot decode 10-bit HEVC in MKV may still play 8-bit H.264 in MKV perfectly.
 *
 * Entries are recorded only from explicit renderer decode/source-not-supported evidence. Transport,
 * proxy, CORS, generic idle, and startup-timeout failures may still trigger a one-off recovery, but must
 * never create a persistent downgrade. The store contains no title, media id, URL, or token.
 */
interface RendererCapabilityStore {
    /** True if [deviceKey] has previously rejected this exact direct-play [format]. */
    fun shouldForceTranscode(deviceKey: String, format: RendererMediaFormat): Boolean

    /** Record qualified evidence that [deviceKey] could not directly play [format]. */
    fun recordTranscodeRequired(deviceKey: String, format: RendererMediaFormat)

    /**
     * Compatibility convenience for callers that genuinely have no stream details. PlaybackEngine never
     * uses this broad form: its learned entries always include the complete [RendererMediaFormat].
     */
    @Deprecated("Use the media-format overload so failures do not over-generalize across codecs")
    fun shouldForceTranscode(deviceKey: String, container: String?): Boolean =
        shouldForceTranscode(deviceKey, RendererMediaFormat.legacy(container))

    /** See [shouldForceTranscode] for why normal playback must use the format overload. */
    @Deprecated("Use the media-format overload so failures do not over-generalize across codecs")
    fun recordTranscodeRequired(deviceKey: String, container: String?) =
        recordTranscodeRequired(deviceKey, RendererMediaFormat.legacy(container))

    fun clear()
}

/**
 * The media properties that materially affect a renderer's direct-play decoder path. This is intentionally
 * a value object, not a media identity: it preserves useful learning without recording what the user watched.
 */
data class RendererMediaFormat(
    val container: String?,
    val videoCodec: String? = null,
    val videoProfile: String? = null,
    val videoLevel: Int? = null,
    val bitDepth: Int? = null,
    val isHdr: Boolean? = null,
    val heightPx: Int? = null,
    val audioCodec: String? = null,
    val audioChannels: Int? = null,
) {
    companion object {
        fun from(profile: MediaProfile): RendererMediaFormat = RendererMediaFormat(
            container = profile.container,
            videoCodec = profile.videoCodec,
            videoProfile = profile.videoProfile,
            videoLevel = profile.videoLevel,
            bitDepth = profile.bitDepth,
            isHdr = profile.isHdr,
            heightPx = profile.heightPx,
            audioCodec = profile.audioCodec,
            audioChannels = profile.audioChannels,
        )

        internal fun legacy(container: String?): RendererMediaFormat = RendererMediaFormat(container = container)
    }
}

/**
 * A capability entry is versioned so a future policy/profile revision naturally stops honoring stale,
 * container-only learning from older releases. Field escaping makes a display/model name unable to alter
 * the key structure.
 */
internal fun rendererCapabilityEntry(deviceKey: String, format: RendererMediaFormat): String = listOf(
    CAPABILITY_POLICY_VERSION,
    deviceKey,
    format.container,
    format.videoCodec,
    format.videoProfile,
    format.videoLevel,
    format.bitDepth,
    format.isHdr,
    format.heightPx,
    format.audioCodec,
    format.audioChannels,
).joinToString("|")(::rendererCapabilityEntryPart)

/** Legacy helper retained for source compatibility; it creates a deliberately separate unknown-format key. */
@Deprecated("Use the media-format overload")
internal fun rendererCapabilityEntry(deviceKey: String, container: String?): String =
    rendererCapabilityEntry(deviceKey, RendererMediaFormat.legacy(container))

private fun rendererCapabilityEntryPart(value: Any?): String = value
    ?.toString()
    ?.trim()
    ?.lowercase()
    ?.replace("\\", "\\\\")
    ?.replace("|", "\\|")
    ?: "∅"

/**
 * Persistent learning is permitted only when the target supplied explicit format evidence and the failed
 * stream was an online direct-play attempt. Generic error recovery stays transient by design.
 */
internal fun shouldPersistTranscodeRequirement(
    qualifiedFormatEvidence: Boolean,
    isOnlineDirectPlay: Boolean,
    profile: MediaProfile?,
): Boolean = qualifiedFormatEvidence && isOnlineDirectPlay && profile != null

/** In-memory [RendererCapabilityStore] — used in tests and as a non-persistent fallback. */
class InMemoryRendererCapabilityStore : RendererCapabilityStore {
    private val entries = mutableSetOf<String>()

    override fun shouldForceTranscode(deviceKey: String, format: RendererMediaFormat): Boolean =
        entries.contains(rendererCapabilityEntry(deviceKey, format))

    override fun recordTranscodeRequired(deviceKey: String, format: RendererMediaFormat) {
        entries.add(rendererCapabilityEntry(deviceKey, format))
    }

    override fun clear() = entries.clear()
}

/** SharedPreferences-backed [RendererCapabilityStore]. */
class PersistentRendererCapabilityStore(context: Context) : RendererCapabilityStore {
    private val prefs = context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    override fun shouldForceTranscode(deviceKey: String, format: RendererMediaFormat): Boolean =
        entries().contains(rendererCapabilityEntry(deviceKey, format))

    override fun recordTranscodeRequired(deviceKey: String, format: RendererMediaFormat) {
        val updated = entries().toMutableSet()
        // getStringSet returns a shared instance that must not be mutated in place — always copy first.
        if (updated.add(rendererCapabilityEntry(deviceKey, format))) {
            prefs.edit().putStringSet(KEY_TRANSCODE_REQUIRED_V2, updated).apply()
        }
    }

    override fun clear() = prefs.edit().clear().apply()

    private fun entries(): Set<String> = prefs.getStringSet(KEY_TRANSCODE_REQUIRED_V2, emptySet()).orEmpty()

    companion object {
        private const val FILE_NAME = "video_bridge_renderer_caps"
        // Changing this key intentionally expires the old container-only policy after an app upgrade.
        private const val KEY_TRANSCODE_REQUIRED_V2 = "transcode_required_v2"
    }
}

private const val CAPABILITY_POLICY_VERSION = "v2"
