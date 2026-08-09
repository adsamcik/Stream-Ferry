package com.adsamcik.streamferry.data.jellyfin

import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class JellyfinUserDataMutationTest {

    @Test
    fun resetProgressPayloadClearsPositionAndWatchedState() {
        val payload = resetProgressUserDataPayload()

        assertEquals(setOf("PlaybackPositionTicks", "Played"), payload.keys)
        assertEquals("0", payload["PlaybackPositionTicks"]?.jsonPrimitive?.content)
        assertEquals("false", payload["Played"]?.jsonPrimitive?.content)
    }
}
