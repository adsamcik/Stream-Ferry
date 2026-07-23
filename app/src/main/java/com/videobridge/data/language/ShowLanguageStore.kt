package com.videobridge.data.language

import android.content.Context
import com.videobridge.core.language.SubtitleMemory

/**
 * Remembers the audio + subtitle language the user last chose **per show** (a series, or a movie), so
 * the next episode auto-selects the same languages. App-private [android.content.SharedPreferences],
 * keyed by the show id; values are ISO language codes (a subtitle can also be explicitly OFF). Cleared
 * by "Delete all app data" (§13). Nothing here is a secret.
 */
class ShowLanguageStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    /** The remembered audio language for [showKey], or null if none was remembered. */
    fun audioLanguage(showKey: String): String? = prefs.getString(audioKey(showKey), null)

    /** The remembered subtitle choice for [showKey]: [SubtitleMemory.None]/[SubtitleMemory.Off]/language. */
    fun subtitle(showKey: String): SubtitleMemory = when (val v = prefs.getString(subKey(showKey), null)) {
        null -> SubtitleMemory.None
        OFF -> SubtitleMemory.Off
        else -> SubtitleMemory.Language(v)
    }

    /** Remember (or clear, when [language] is null) the audio language for [showKey]. */
    fun rememberAudio(showKey: String, language: String?) {
        val edit = prefs.edit()
        if (language.isNullOrBlank()) edit.remove(audioKey(showKey)) else edit.putString(audioKey(showKey), language)
        edit.apply()
    }

    /** Remember the subtitle choice for [showKey]: a [language], or null to remember "off". */
    fun rememberSubtitle(showKey: String, language: String?) {
        prefs.edit().putString(subKey(showKey), language?.takeIf { it.isNotBlank() } ?: OFF).apply()
    }

    fun clear() = prefs.edit().clear().apply()

    private fun audioKey(showKey: String) = "a:$showKey"
    private fun subKey(showKey: String) = "s:$showKey"

    private companion object {
        const val FILE_NAME = "jellyfin_bridge_show_languages"
        // Sentinel stored when the user turned subtitles OFF for a show (distinct from "nothing remembered").
        const val OFF = "__off__"
    }
}
