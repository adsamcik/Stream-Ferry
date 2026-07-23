package com.videobridge.playback

import android.content.Context

/**
 * Remembers, per renderer + source container, that an optimistic direct play failed and a server
 * transcode was required — so the next play of that format to that renderer **skips the doomed
 * direct-play attempt** (no error flash, no fallback delay). Learned empirically from real playback
 * failures, which is more reliable than trusting a static capability table or a DLNA `GetProtocolInfo`
 * hint. Small and privacy-safe: it stores only "(device, container) needed a transcode" facts, never
 * any media identity, URL, or token. Cleared by "Delete all app data".
 */
interface RendererCapabilityStore {
    /** True if [deviceKey] has previously failed to direct-play [container] (so transcode up front). */
    fun shouldForceTranscode(deviceKey: String, container: String?): Boolean

    /** Record that [deviceKey] couldn't direct-play [container] and needed a server transcode. */
    fun recordTranscodeRequired(deviceKey: String, container: String?)

    fun clear()
}

/** The stored key for a (renderer, container) pair; a null container is a device-wide wildcard. */
internal fun rendererCapabilityEntry(deviceKey: String, container: String?): String = "$deviceKey|${container ?: "*"}"

/** In-memory [RendererCapabilityStore] — used in tests and as a non-persistent fallback. */
class InMemoryRendererCapabilityStore : RendererCapabilityStore {
    private val entries = mutableSetOf<String>()
    override fun shouldForceTranscode(deviceKey: String, container: String?) =
        entries.contains(rendererCapabilityEntry(deviceKey, container))
    override fun recordTranscodeRequired(deviceKey: String, container: String?) {
        entries.add(rendererCapabilityEntry(deviceKey, container))
    }
    override fun clear() = entries.clear()
}

/** SharedPreferences-backed [RendererCapabilityStore]; entries are `"<deviceKey>|<container>"` strings. */
class PersistentRendererCapabilityStore(context: Context) : RendererCapabilityStore {
    private val prefs = context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    override fun shouldForceTranscode(deviceKey: String, container: String?): Boolean =
        entries().contains(rendererCapabilityEntry(deviceKey, container))

    override fun recordTranscodeRequired(deviceKey: String, container: String?) {
        val updated = entries().toMutableSet()
        // getStringSet returns a shared instance that must not be mutated in place — always copy first.
        if (updated.add(rendererCapabilityEntry(deviceKey, container))) {
            prefs.edit().putStringSet(KEY_TRANSCODE_REQUIRED, updated).apply()
        }
    }

    override fun clear() = prefs.edit().clear().apply()

    private fun entries(): Set<String> = prefs.getStringSet(KEY_TRANSCODE_REQUIRED, emptySet()).orEmpty()

    companion object {
        private const val FILE_NAME = "video_bridge_renderer_caps"
        private const val KEY_TRANSCODE_REQUIRED = "transcode_required"
    }
}
