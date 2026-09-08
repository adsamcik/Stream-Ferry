package com.adsamcik.streamferry.data.cast

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.junit.runner.RunWith

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CastOptionsProviderTest {
    @Test fun `cast sdk owns background media session without duplicating playback notification`() {
        val options = CastOptionsProvider().getCastOptions(RuntimeEnvironment.getApplication())

        val mediaOptions = assertNotNull(options.castMediaOptions)
        assertTrue(mediaOptions.mediaSessionEnabled)
        assertNull(mediaOptions.notificationOptions)
    }
}
