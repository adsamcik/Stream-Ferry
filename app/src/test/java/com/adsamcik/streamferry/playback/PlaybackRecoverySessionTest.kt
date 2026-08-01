package com.adsamcik.streamferry.playback

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
    @Test fun budgetAllowsOneNetworkRetryAndAtMostThreeCompatibilityVariants() {
        var state = session()
        state = assertNotNull(state.reserveRecovery(RecoveryAttemptKind.SAME_STREAM_NETWORK, PlaybackPhase.RECONNECTING))
        assertNull(state.reserveRecovery(RecoveryAttemptKind.SAME_STREAM_NETWORK, PlaybackPhase.RECONNECTING))
        repeat(3) {
            state = assertNotNull(state.reserveRecovery(RecoveryAttemptKind.FORMAT_COMPATIBILITY, PlaybackPhase.CHANGING_STREAM))
        }
        assertNull(state.reserveRecovery(RecoveryAttemptKind.LOWER_RESOLUTION, PlaybackPhase.CHANGING_STREAM))
    }

    @Test fun totalAutomaticWorkIsBoundedAtSix() {
        var state = session().copy(budget = RecoveryBudget(maxCompatibilityOrQualityVariants = 5))
        repeat(6) { index ->
            val kind = if (index == 0) RecoveryAttemptKind.SAME_STREAM_NETWORK else RecoveryAttemptKind.FORMAT_COMPATIBILITY
            state = assertNotNull(state.reserveRecovery(kind, PlaybackPhase.CHANGING_STREAM))
        }
        assertEquals(0, state.budgetStatus.automaticAttemptsRemaining)
        assertFalse(state.budget.canSchedule(state.usage, RecoveryAttemptKind.ALTERNATE_PROTOCOL))
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
        val original = assertNotNull(session().reserveRecovery(RecoveryAttemptKind.SAME_STREAM_NETWORK, PlaybackPhase.RECONNECTING))
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
}
