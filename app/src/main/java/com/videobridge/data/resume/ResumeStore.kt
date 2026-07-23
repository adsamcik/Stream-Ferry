package com.videobridge.data.resume

import android.content.Context
import com.videobridge.core.resume.ResumePolicy

/**
 * Persists per-item "continue where you left off" positions for on-device local files (which have no
 * server-side resume point). App-private SharedPreferences, bounded to the most-recently-updated
 * [MAX_ENTRIES] items. The key is the caller's stable item id (a `content://` URI for picked files, or
 * `dl:<id>` for a downloaded copy); the value encodes `positionSeconds|durationSeconds|updatedAtMillis`.
 */
class ResumeStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("jellyfin_bridge_resume", Context.MODE_PRIVATE)

    data class Entry(val positionSeconds: Long, val durationSeconds: Long?, val updatedAt: Long)

    /** Save [positionSeconds] for [itemId] if it is worth resuming; otherwise drop any stored point. */
    fun save(itemId: String, positionSeconds: Long, durationSeconds: Long?) {
        if (!ResumePolicy.shouldSave(positionSeconds, durationSeconds)) {
            remove(itemId)
            return
        }
        prefs.edit()
            .putString(itemId, encode(Entry(positionSeconds, durationSeconds, System.currentTimeMillis())))
            .apply()
        evictIfNeeded()
    }

    /** The position to resume [itemId] from per [ResumePolicy], or null to start at the beginning. */
    fun resumePosition(itemId: String): Long? {
        val entry = get(itemId) ?: return null
        return ResumePolicy.resumePosition(entry.positionSeconds, entry.durationSeconds)
    }

    fun get(itemId: String): Entry? = prefs.getString(itemId, null)?.let(::decode)

    fun remove(itemId: String) {
        prefs.edit().remove(itemId).apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private fun evictIfNeeded() {
        val all = prefs.all
        if (all.size <= MAX_ENTRIES) return
        val oldest = all.entries
            .mapNotNull { (k, v) -> (v as? String)?.let(::decode)?.let { k to it.updatedAt } }
            .sortedBy { it.second }
            .take(all.size - MAX_ENTRIES)
            .map { it.first }
        prefs.edit().apply { oldest.forEach { remove(it) } }.apply()
    }

    private fun encode(entry: Entry): String =
        "${entry.positionSeconds}|${entry.durationSeconds ?: -1L}|${entry.updatedAt}"

    private fun decode(raw: String): Entry? {
        val parts = raw.split('|')
        if (parts.size != 3) return null
        val position = parts[0].toLongOrNull() ?: return null
        val duration = parts[1].toLongOrNull()?.takeIf { it >= 0 }
        val updatedAt = parts[2].toLongOrNull() ?: return null
        return Entry(position, duration, updatedAt)
    }

    private companion object {
        const val MAX_ENTRIES = 256
    }
}
