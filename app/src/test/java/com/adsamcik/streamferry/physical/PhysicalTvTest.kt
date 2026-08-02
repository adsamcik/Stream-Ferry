package com.adsamcik.streamferry.physical

import com.adsamcik.streamferry.core.stream.Protocol
import com.adsamcik.streamferry.core.stream.TargetCapabilities
import com.adsamcik.streamferry.domain.DiscoveredTarget
import com.adsamcik.streamferry.domain.TargetDiscoveryMetadata
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PhysicalTvTest {
    @Test fun confidentMatchNeedsValidatedHostAndSpecificModel() {
        val match = PhysicalTvMatcher.match(cast(host = "192.168.1.20", model = "Sony XR-55A80L"), dlna(host = "192.168.1.20", model = "Sony XR-55A80L"))
        assertEquals(PhysicalMatchOutcome.CONFIDENT, match.outcome)
        assertTrue(PhysicalMatchReason.MATCHING_VALIDATED_HOST_AND_MODEL in match.reasons)
    }

    @Test fun hostEqualityAloneIsAmbiguous() {
        val match = PhysicalTvMatcher.match(cast(host = "192.168.1.20", model = "Sony XR-55A80L"), dlna(host = "192.168.1.20", model = "LG OLED C4"))
        assertEquals(PhysicalMatchOutcome.AMBIGUOUS, match.outcome)
        assertTrue(PhysicalMatchReason.SHARED_HOST_WITHOUT_INDEPENDENT_DEVICE_EVIDENCE in match.reasons)
    }

    @Test fun sameNameAloneNeverMerges() {
        val match = PhysicalTvMatcher.match(cast(name = "Living Room", host = "192.168.1.20"), dlna(name = "Living Room", host = "192.168.1.30"))
        assertEquals(PhysicalMatchOutcome.NO_MATCH, match.outcome)
        assertTrue(PhysicalMatchReason.NAME_ONLY_MATCH in match.reasons)
    }

    @Test fun separateChromecastAttachedToDlnaTvNeverMerges() {
        val match = PhysicalTvMatcher.match(cast(host = "192.168.1.20", model = "Chromecast with Google TV"), dlna(host = "192.168.1.20", model = "Chromecast with Google TV"))
        assertEquals(PhysicalMatchOutcome.NO_MATCH, match.outcome)
        assertTrue(PhysicalMatchReason.SEPARATE_CAST_DONGLE_SIGNATURE in match.reasons)
    }

    @Test fun noSharedValidatedHostIsNoMatch() {
        val match = PhysicalTvMatcher.match(cast(host = "192.168.1.20", model = "Sony XR-55A80L"), dlna(host = "192.168.1.21", model = "Sony XR-55A80L"))
        assertEquals(PhysicalMatchOutcome.NO_MATCH, match.outcome)
        assertTrue(PhysicalMatchReason.NO_SHARED_VALIDATED_HOST in match.reasons)
    }

    @Test fun oneToManyConfidentCandidatesRemainSeparateAndAmbiguous() {
        val first = dlna(id = "dlna-a", host = "192.168.1.20", model = "Sony XR-55A80L")
        val second = dlna(id = "dlna-b", host = "192.168.1.20", model = "Sony XR-55A80L")
        val result = PhysicalTvAggregator.aggregate(listOf(cast(host = "192.168.1.20", model = "Sony XR-55A80L"), first, second))
        assertEquals(3, result.physicalTvs.size)
        assertTrue(result.matches.all { it.outcome == PhysicalMatchOutcome.AMBIGUOUS })
        assertTrue(result.matches.all { PhysicalMatchReason.ONE_TO_MANY_HIGH_CONFIDENCE_CANDIDATES in it.reasons })
    }

    @Test fun persistedLinkUnlinkAndOfflinePreferencePolicy() {
        val store = InMemoryPhysicalTvAssociationStore()
        val cast = cast(host = "192.168.1.20", model = "Sony XR-55A80L")
        val dlna = dlna(host = "192.168.1.20", model = "Sony XR-55A80L")
        val castKey = requireNotNull(PhysicalEndpointKey.from(cast))
        val dlnaKey = requireNotNull(PhysicalEndpointKey.from(dlna))
        store.link(castKey, dlnaKey)
        store.recordLastSuccessful(listOf(castKey, dlnaKey), Protocol.DLNA)
        store.setPreferredProtocol(listOf(castKey, dlnaKey), Protocol.CAST)
        assertEquals(PhysicalMatchOutcome.CONFIDENT, PhysicalTvMatcher.match(cast, dlna, store).outcome)
        val offline = PhysicalTvAggregator.aggregate(listOf(cast), store).physicalTvs.single()
        assertTrue(offline.id.contains(dlnaKey.storageToken))
        assertEquals(Protocol.DLNA, offline.selectionPreference.lastSuccessfulProtocol)
        assertEquals(Protocol.CAST, offline.selectionPreference.preferredProtocol)
        store.unlink(castKey, dlnaKey)
        val unlinked = PhysicalTvMatcher.match(cast, dlna, store)
        assertEquals(PhysicalMatchOutcome.NO_MATCH, unlinked.outcome)
        assertTrue(PhysicalMatchReason.USER_UNLINKED in unlinked.reasons)
        assertNull(store.selectionFor(listOf(castKey, dlnaKey)).lastSuccessfulProtocol)
        store.link(castKey, dlnaKey)
        assertFalse(store.isBlocked(castKey, dlnaKey))
    }

    @Test fun aggregationCarriesBothEndpointsAndEndpointSelectionHonorsPolicy() {
        val cast = cast(host = "192.168.1.20", model = "Sony XR-55A80L")
        val dlna = dlna(host = "192.168.1.20", model = "Sony XR-55A80L")
        val tv = PhysicalTvAggregator.aggregate(listOf(dlna, cast)).physicalTvs.single()
        assertEquals(cast, tv.castEndpoint)
        assertEquals(dlna, tv.dlnaEndpoint)
        assertEquals(cast, tv.selectEndpoint())
        assertEquals(dlna, tv.copy(selectionPreference = EndpointSelectionPreference(preferredProtocol = Protocol.DLNA)).selectEndpoint())
        assertEquals(cast, tv.copy(selectionPreference = EndpointSelectionPreference(Protocol.CAST, Protocol.DLNA)).selectEndpoint())
    }

    @Test fun resumeMatcherUsesOnlyExactStableIdentity() {
        val first = PhysicalTvAggregator.aggregate(listOf(cast(id = "cast-a", name = "Living Room"))).physicalTvs.single()
        val second = PhysicalTvAggregator.aggregate(listOf(cast(id = "cast-b", name = "Living Room"))).physicalTvs.single()
        val token = requireNotNull(PhysicalEndpointKey.from(second.castEndpoint!!)).storageToken

        assertEquals(second, PhysicalTvResumeMatcher.findConfident(listOf(first, second), null, token))
        assertEquals(first, PhysicalTvResumeMatcher.findConfident(listOf(first, second), first.id, null))
        assertNull(PhysicalTvResumeMatcher.findConfident(listOf(first, second), "Living Room", null))
    }

    @Test fun reconnectPolicyRebindsChangedRouteAndAddressByStableDeviceId() {
        val previousEndpoint = cast(
            id = "route-before-reboot",
            stableId = "living-room-device",
            host = "192.168.1.20",
        )
        val refreshedEndpoint = cast(
            id = "route-after-reboot",
            stableId = "living-room-device",
            host = "192.168.1.44",
        )
        val previousTv = PhysicalTvAggregator.aggregate(listOf(previousEndpoint)).physicalTvs.single()
        val refreshedTv = PhysicalTvAggregator.aggregate(listOf(refreshedEndpoint)).physicalTvs.single()

        val match = PhysicalTvReconnectPolicy.find(listOf(refreshedTv), previousTv, previousEndpoint)

        assertEquals(refreshedTv, match?.physicalTv)
        assertEquals(refreshedEndpoint, match?.sameProtocolEndpoint)
    }

    @Test fun reconnectPolicyNeverUsesDisplayNameWithoutStableIdentity() {
        val previousEndpoint = cast(id = "route-old", name = "Living Room", stableId = null)
        val unrelatedEndpoint = cast(id = "route-new", name = "Living Room", stableId = null)
        val previousTv = PhysicalTvAggregator.aggregate(listOf(previousEndpoint)).physicalTvs.single()
        val unrelatedTv = PhysicalTvAggregator.aggregate(listOf(unrelatedEndpoint)).physicalTvs.single()

        assertFalse(PhysicalTvReconnectPolicy.hasStableIdentity(previousTv, previousEndpoint))
        assertNull(PhysicalTvReconnectPolicy.find(listOf(unrelatedTv), previousTv, previousEndpoint))
    }

    @Test fun reconnectPolicyCanReturnOnlyARefreshedAlternateProtocol() {
        val store = InMemoryPhysicalTvAssociationStore()
        val cast = cast(id = "cast-route", stableId = "cast-device")
        val dlna = dlna(id = "uuid:renderer")
        store.link(requireNotNull(PhysicalEndpointKey.from(cast)), requireNotNull(PhysicalEndpointKey.from(dlna)))
        val previousTv = PhysicalTvAggregator.aggregate(listOf(cast, dlna), store).physicalTvs.single()
        val refreshedTv = PhysicalTvAggregator.aggregate(listOf(dlna), store).physicalTvs.single()

        val match = PhysicalTvReconnectPolicy.find(listOf(refreshedTv), previousTv, cast)

        assertEquals(refreshedTv, match?.physicalTv)
        assertNull(match?.sameProtocolEndpoint)
        assertEquals(dlna, match?.physicalTv?.dlnaEndpoint)
    }

    @Test fun reconnectScanBackoffIsBounded() {
        assertEquals(1_500L, PhysicalTvReconnectPolicy.delayAfterScanMillis(1))
        assertEquals(10_000L, PhysicalTvReconnectPolicy.delayAfterScanMillis(100))
    }

    private fun cast(
        id: String = "cast-id",
        name: String = "TV",
        host: String? = null,
        model: String? = null,
        stableId: String? = id,
    ) = target(
        id, name, Protocol.CAST,
        TargetDiscoveryMetadata(
            castDeviceId = stableId,
            validatedSourceHost = host,
            modelName = model,
            volumeControlAvailable = true,
        ),
    )
    private fun dlna(id: String = "uuid:dlna-id", name: String = "TV", host: String? = null, model: String? = null) = target(
        id, name, Protocol.DLNA,
        TargetDiscoveryMetadata(dlnaUdn = id, dlnaUsn = "$id::urn:schemas-upnp-org:device:MediaRenderer:1", validatedSourceHost = host, modelName = model),
    )
    private fun target(id: String, name: String, protocol: Protocol, metadata: TargetDiscoveryMetadata) = DiscoveredTarget(
        id = id,
        displayName = name,
        protocol = protocol,
        capabilities = TargetCapabilities(protocol),
        lastTestedStatus = null,
        discoveryMetadata = metadata,
    )
}
