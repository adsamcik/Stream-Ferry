package com.adsamcik.streamferry.data.local

import android.content.Context

/**
 * Persists the user's elective local-media grants: SAF folder (tree) URIs and individual file URIs the
 * user has picked. Only the URI strings are stored (app-private SharedPreferences); the actual read
 * permission is held by the system as a persisted URI grant (taken via
 * [android.content.ContentResolver.takePersistableUriPermission]).
 */
class LocalSourceStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("jellyfin_bridge_local_source", Context.MODE_PRIVATE)

    fun folders(): Set<String> = prefs.getStringSet(KEY_FOLDERS, emptySet())!!.toSortedSet()
    fun files(): Set<String> = prefs.getStringSet(KEY_FILES, emptySet())!!.toSortedSet()

    fun addFolder(uri: String) = mutate(KEY_FOLDERS) { it.add(uri) }
    fun removeFolder(uri: String) = mutate(KEY_FOLDERS) { it.remove(uri) }
    fun addFile(uri: String) = mutate(KEY_FILES) { it.add(uri) }
    fun removeFile(uri: String) = mutate(KEY_FILES) { it.remove(uri) }

    fun clear() {
        prefs.edit().remove(KEY_FOLDERS).remove(KEY_FILES).apply()
    }

    private fun mutate(key: String, op: (MutableSet<String>) -> Unit) {
        val current = prefs.getStringSet(key, emptySet())!!.toMutableSet()
        op(current)
        prefs.edit().putStringSet(key, current).apply()
    }

    private companion object {
        const val KEY_FOLDERS = "folders"
        const val KEY_FILES = "files"
    }
}
