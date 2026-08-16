package com.adsamcik.streamferry.data.jellyfin

import com.adsamcik.streamferry.source.api.MediaRef
import com.adsamcik.streamferry.source.api.MediaUserState
import com.adsamcik.streamferry.source.api.SourceInstanceId
import com.adsamcik.streamferry.source.api.SourceProviderId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JellyfinUserStateProviderTest {

    private val source = SourceInstanceId(JellyfinSourceBackend.PROVIDER_ID, "server:user")

    @Test
    fun `provider maps normalized state to native watch operations`() = runTest {
        val calls = mutableListOf<String>()
        val provider = JellyfinUserStateProvider(
            source = source,
            updatePlayed = { itemId, played -> calls += "played:$itemId:$played" },
            resetProgress = { itemId -> calls += "reset:$itemId" },
        )
        val media = MediaRef(source, "episode-7")

        provider.update(media, MediaUserState(played = true)).getOrThrow()
        provider.update(media, MediaUserState(played = false)).getOrThrow()
        provider.update(media, MediaUserState(played = false, resumePositionSeconds = 0)).getOrThrow()

        assertEquals(
            listOf("played:episode-7:true", "played:episode-7:false", "reset:episode-7"),
            calls,
        )
    }

    @Test
    fun `foreign media is rejected before native mutation`() = runTest {
        var called = false
        val provider = JellyfinUserStateProvider(
            source = source,
            updatePlayed = { _, _ -> called = true },
            resetProgress = { called = true },
        )
        val foreign = MediaRef(SourceInstanceId(SourceProviderId("other"), "account"), "episode-7")

        assertTrue(provider.update(foreign, MediaUserState(played = true)).isFailure)
        assertFalse(called)
    }
}
