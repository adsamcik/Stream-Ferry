package com.adsamcik.streamferry.ui.state

import com.adsamcik.streamferry.core.stream.Protocol
import com.adsamcik.streamferry.core.stream.TargetCapabilities
import com.adsamcik.streamferry.domain.DiscoveredTarget
import com.adsamcik.streamferry.physical.PhysicalTv
import kotlin.test.Test
import kotlin.test.assertEquals

class PlaybackProtocolUiStateTest {
    private val cast = DiscoveredTarget(
        id = "cast-tv",
        displayName = "Living Room TV",
        protocol = Protocol.CAST,
        capabilities = TargetCapabilities(Protocol.CAST),
        lastTestedStatus = null,
    )
    private val dlna = DiscoveredTarget(
        id = "dlna-tv",
        displayName = "Living Room TV",
        protocol = Protocol.DLNA,
        capabilities = TargetCapabilities(Protocol.DLNA),
        lastTestedStatus = null,
    )
    private val pairedTv = PhysicalTv(
        id = "living-room",
        displayName = "Living Room TV",
        castEndpoint = cast,
        dlnaEndpoint = dlna,
    )

    @Test fun pairedTvOffersBothExplicitConnections() {
        assertEquals(
            listOf(Protocol.CAST, Protocol.DLNA),
            AppUiState(selectedPhysicalTv = pairedTv, selectedTarget = cast).playbackProtocols,
        )
        assertEquals(
            listOf(Protocol.CAST, Protocol.DLNA),
            AppUiState(selectedPhysicalTv = pairedTv, selectedTarget = dlna).playbackProtocols,
        )
    }

    @Test fun singleProtocolOrUnselectedTvDoesNotOfferSwitch() {
        val castOnly = pairedTv.copy(dlnaEndpoint = null)
        assertEquals(listOf(Protocol.CAST), AppUiState(selectedPhysicalTv = castOnly, selectedTarget = cast).playbackProtocols)
        assertEquals(emptyList(), AppUiState(selectedPhysicalTv = pairedTv).playbackProtocols)
    }

    @Test fun anotherTvCannotOfferConnectionsForTheActiveEndpoint() {
        assertEquals(emptyList(), AppUiState(selectedPhysicalTv = pairedTv, selectedTarget = cast.copy(id = "other-tv")).playbackProtocols)
        assertEquals(emptyList(), AppUiState(selectedPhysicalTv = pairedTv.copy(castEndpoint = null), selectedTarget = cast).playbackProtocols)
    }
}
