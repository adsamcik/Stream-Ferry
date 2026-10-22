package com.adsamcik.streamferry.playback.proxy

import kotlin.test.Test
import kotlin.test.assertEquals

class PlaybackCpuOwnershipTest {
    @Test fun `pause waits for source and producer to drain before releasing`() {
        val changes = mutableListOf<Boolean>()
        val ownership = PlaybackCpuOwnership(changes::add)
        ownership.setPlaybackActive(true)
        val source = ownership.beginWork()
        val producer = ownership.beginWork()
        ownership.setPlaybackActive(false)
        source.close()
        assertEquals(listOf(true), changes)
        producer.close()
        producer.close()
        assertEquals(listOf(true, false), changes)
    }

    @Test fun `paused source request reacquires before work and resume retains ownership`() {
        val changes = mutableListOf<Boolean>()
        val ownership = PlaybackCpuOwnership(changes::add)
        val source = ownership.beginWork()
        assertEquals(listOf(true), changes)
        source.close()
        ownership.setPlaybackActive(true)
        ownership.beginWork().close()
        assertEquals(listOf(true, false, true), changes)
        ownership.setPlaybackActive(false)
        assertEquals(listOf(true, false, true, false), changes)
    }

    @Test fun `late work release from destroyed service cannot release replacement ownership`() {
        val changes = mutableListOf<String>()
        val old = PlaybackCpuOwnership { changes += "old:$it" }
        val work = old.beginWork()
        old.close()
        val replacement = PlaybackCpuOwnership { changes += "new:$it" }
        replacement.setPlaybackActive(true)
        work.close()
        old.beginWork().close()
        assertEquals(listOf("old:true", "old:false", "new:true"), changes)
    }
}
