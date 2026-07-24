package com.videobridge.core

import com.videobridge.core.metadata.MetadataSanitizer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MetadataSanitizerTest {

    @Test fun receiverTitleNormalizesControlsAndWhitespace() {
        assertEquals(
            "A title with controls",
            MetadataSanitizer.receiverTitle("A\u0000 title\nwith\tcontrols"),
        )
    }

    @Test fun receiverTitleCapsByUtf8BytesWithoutSplittingCodePoints() {
        val title = MetadataSanitizer.normalize("€".repeat(200), maxUtf8Bytes = 10)
        assertEquals("€€€", title)
        assertTrue(title.toByteArray(Charsets.UTF_8).size <= 10)
    }

    @Test fun receiverTitleUsesAFallbackForEmptyOrInvalidText() {
        assertEquals(MetadataSanitizer.FALLBACK_TITLE, MetadataSanitizer.receiverTitle("\u0000\t\n"))
    }
}
