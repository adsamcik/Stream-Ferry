package com.adsamcik.streamferry.ui.theme

import android.content.Context

/** App-private presentation preferences. Appearance is deliberately independent of account data. */
class AppearancePreferences(context: Context) {

    private val preferences = context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    var themeMode: ThemeMode
        get() = preferences.getString(KEY_THEME_MODE, null)
            ?.let { stored -> ThemeMode.entries.firstOrNull { it.name == stored } }
            ?: ThemeMode.SYSTEM
        set(value) {
            preferences.edit().putString(KEY_THEME_MODE, value.name).apply()
        }

    fun clear() = preferences.edit().clear().apply()

    private companion object {
        const val FILE_NAME = "stream_ferry_appearance"
        const val KEY_THEME_MODE = "theme_mode"
    }
}
