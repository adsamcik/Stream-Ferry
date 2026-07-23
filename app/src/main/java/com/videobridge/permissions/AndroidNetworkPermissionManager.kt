package com.videobridge.permissions

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.videobridge.domain.NetworkPermissionManager

/**
 * Resolves runtime permission state for local-network access and notifications (§3, §16).
 *
 * ACCESS_LOCAL_NETWORK is a runtime permission introduced for local-network access; it is requested
 * and explained when the app needs the TV to fetch from the phone or to discover Cast/DLNA devices.
 * The app must keep Jellyfin browsing usable even when it is denied/revoked (browsing only needs
 * INTERNET; only TV playback requires local-network access).
 */
class AndroidNetworkPermissionManager(context: Context) : NetworkPermissionManager {

    private val appContext = context.applicationContext

    override fun hasLocalNetworkAccess(): Boolean {
        // The permission constant exists from the API level that introduced local-network access.
        // On earlier devices in our minSdk..targetSdk window the platform grants LAN access without
        // this runtime gate, so treat its absence as "granted" only when the constant is unknown.
        return isGranted(PERMISSION_ACCESS_LOCAL_NETWORK)
    }

    override fun hasNotificationsPermission(): Boolean =
        isGranted(Manifest.permission.POST_NOTIFICATIONS)

    /** Elective: whether the user has granted access to the device video library (local-media gallery). */
    fun hasReadMediaVideo(): Boolean = isGranted(PERMISSION_READ_MEDIA_VIDEO)

    /**
     * Whether the app is exempt from battery optimization ("unrestricted"). When false, aggressive OEM
     * power management (esp. Samsung "deep sleep") can suspend the app's network with the screen off,
     * stalling screen-off casting until the display wakes. Playback works without it — it's a reliability
     * lever the user can grant via [batteryOptimizationRequestIntent].
     */
    fun isBatteryOptimizationExempt(): Boolean = runCatching {
        appContext.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(appContext.packageName) == true
    }.getOrDefault(true) // if the API/service is unavailable, don't nag the user

    /**
     * Intent that asks the system to exempt the app from battery optimization (a user-approved dialog,
     * backed by the REQUEST_IGNORE_BATTERY_OPTIMIZATIONS permission). Falls back to the battery-optimization
     * settings list if the direct request is unavailable on the device.
     */
    fun batteryOptimizationRequestIntent(): Intent =
        runCatching {
            @Suppress("BatteryLife")
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${appContext.packageName}"))
                .takeIf { it.resolveActivity(appContext.packageManager) != null }
        }.getOrNull() ?: Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

    override fun localNetworkStatus(): String {
        if (isGranted(PERMISSION_ACCESS_LOCAL_NETWORK)) return "granted"
        // ACCESS_LOCAL_NETWORK is a forward-declared permission for Local Network Protection. Current
        // Android releases don't enforce it (the OS never prompts and LAN access works without it), so a
        // bare "denied" here is misleading. Only report "denied" when the platform actually defines it as
        // a dangerous, runtime-grantable permission on this device; otherwise say it isn't enforced.
        val isRuntimePermission = runCatching {
            appContext.packageManager.getPermissionInfo(PERMISSION_ACCESS_LOCAL_NETWORK, 0)
                .protection == PermissionInfo.PROTECTION_DANGEROUS
        }.getOrDefault(false)
        return if (isRuntimePermission) "denied" else "not enforced (Android ${Build.VERSION.RELEASE})"
    }

    private fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED

    companion object {
        /**
         * String name for the local-network access permission. Kept as a literal so the module
         * still compiles on toolchains where the Manifest constant is not yet present, while the
         * actual grant check is resolved at runtime against the installed platform.
         */
        const val PERMISSION_ACCESS_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"

        /** Elective media-library permission backing the optional "all device videos" local gallery. */
        const val PERMISSION_READ_MEDIA_VIDEO = "android.permission.READ_MEDIA_VIDEO"

        /** Permissions the UI should request before attempting TV playback. */
        val PLAYBACK_PERMISSIONS: Array<String> = arrayOf(
            PERMISSION_ACCESS_LOCAL_NETWORK,
            Manifest.permission.POST_NOTIFICATIONS, // runtime-grantable since API 33; always present at minSdk 34
        )
    }
}
