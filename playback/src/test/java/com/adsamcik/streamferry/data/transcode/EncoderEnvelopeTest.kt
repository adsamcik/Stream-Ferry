package com.adsamcik.streamferry.data.transcode

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EncoderEnvelopeTest {
    @Test fun `ultrawide and portrait fit both dimensions`() {
        assertEquals(1920 to 540, fitEncoderEnvelope(3840, 1080, 1920, 1080))
        assertEquals(606 to 1080, fitEncoderEnvelope(1080, 1920, 1920, 1080))
        assertEquals(640 to 360, fitEncoderEnvelope(640, 360, 1920, 1080))
    }

    @Test fun `actual display frames after rotation and pixel aspect correction never upscale`() {
        // Media3 presents 720x576 SAR16:15 as 768x576; a rotated source reverses display sides.
        for ((width, height) in listOf(768 to 576, 576 to 768, 1920 to 1080, 1080 to 1920, 853 to 479)) {
            val (outputWidth, outputHeight) = fitEncoderEnvelope(width, height, 854, 480)
            assertTrue(outputWidth <= minOf(width, 854) && outputHeight <= minOf(height, 480))
            assertEquals(0, outputWidth % 2)
            assertEquals(0, outputHeight % 2)
            // At most one rounded encoder pixel on either side, rather than aspect distortion.
            assertTrue(kotlin.math.abs(outputWidth.toDouble() / outputHeight - width.toDouble() / height) < 0.01)
        }
    }
}
