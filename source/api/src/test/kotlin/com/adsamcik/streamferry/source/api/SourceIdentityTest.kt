package com.adsamcik.streamferry.source.api

import com.adsamcik.streamferry.domain.MediaItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class SourceIdentityTest {
    @Test
    fun `media keys are namespaced by provider and configured instance`() {
        val nativeId = "42"
        val first = MediaRef(SourceInstanceId(SourceProviderId("server"), "home"), nativeId)
        val second = MediaRef(SourceInstanceId(SourceProviderId("server"), "cabin"), nativeId)
        val third = MediaRef(SourceInstanceId(SourceProviderId("local"), "phone"), nativeId)

        assertNotEquals(first.storageKey, second.storageKey)
        assertNotEquals(first.storageKey, third.storageKey)
        assertEquals(first, MediaRef(first.source, nativeId))
    }

    @Test
    fun `legacy media exposes a canonical namespaced reference`() {
        val media = MediaItem(
            id = "native-id",
            title = "Title",
            year = null,
            runtimeSeconds = null,
            overview = null,
            resumePositionSeconds = null,
            isFolder = false,
            sourceId = "remote",
            sourceInstanceId = SourceInstanceId(SourceProviderId("remote"), "home-account"),
        )

        assertEquals("native-id", media.ref.nativeId)
        assertEquals("home-account", media.ref.source.value)
    }
}
