package com.adsamcik.streamferry.diagnostics

import android.content.Context

/**
 * Local diagnostics preferences (app-private). Currently the opt-in **detailed TV communication
 * tracing** toggle: when on, Cast/DLNA request/response traffic is recorded (redacted) into the
 * exportable diagnostics log to help trace playback problems.
 *
 * Off by default. This is purely local diagnostics — nothing is ever sent anywhere; it only leaves the
 * device if the user explicitly exports/shares the log. Cleared by "Delete all app data" (§13).
 */
class DiagnosticsPreferences(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    var tvTracingEnabled: Boolean
        get() = prefs.getBoolean(KEY_TV_TRACING, false)
        set(value) {
            prefs.edit().putBoolean(KEY_TV_TRACING, value).apply()
        }

    fun clear() = prefs.edit().clear().apply()

    companion object {
        private const val FILE_NAME = "jellyfin_bridge_diagnostics"
        private const val KEY_TV_TRACING = "tv_tracing_enabled"
    }
}
