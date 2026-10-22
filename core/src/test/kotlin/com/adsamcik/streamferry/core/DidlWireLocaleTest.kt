package com.adsamcik.streamferry.core

import com.adsamcik.streamferry.core.dlna.DidlLite
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DidlWireLocaleTest {
    @Test fun protocolTimesUseAsciiUnderLocalizedDigitLocales() {
        val previous = Locale.getDefault()
        try {
            for (tag in listOf("ar-EG", "fa-IR")) {
                Locale.setDefault(Locale.forLanguageTag(tag))
                assertEquals("1:02:03", DidlLite.formatDuration(3723))
                val metadata = DidlLite.build("http://phone/video", "Movie", "video/mp4", durationSecs = 3723)
                assertTrue(metadata.contains("duration=\"1:02:03\""))
            }
        } finally {
            Locale.setDefault(previous)
        }
    }
}
