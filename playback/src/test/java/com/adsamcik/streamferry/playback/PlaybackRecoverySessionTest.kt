package com.adsamcik.streamferry.playback

import com.adsamcik.streamferry.core.stream.Protocol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaybackRecoverySessionTest {
    private fun session() = PlaybackRecoverySession()
        .startSession()
        .beginAttempt(PlaybackAttemptDescriptor(generation = 0, endpoint = redactPlaybackEndpoint("10.0.0.5")))

    @Test fun generationsAreMonotonicAndHistoryIsDeduplicatedToSix() {
        var state = session()
        val first = state.generation
        repeat(8) { index -> state = state.beginAttempt(PlaybackAttemptDescriptor(generation = 0, reason = "load-$index")) }

        assertTrue(state.generation > first)
        assertEquals(PlaybackRecoverySession.MAX_ATTEMPT_HISTORY, state.attempts.size)
        assertEquals(state.generation, state.attempts.last().generation)
        assertTrue(state.attempts.zipWithNext().all { (a, b) -> a.generation < b.generation })
        assertEquals("lan://…", session().attempts.single().endpoint)
    }

    @Test fun equivalentLoadsAreRecordedOnceButStillAdvanceGeneration() {
        var state = session()
        val first = state.beginAttempt(PlaybackAttemptDescriptor(generation = 0, reason = "same"))
        state = first.beginAttempt(PlaybackAttemptDescriptor(generation = 0, reason = "same"))

        assertEquals(first.attempts.size, state.attempts.size)
        assertTrue(state.generation > first.generation)
    }
    @Test fun budgetAllowsOneEstablishedNetworkRetryAndAtMostThreeCompatibilityVariants() {
        var state = session()
        state = assertNotNull(state.reserveRecovery(RecoveryAttemptKind.SAME_STREAM_NETWORK, PlaybackPhase.RECONNECTING))
        assertNull(state.reserveRecovery(RecoveryAttemptKind.SAME_STREAM_NETWORK, PlaybackPhase.RECONNECTING))
        repeat(3) {
            state = assertNotNull(state.reserveRecovery(RecoveryAttemptKind.FORMAT_COMPATIBILITY, PlaybackPhase.CHANGING_STREAM))
        }
        assertNull(state.reserveRecovery(RecoveryAttemptKind.LOWER_RESOLUTION, PlaybackPhase.CHANGING_STREAM))
    }

    @Test fun exhaustedFormatVariantsStillAllowOneProtocolFallbackWithinTheTotalBudget() {
        var state = session()
        repeat(3) {
            state = assertNotNull(state.reserveRecovery(RecoveryAttemptKind.FORMAT_COMPATIBILITY, PlaybackPhase.CHANGING_STREAM))
        }
        assertNull(state.reserveRecovery(RecoveryAttemptKind.LOWER_RESOLUTION, PlaybackPhase.CHANGING_STREAM))
        val alternate = assertNotNull(state.fail().reserveAlternateProtocol(protocolSwitchInput(state)))
        state = state.stop().continueFrom(alternate)
        assertEquals(3, state.usage.compatibilityOrQualityVariants)
        assertEquals(4, state.usage.automaticAttempts)
        assertNull(state.reserveAlternateProtocol(protocolSwitchInput(state)))
    }

    @Test fun connectivityRecoveryCannotStarveTheFormatAllowanceOnEitherProtocol() {
        for (first in Protocol.entries) {
            val second = Protocol.entries.single { it != first }
            var state = session()
            fun connect(protocol: Protocol) {
                repeat(2) {
                    val retry = assertNotNull(state.reserveEndpointConnectionRetry(protocol))
                    state = state.stop().continueFrom(retry)
                }
            }
            connect(first)
            state = assertNotNull(state.reserveRecovery(RecoveryAttemptKind.SAME_STREAM_NETWORK, PlaybackPhase.RECONNECTING))
            val handoff = assertNotNull(state.fail().reserveAlternateProtocol(protocolSwitchInput(state)))
            state = state.stop().continueFrom(handoff)
            connect(second)
            assertEquals(6, state.usage.automaticAttempts)
            assertEquals(3, state.budgetStatus.compatibilityOrQualityVariantsRemaining)
            repeat(3) { index ->
                val kind = if (index == 0) RecoveryAttemptKind.FORMAT_COMPATIBILITY else RecoveryAttemptKind.LOWER_RESOLUTION
                state = assertNotNull(state.reserveRecovery(kind, PlaybackPhase.CHANGING_STREAM))
            }
            assertEquals(9, state.usage.automaticAttempts)
            assertEquals(0, state.budgetStatus.automaticAttemptsRemaining)
            assertNull(state.reserveRecovery(RecoveryAttemptKind.FORMAT_COMPATIBILITY, PlaybackPhase.CHANGING_STREAM))
            assertNull(state.reserveRecovery(RecoveryAttemptKind.SAME_STREAM_NETWORK, PlaybackPhase.RECONNECTING))
            assertNull(state.reserveEndpointConnectionRetry(first))
            assertNull(state.reserveEndpointConnectionRetry(second))
            assertNull(state.reserveAlternateProtocol(protocolSwitchInput(state)))
        }
    }

    @Test fun customTotalAutomaticWorkIsBoundedAtSix() {
        var state = session().copy(budget = RecoveryBudget(maxAutomaticAttempts = 6, maxCompatibilityOrQualityVariants = 5))
        repeat(6) { index ->
            val kind = if (index == 0) RecoveryAttemptKind.SAME_STREAM_NETWORK else RecoveryAttemptKind.FORMAT_COMPATIBILITY
            state = assertNotNull(state.reserveRecovery(kind, PlaybackPhase.CHANGING_STREAM))
        }
        assertEquals(0, state.budgetStatus.automaticAttemptsRemaining)
        assertFalse(state.budget.canSchedule(state.usage, RecoveryAttemptKind.ALTERNATE_PROTOCOL))
    }

    @Test fun castAndDlnaConnectionRetriesAreIndependentAndCannotCycle() {
        var state = session()
        fun decision(protocol: Protocol, hasAlternate: Boolean = true) = decideInitialConnectionRecovery(
            InitialConnectionRecoveryInput(
                protocol = protocol,
                hasAlternateProtocol = hasAlternate,
                failureStage = PlaybackFailureStage.ENDPOINT_CONNECTION,
                failureCause = PlaybackFailureCause.ENDPOINT_UNAVAILABLE,
                budget = state.budget,
                usage = state.usage,
            ),
        )
        fun reserveConnectionRetry(protocol: Protocol) {
            val continuation = assertNotNull(state.reserveEndpointConnectionRetry(protocol))
            state = state.stop().continueFrom(continuation)
        }

        assertEquals(InitialConnectionRecoveryAction.RETRY_SAME_ENDPOINT, decision(Protocol.CAST))
        repeat(2) { reserveConnectionRetry(Protocol.CAST) }
        assertEquals(InitialConnectionRecoveryAction.TRY_ALTERNATE_PROTOCOL, decision(Protocol.CAST))
        assertEquals(InitialConnectionRecoveryAction.SURFACE, decision(Protocol.CAST, hasAlternate = false))

        // Exhausting Cast does not spend DLNA's allowance.
        assertEquals(InitialConnectionRecoveryAction.RETRY_SAME_ENDPOINT, decision(Protocol.DLNA))
        val switch = assertNotNull(state.reserveAlternateProtocol(protocolSwitchInput(state)))
        state = state.stop().continueFrom(switch)
        repeat(2) { reserveConnectionRetry(Protocol.DLNA) }

        assertEquals(2, state.usage.endpointConnectionRetries(Protocol.CAST))
        assertEquals(2, state.usage.endpointConnectionRetries(Protocol.DLNA))
        assertEquals(5, state.usage.automaticAttempts)
        assertEquals(InitialConnectionRecoveryAction.TRY_ALTERNATE_PROTOCOL, decision(Protocol.DLNA))
        assertNull(state.reserveEndpointConnectionRetry(Protocol.CAST))
        assertNull(state.reserveEndpointConnectionRetry(Protocol.DLNA))
        assertNull(state.reserveAlternateProtocol(protocolSwitchInput(state)))
    }

    @Test fun connectionRetryIsSymmetricButDoesNotMaskLoadOrSourceFailures() {
        val base = InitialConnectionRecoveryInput(
            protocol = Protocol.DLNA,
            hasAlternateProtocol = true,
            failureStage = PlaybackFailureStage.ENDPOINT_CONNECTION,
            failureCause = PlaybackFailureCause.ENDPOINT_UNAVAILABLE,
            budget = RecoveryBudget(),
            usage = RecoveryBudgetUsage(),
        )

        assertEquals(InitialConnectionRecoveryAction.RETRY_SAME_ENDPOINT, decideInitialConnectionRecovery(base))
        assertEquals(
            InitialConnectionRecoveryAction.SURFACE,
            decideInitialConnectionRecovery(base.copy(
                protocol = Protocol.CAST,
                failureStage = PlaybackFailureStage.RENDERER_LOAD,
            )),
        )
        assertEquals(
            InitialConnectionRecoveryAction.SURFACE,
            decideInitialConnectionRecovery(base.copy(
                protocol = Protocol.CAST,
                failureStage = PlaybackFailureStage.STREAM_RESOLUTION,
                failureCause = PlaybackFailureCause.UPSTREAM_OR_SERVER_UNAVAILABLE,
            )),
        )
    }

    @Test fun protocolFallbackAllowsEndpointDisconnectAfterSameEndpointRecoveryForLocalOrOnline() {
        val budget = RecoveryBudget()
        val usedNetwork = RecoveryBudgetUsage(automaticAttempts = 1, sameStreamNetworkRetries = 1)
        val input = ProtocolSwitchInput(
            isLocalSession = true,
            isOnlineSession = false,
            hasAlternateProtocol = true,
            hasAlreadySwitchedProtocol = false,
            sameEndpointRecoveryExhausted = true,
            failureStage = PlaybackFailureStage.ENDPOINT_DISCONNECT,
            failureCause = PlaybackFailureCause.TRANSIENT_NETWORK,
            budget = budget,
            usage = usedNetwork,
        )
        assertTrue(isAlternateProtocolEligible(input))
        assertTrue(isAlternateProtocolEligible(input.copy(isLocalSession = false, isOnlineSession = true)))
    }

    @Test fun protocolFallbackRejectsUpstreamServerFailureAndPrematureSwitch() {
        val input = ProtocolSwitchInput(
            isLocalSession = false,
            isOnlineSession = true,
            hasAlternateProtocol = true,
            hasAlreadySwitchedProtocol = false,
            sameEndpointRecoveryExhausted = true,
            failureStage = PlaybackFailureStage.STREAM_RESOLUTION,
            failureCause = PlaybackFailureCause.UPSTREAM_OR_SERVER_UNAVAILABLE,
            budget = RecoveryBudget(),
            usage = RecoveryBudgetUsage(),
        )
        assertFalse(isAlternateProtocolEligible(input))
        assertFalse(isAlternateProtocolEligible(input.copy(
            failureCause = PlaybackFailureCause.ENDPOINT_UNAVAILABLE,
            sameEndpointRecoveryExhausted = false,
        )))
    }

    @Test fun staleGenerationIsRejectedAndStopInvalidatesQueuedRecovery() {
        var state = session()
        val current = state.generation
        assertTrue(state.acceptsEvent(current))
        state = assertNotNull(state.reserveRecovery(RecoveryAttemptKind.SAME_STREAM_NETWORK, PlaybackPhase.RECONNECTING))
        assertTrue(state.acceptsEvent(current))
        state = state.stop()
        assertFalse(state.acceptsEvent(current))
        assertNull(state.reserveRecovery(RecoveryAttemptKind.FORMAT_COMPATIBILITY, PlaybackPhase.CHANGING_STREAM))
    }

    @Test fun continuationPreservesUsageHistoryAndMonotonicGeneration() {
        val base = session()
        val connectionContinuation = assertNotNull(base.reserveEndpointConnectionRetry(Protocol.CAST))
        val original = base.stop().continueFrom(connectionContinuation)
        val continuation = assertNotNull(original.reserveAlternateProtocol(
            ProtocolSwitchInput(
                isLocalSession = false,
                isOnlineSession = true,
                hasAlternateProtocol = true,
                hasAlreadySwitchedProtocol = false,
                sameEndpointRecoveryExhausted = true,
                failureStage = PlaybackFailureStage.ESTABLISHED_PLAYBACK,
                failureCause = PlaybackFailureCause.ENDPOINT_UNAVAILABLE,
                budget = original.budget,
                usage = original.usage,
            ),
        ))
        val continued = original.stop().continueFrom(continuation)

        assertEquals(2, continued.usage.automaticAttempts)
        assertEquals(1, continued.usage.endpointConnectionRetries(Protocol.CAST))
        assertEquals(0, continued.usage.endpointConnectionRetries(Protocol.DLNA))
        assertEquals(original.attempts, continued.attempts)
        assertTrue(continued.generation > original.generation)
        assertTrue(continued.alternateProtocolReserved)
        assertNull(continued.reserveAlternateProtocol(
            ProtocolSwitchInput(
                isLocalSession = false,
                isOnlineSession = true,
                hasAlternateProtocol = true,
                hasAlreadySwitchedProtocol = false,
                sameEndpointRecoveryExhausted = true,
                failureStage = PlaybackFailureStage.ENDPOINT_DISCONNECT,
                failureCause = PlaybackFailureCause.ENDPOINT_UNAVAILABLE,
                budget = continued.budget,
                usage = continued.usage,
            ),
        ))
    }

    @Test fun manualProtocolSwitchPreservesSessionWithoutConsumingAutomaticRecovery() {
        var original = session().transition(PlaybackPhase.PLAYING)
        original = assertNotNull(original.reserveRecovery(
            RecoveryAttemptKind.SAME_STREAM_NETWORK,
            PlaybackPhase.RECONNECTING,
        )).transition(PlaybackPhase.PLAYING)

        val firstSwitch = assertNotNull(original.manualProtocolSwitchContinuation())
        assertNull(firstSwitch.reservedKind)
        val continued = original.stop().continueFrom(firstSwitch)

        assertEquals(original.usage, continued.usage)
        assertEquals(original.attempts, continued.attempts)
        assertEquals(original.alternateProtocolReserved, continued.alternateProtocolReserved)
        assertTrue(continued.generation > original.generation)
        assertNotNull(continued.transition(PlaybackPhase.PLAYING).manualProtocolSwitchContinuation())
        assertNull(continued.stop().manualProtocolSwitchContinuation())
        assertNull(continued.fail().manualProtocolSwitchContinuation())
    }

    @Test fun healthyPlaybackRearmsOnlyNetworkCapacityAndPreservesOtherSessionState() {
        var state = session()
        for (protocol in Protocol.entries) repeat(2) {
            val retry = assertNotNull(state.reserveEndpointConnectionRetry(protocol))
            state = state.stop().continueFrom(retry)
        }
        repeat(3) {
            state = assertNotNull(state.reserveRecovery(RecoveryAttemptKind.FORMAT_COMPATIBILITY, PlaybackPhase.CHANGING_STREAM))
        }
        val alternate = assertNotNull(state.reserveAlternateProtocol(protocolSwitchInput(state)))
        state = state.stop().continueFrom(alternate)
        state = assertNotNull(state.reserveRecovery(RecoveryAttemptKind.SAME_STREAM_NETWORK, PlaybackPhase.RECONNECTING))
            .transition(PlaybackPhase.PLAYING)
        val before = state
        assertEquals(0, before.budgetStatus.automaticAttemptsRemaining)

        val rearmed = before.rearmNetworkRetry()

        assertEquals(before.copy(usage = before.usage.copy(
            automaticAttempts = before.usage.automaticAttempts - 1,
            sameStreamNetworkRetries = 0,
        )), rearmed)
        assertEquals(before.attempts, rearmed.attempts)
        assertEquals(before.generation, rearmed.generation)
        assertEquals(before.usage.endpointConnectionRetries, rearmed.usage.endpointConnectionRetries)
        assertEquals(3, rearmed.usage.compatibilityOrQualityVariants)
        assertTrue(rearmed.alternateProtocolReserved)
        assertEquals(1, rearmed.budgetStatus.sameStreamNetworkRetriesRemaining)
        assertNotNull(rearmed.reserveRecovery(RecoveryAttemptKind.SAME_STREAM_NETWORK, PlaybackPhase.RECONNECTING))
        assertNull(rearmed.reserveRecovery(RecoveryAttemptKind.FORMAT_COMPATIBILITY, PlaybackPhase.CHANGING_STREAM))
        assertNull(rearmed.reserveRecovery(RecoveryAttemptKind.LOWER_RESOLUTION, PlaybackPhase.CHANGING_STREAM))
        Protocol.entries.forEach { assertNull(rearmed.reserveEndpointConnectionRetry(it)) }
        assertNull(rearmed.reserveAlternateProtocol(protocolSwitchInput(rearmed)))
        assertEquals(rearmed, rearmed.rearmNetworkRetry())
    }

    @Test fun networkRetryCannotRearmOutsideConfirmedPlayingState() {
        val used = assertNotNull(session().reserveRecovery(
            RecoveryAttemptKind.SAME_STREAM_NETWORK, PlaybackPhase.RECONNECTING,
        ))
        for (phase in PlaybackPhase.entries.filter { it != PlaybackPhase.PLAYING }) {
            val state = used.transition(phase)
            assertEquals(state, state.rearmNetworkRetry(), "phase=$phase")
        }
    }

    private fun protocolSwitchInput(state: PlaybackRecoverySession) = ProtocolSwitchInput(
        isLocalSession = false,
        isOnlineSession = true,
        hasAlternateProtocol = true,
        hasAlreadySwitchedProtocol = state.alternateProtocolReserved,
        sameEndpointRecoveryExhausted = true,
        failureStage = PlaybackFailureStage.ENDPOINT_CONNECTION,
        failureCause = PlaybackFailureCause.ENDPOINT_UNAVAILABLE,
        budget = state.budget,
        usage = state.usage,
    )
}
