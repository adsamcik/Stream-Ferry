package com.adsamcik.streamferry.playback

import android.app.Notification
import android.media.session.MediaSession
import android.media.session.PlaybackState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
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
            assertEquals(session.sessionToken, notification.extras.getParcelable(Notification.EXTRA_MEDIA_SESSION, MediaSession.Token::class.java))
            assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
            assertEquals("Movie", notification.extras.getString(Notification.EXTRA_TITLE))
            assertEquals("Playing on Living room TV", notification.extras.getString(Notification.EXTRA_TEXT))
            assertEquals(
                listOf(
                    PlaybackNotificationFactory.ACTION_RWND,
                    PlaybackNotificationFactory.ACTION_PAUSE,
                    PlaybackNotificationFactory.ACTION_FFWD,
                    PlaybackNotificationFactory.ACTION_STOP,
                ),
                notification.actions.map { shadowOf(it.actionIntent).savedIntent.action },
            )
            assertTrue(notification.extras.getIntArray(Notification.EXTRA_COMPACT_ACTIONS)!!.contentEquals(intArrayOf(0, 1, 2)))

            val state = PlaybackNotificationFactory(context, session.sessionToken, null)
                .addSystemControls(PlaybackState.Builder(), canSkip = true).build()
            assertEquals(
                listOf(PlaybackNotificationFactory.ACTION_RWND, PlaybackNotificationFactory.ACTION_FFWD, PlaybackNotificationFactory.ACTION_STOP),
                state.customActions.map { it.action },
            )
        } finally {
            session.release()
        }
    }
}
