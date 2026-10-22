package com.adsamcik.streamferry.data.subtitle

import android.graphics.Bitmap
import android.graphics.Color
import androidx.media3.common.text.Cue
import androidx.media3.common.util.Size
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SubtitleOverlayTest {
    @Test fun `clipped output renders text and bitmap captions at original source time and clears expired captions`() {
        val image = Bitmap.createBitmap(32, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.YELLOW) }
        val cues = listOf(
            Cue.Builder().setText("English captions\nPříliš žluťoučký kůň").build(),
            Cue.Builder().setBitmap(image).setPosition(0.1f).setPositionAnchor(Cue.ANCHOR_TYPE_START)
                .setLine(0.1f, Cue.LINE_TYPE_FRACTION).setLineAnchor(Cue.ANCHOR_TYPE_START)
                .setSize(0.15f).setBitmapHeight(0.1f).build(),
        )
        val queried = mutableListOf<Long>()
        val source = object : SubtitleCueSource {
            override fun cuesAt(sourceTimeUs: Long): List<Cue> {
                queried += sourceTimeUs
                return if (sourceTimeUs < 92_000_000) cues else emptyList()
            }
            override fun close() = Unit
        }
        val overlay = SubtitleOverlay(RuntimeEnvironment.getApplication(), source, 90_000_000)
        overlay.configure(Size(1280, 720))
        val rendered = overlay.getBitmap(1_000_000)
        assertEquals(91_000_000, queried.last())
        assertEquals(1280, rendered.width)
        assertTrue((0 until 720).any { y -> (0 until 1280).any { x -> Color.alpha(rendered.getPixel(x, y)) > 0 } })
        assertTrue(Color.alpha(rendered.getPixel(150, 100)) > 0, "bitmap subtitle position was lost")
        assertTrue((500 until 720).any { y -> (200 until 1080).any { x -> Color.alpha(rendered.getPixel(x, y)) > 0 } }, "text captions were not rendered")
        assertSame(rendered, overlay.getBitmap(1_500_000), "unchanged captions should reuse their texture bitmap")
        val file = File("build/subtitle-previews/captions-1280x720.png").apply { parentFile?.mkdirs() }
        file.outputStream().use { rendered.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val cleared = overlay.getBitmap(2_000_000)
        assertTrue((0 until 720).all { y -> (0 until 1280).all { x -> Color.alpha(cleared.getPixel(x, y)) == 0 } })
    }
}
