package com.adsamcik.streamferry.data.subtitle

import androidx.media3.common.C
import androidx.media3.common.text.Cue
import androidx.media3.extractor.text.CuesWithTiming
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SubtitleCueTimelineTest {
    private fun cues(text: String, start: Long, duration: Long) =
        CuesWithTiming(listOf(Cue.Builder().setText(text).build()), start, duration)

    @Test
    fun `overlapping cues remain until their individual ends and pruning keeps active captions`() {
        val timeline = SubtitleCueTimeline(false)
        timeline.add(cues("first", 100, 300))
        timeline.add(cues("second", 200, 300))
        assertEquals(listOf("first", "second"), timeline.cuesAt(250).map { it.text.toString() })
        timeline.discardBefore(400)
        assertEquals(listOf("second"), timeline.cuesAt(400).map { it.text.toString() })
        assertTrue(timeline.cuesAt(500).isEmpty())
    }

    @Test
    fun `replacement closes indefinite prior cue and explicit empty group clears current cue`() {
        val timeline = SubtitleCueTimeline(true)
        timeline.add(cues("first", 100, C.TIME_UNSET))
        timeline.add(cues("second", 200, C.TIME_UNSET))
        timeline.add(CuesWithTiming(emptyList(), 300, C.TIME_UNSET))
        assertEquals("first", timeline.cuesAt(199).single().text.toString())
        assertEquals("second", timeline.cuesAt(200).single().text.toString())
        assertTrue(timeline.cuesAt(300).isEmpty())
    }
}
