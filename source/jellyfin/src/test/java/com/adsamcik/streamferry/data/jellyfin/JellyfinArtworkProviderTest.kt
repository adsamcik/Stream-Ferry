package com.adsamcik.streamferry.data.jellyfin

import com.adsamcik.streamferry.source.api.DiagnosticSink
import com.adsamcik.streamferry.source.api.SourceInstanceId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import okhttp3.OkHttpClient

class JellyfinArtworkProviderTest {

    @Test
    fun `artwork references are source scoped and contain no native values`() {
        val source = SourceInstanceId(JellyfinSourceBackend.PROVIDER_ID, "server:user")
        val httpClient = OkHttpClient()
        val client = JellyfinClient(
            httpClient = httpClient,
            deviceId = "device",
            deviceName = "phone",
            appVersion = "1.0.0",
            logger = object : DiagnosticSink {},
        )
        val provider = JellyfinArtworkProvider(source, client, httpClient)

        val poster = provider.posterForTag("native-item", "image-tag")
        val chapter = provider.chapterForTag("native-item", 3, "chapter-tag")

        assertEquals(source, poster.source)
        assertEquals(source, chapter.source)
        assertNotEquals(poster.opaqueId, chapter.opaqueId)
        assertFalse(poster.opaqueId.contains("native-item"))
        assertFalse(poster.opaqueId.contains("image-tag"))
        assertFalse(chapter.opaqueId.contains("chapter-tag"))
    }
}
