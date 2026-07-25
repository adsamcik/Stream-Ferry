package com.adsamcik.streamferry.branding

import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import com.adsamcik.streamferry.R
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BrandingResourcesTest {

    @Test
    fun currentAdaptiveLauncherArtworkAndBackgroundRemainPackaged() {
        val context = RuntimeEnvironment.getApplication()

        assertIs<BitmapDrawable>(context.getDrawable(R.drawable.ic_launcher_foreground_art_v2))
        assertIs<AdaptiveIconDrawable>(context.getDrawable(R.mipmap.ic_launcher))
        assertIs<AdaptiveIconDrawable>(context.getDrawable(R.mipmap.ic_launcher_round))
        assertEquals(Color.rgb(0x07, 0x3F, 0x4C), context.getColor(R.color.ic_launcher_background))
    }
}
