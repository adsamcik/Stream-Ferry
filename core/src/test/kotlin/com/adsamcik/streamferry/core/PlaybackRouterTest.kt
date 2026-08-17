package com.adsamcik.streamferry.core

import com.adsamcik.streamferry.core.stream.PlayMethod
import com.adsamcik.streamferry.core.stream.StreamDecision
import com.adsamcik.streamferry.core.transcode.PlaybackRouter
import com.adsamcik.streamferry.core.transcode.RouteKind
import com.adsamcik.streamferry.core.transcode.SourceCapabilities
import kotlin.test.Test
import kotlin.test.assertEquals

class PlaybackRouterTest {

    private val router = PlaybackRouter()

    private val jellyfin = SourceCapabilities(
        canServerTranscode = true,
        isSeekable = true,
        isReopenable = true,
        canStreamToClientTranscoder = false,
    )
    private val localFile = SourceCapabilities(
        canServerTranscode = false,
        isSeekable = true,
        isReopenable = true,
        canStreamToClientTranscoder = true,
    )

    private fun decision(method: PlayMethod) =
        StreamDecision(method, burnInSubtitles = false, maxBitrateBps = null, producesHls = method == PlayMethod.HLS_TRANSCODE, rationale = "test")

    @Test fun directPlayAlwaysRoutesDirect() {
        assertEquals(RouteKind.DIRECT_PLAY, router.route(decision(PlayMethod.DIRECT_PLAY), jellyfin).kind)
        assertEquals(RouteKind.DIRECT_PLAY, router.route(decision(PlayMethod.DIRECT_PLAY), localFile).kind)
    }

    @Test fun jellyfinTranscodeUsesServerByDefault() {
        assertEquals(RouteKind.SERVER_TRANSCODE, router.route(decision(PlayMethod.HLS_TRANSCODE), jellyfin).kind)
        assertEquals(RouteKind.SERVER_TRANSCODE, router.route(decision(PlayMethod.AUDIO_TRANSCODE), jellyfin).kind)
    }

    @Test fun jellyfinClientPreferenceFallsBackToServerUntilRemoteInputProviderExists() {
        val route = router.route(decision(PlayMethod.HLS_TRANSCODE), jellyfin, preferClientTranscode = true)
        assertEquals(RouteKind.SERVER_TRANSCODE, route.kind)
    }

    @Test fun localFileTranscodeUsesClient() {
        assertEquals(RouteKind.CLIENT_TRANSCODE, router.route(decision(PlayMethod.HLS_TRANSCODE), localFile).kind)
        assertEquals(RouteKind.CLIENT_TRANSCODE, router.route(decision(PlayMethod.DIRECT_STREAM_REMUX), localFile).kind)
    }

    @Test fun clientPreferredButNotSeekableFallsBackToServer() {
        val nonSeekableServer = SourceCapabilities(canServerTranscode = true, isSeekable = false)
        val route = router.route(decision(PlayMethod.HLS_TRANSCODE), nonSeekableServer, preferClientTranscode = true)
        assertEquals(RouteKind.SERVER_TRANSCODE, route.kind)
    }

    @Test fun noServerAndIneligibleInputIsUnsupported() {
        val nonSeekableLocal = SourceCapabilities(
            canServerTranscode = false,
            isSeekable = false,
            isReopenable = true,
            canStreamToClientTranscoder = true,
        )
        val route = router.route(decision(PlayMethod.HLS_TRANSCODE), nonSeekableLocal)
        assertEquals(RouteKind.UNSUPPORTED, route.kind)
    }

    @Test fun clientTranscodeRequiresReopenableAndStreamableInput() {
        val notReopenable = localFile.copy(isReopenable = false)
        val noInputAdapter = localFile.copy(canStreamToClientTranscoder = false)

        assertEquals(RouteKind.UNSUPPORTED, router.route(decision(PlayMethod.HLS_TRANSCODE), notReopenable).kind)
        assertEquals(RouteKind.UNSUPPORTED, router.route(decision(PlayMethod.HLS_TRANSCODE), noInputAdapter).kind)
    }
}
