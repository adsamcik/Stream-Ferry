package com.adsamcik.streamferry.data.volume

import android.content.Context
import com.adsamcik.streamferry.core.volume.NightVolumePolicy
import java.time.LocalTime

/** Small SharedPreferences holder for the optional night-volume configuration. */
class NightVolumeSettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): NightVolumePolicy = runCatching {
        when (prefs.getString(KEY_KIND, KIND_OFF)) {
            KIND_GRADUAL -> NightVolumePolicy.Gradual(
                LocalTime.parse(requireNotNull(prefs.getString(KEY_START, null))),
                LocalTime.parse(requireNotNull(prefs.getString(KEY_END, null))),
                prefs.getFloat(KEY_TARGET, Float.NaN),
            )
            KIND_HARD -> NightVolumePolicy.Hard(
                LocalTime.parse(requireNotNull(prefs.getString(KEY_TIME, null))),
                prefs.getFloat(KEY_TARGET, Float.NaN),
            )
            else -> NightVolumePolicy.Off
        }
    }.getOrElse { NightVolumePolicy.Off }

    /** Used by the app-wide delete-data path. */
    fun clear() { prefs.edit().clear().apply() }

    fun save(policy: NightVolumePolicy) {
        prefs.edit().clear().apply {
            when (policy) {
                NightVolumePolicy.Off -> putString(KEY_KIND, KIND_OFF)
                is NightVolumePolicy.Gradual -> {
                    putString(KEY_KIND, KIND_GRADUAL); putString(KEY_START, policy.start.toString())
                    putString(KEY_END, policy.end.toString()); putFloat(KEY_TARGET, policy.targetVolume)
                }
                is NightVolumePolicy.Hard -> {
                    putString(KEY_KIND, KIND_HARD); putString(KEY_TIME, policy.time.toString())
                    putFloat(KEY_TARGET, policy.targetVolume)
                }
            }
        }.apply()
    }

    private companion object {
        const val PREFS_NAME = "night_volume_settings"
        const val KEY_KIND = "kind"
        const val KEY_START = "start"
        const val KEY_END = "end"
        const val KEY_TIME = "time"
        const val KEY_TARGET = "target"
        const val KIND_OFF = "off"
        const val KIND_GRADUAL = "gradual"
        const val KIND_HARD = "hard"
    }
}
