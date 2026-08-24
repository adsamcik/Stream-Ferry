package com.adsamcik.streamferry.playback

import android.app.Notification
import android.media.session.MediaSession
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlaybackNotificationFactoryTest {

    @Test
    fun `media notification is public so controls can appear on lock screen`() {
        val context = RuntimeEnvironment.getApplication()
        val session = MediaSession(context, "notification-test")

        try {
            val notification = PlaybackNotificationFactory(context, session.sessionToken, null).build(
                title = "Movie",
                targetName = "Living room TV",
                phase = PlaybackNotificationFactory.Phase.PLAYING,
            )

            assertEquals(Notification.VISIBILITY_PUBLIC, notification.visibility)
            assertEquals(Notification.CATEGORY_TRANSPORT, notification.category)
        } finally {
            session.release()
        }
    }
}
