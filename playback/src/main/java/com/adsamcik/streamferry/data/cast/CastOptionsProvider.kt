package com.adsamcik.streamferry.data.cast

import android.content.Context
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.cast.framework.media.CastMediaOptions

/**
 * Cast configuration (§10).
 *
 * Receiver decision record: we use the Default Media Receiver, whose application ID is provided by
 * [CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID]. The Default Media Receiver does
 * NOT require Cast Developer Console registration, supports byte-range HTTP + HLS, and is sufficient
 * for the MVP because the TV only ever loads a plain phone proxy URL. A Styled/Custom Receiver
 * (which WOULD require registration) is deferred until receiver-side probing or advanced
 * subtitle/audio handling is needed (see docs/CAST.md).
 */
class CastOptionsProvider : OptionsProvider {

    override fun getCastOptions(context: Context): CastOptions {
        val mediaOptions = CastMediaOptions.Builder()
            // Stream Ferry owns one media session + foreground notification for both Cast and DLNA.
            // Its transport and remote-volume callbacks route through PlaybackEngine to the selected
            // renderer, including while the activity is stopped. Avoid competing SDK controls.
            .setMediaSessionEnabled(false)
            .setNotificationOptions(null)
            .build()
        return CastOptions.Builder()
            .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
            .setCastMediaOptions(mediaOptions)
            // We never resume a Cast session that points at a stale proxy URL after process death;
            // resumption is handled explicitly by PlaybackSessionCoordinator instead.
            .setResumeSavedSession(false)
            .setEnableReconnectionService(true)
            .build()
    }

    override fun getAdditionalSessionProviders(context: Context): MutableList<SessionProvider>? = null
}
