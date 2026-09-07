package com.adsamcik.streamferry.ui.state

import com.adsamcik.streamferry.core.stream.Protocol
import com.adsamcik.streamferry.core.stream.TargetCapabilities
import com.adsamcik.streamferry.domain.DiscoveredTarget
import com.adsamcik.streamferry.physical.PhysicalTv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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

    @Test fun pairedTvOffersOnlyTheOtherProtocol() {
        assertEquals(
            Protocol.DLNA,
            AppUiState(selectedPhysicalTv = pairedTv, selectedTarget = cast).alternatePlaybackProtocol,
        )
        assertEquals(
            Protocol.CAST,
            AppUiState(selectedPhysicalTv = pairedTv, selectedTarget = dlna).alternatePlaybackProtocol,
        )
    }

    @Test fun singleProtocolOrUnselectedTvDoesNotOfferSwitch() {
        val castOnly = pairedTv.copy(dlnaEndpoint = null)
        assertNull(AppUiState(selectedPhysicalTv = castOnly, selectedTarget = cast).alternatePlaybackProtocol)
        assertNull(AppUiState(selectedPhysicalTv = pairedTv).alternatePlaybackProtocol)
    }
}
