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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
    private val _localNetworkAccess = MutableStateFlow(readLocalNetworkAccess())
    /** Updated when Android grants or revokes this app's local-network permission. */
    val localNetworkAccess: StateFlow<Boolean> = _localNetworkAccess.asStateFlow()

    /** Refreshes the observable state; AppContainer polls this short-lived platform check for revocation. */
    fun refreshLocalNetworkAccess(): Boolean = readLocalNetworkAccess().also { granted ->
        _localNetworkAccess.value = granted
    }

    override fun hasLocalNetworkAccess(): Boolean = refreshLocalNetworkAccess()

    private fun readLocalNetworkAccess(): Boolean =
        !isRuntimeLocalNetworkPermission() || isGranted(PERMISSION_ACCESS_LOCAL_NETWORK)

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

    /** Directs a denied/revoked LAN user to the app's system permission page. */
    fun appDetailsSettingsIntent(): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${appContext.packageName}"))

    override fun localNetworkStatus(): String {
        if (hasLocalNetworkAccess()) return if (isRuntimeLocalNetworkPermission()) "granted" else "not enforced (Android ${Build.VERSION.RELEASE})"
        return "denied"
    }

    @Suppress("DEPRECATION") // protection-level bitmask remains the compatible public API.
    private fun isRuntimeLocalNetworkPermission(): Boolean = runCatching {
        val protection = appContext.packageManager.getPermissionInfo(PERMISSION_ACCESS_LOCAL_NETWORK, 0).protection
        (protection and PermissionInfo.PROTECTION_MASK_BASE) == PermissionInfo.PROTECTION_DANGEROUS
    }.getOrDefault(false)

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
