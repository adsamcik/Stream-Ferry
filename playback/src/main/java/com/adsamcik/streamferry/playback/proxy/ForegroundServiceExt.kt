package com.adsamcik.streamferry.playback.proxy

import android.app.Notification
import android.app.Service

/**
 * Enter the foreground with a typed service. The typed `startForeground(id, notification, type)` overload
 * is required on Android 14+ and is always available at our `minSdk` 34, so no version branch is needed.
 * Shared by [ProxyPlaybackService] and
 * [com.adsamcik.streamferry.data.download.DownloadService] so the call lives in exactly one place.
 *
 * Both services call this as the very first thing in `onStartCommand` to satisfy the
 * `startForegroundService()` deadline (see docs/PROXY_DESIGN.md). The proxy service additionally waits on
 * a barrier before the renderer handshake; the download service is started from a quiet main thread
 * (a user tap), so it doesn't need one.
 */
fun Service.startForegroundCompat(id: Int, notification: Notification, foregroundServiceType: Int) {
    startForeground(id, notification, foregroundServiceType)
}
