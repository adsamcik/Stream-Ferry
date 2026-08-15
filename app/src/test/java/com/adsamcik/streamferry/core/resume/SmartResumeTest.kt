package com.adsamcik.streamferry.core.resume

import com.adsamcik.streamferry.core.stream.Protocol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SmartResumeTest {
    private class MemoryStore : SmartResumeRecordStore {
        override var current: SmartResumeRecord? = null
        override fun apply(update: SmartResumeCheckpoint): SmartResumeRecord? =
            SmartResumeReducer.reduce(current, update).also { current = it }
        override fun clear() { current = null }
    }
    private val seed = SmartResumeSeed(SmartResumeSourceType.JELLYFIN, "movie-1", "Movie", durationSeconds = 1_000, serverId = "server", userId = "user")

    private fun checkpoint(
        sequence: Long = 1,
        position: Long = 100,
        kind: SmartResumeCheckpointKind = SmartResumeCheckpointKind.STARTED,
        generation: Long = 1,
        device: SmartResumeDeviceContext? = null,
        sessionId: String = "session",
    ) = SmartResumeCheckpoint(seed, sessionId, generation, sequence, position, 1_000, 1000, kind, device)

    @Test fun deviceAwareCheckpointPersistsOnlySafeReferenceFields() {
        val device = SmartResumeDeviceContext("living-room-tv", "cast:living-room", Protocol.CAST, "cast-device-42")
        val record = SmartResumeReducer.reduce(null, checkpoint(device = device))!!

        assertEquals("living-room-tv", record.physicalDeviceStableId)
        assertEquals("cast:living-room", record.physicalDeviceReference)
        assertEquals(Protocol.CAST, record.lastSuccessfulProtocol)
        assertEquals("cast-device-42", record.stableEndpointIdentity)
    }

    @Test fun rendererConfirmedEndpointIsPersistedWithoutBypassingCompletionGuard() {
        val store = MemoryStore()
        val tracker = SmartResumeSessionTracker(store, clock = { 1000 }, newSessionId = { "session" })
        val connected = SmartResumeDeviceContext("living-room-tv", lastSuccessfulProtocol = Protocol.DLNA, stableEndpointIdentity = "udn:tv")
        tracker.prepare(seed)
        tracker.onRendererStatus(100, 1_000, isPlaying = true, deviceContext = connected)
        assertEquals("udn:tv", store.current!!.stableEndpointIdentity)

        tracker.complete()
        tracker.checkpoint(SmartResumeCheckpointKind.DISCONNECTED, connected.copy(stableEndpointIdentity = "late-endpoint"))
        assertEquals("udn:tv", store.current!!.stableEndpointIdentity)
    }
    @Test fun reconciliationUsesNewestConfirmedPositionAndNeverRegressesToServer() {
        val record = SmartResumeReducer.reduce(null, checkpoint(position = 120))!!
        assertEquals(135, SmartResumePositionReconciler.reconcile(record, rendererConfirmedSeconds = 135, jellyfinResumeSeconds = 90))
    }

    @Test fun matchingJellyfinCheckpointWinsOverStaleServerResume() {
        val record = SmartResumeReducer.reduce(null, checkpoint(position = 135))!!

        assertEquals(135, SmartResumePositionReconciler.reconcileJellyfinItem(
            record, itemId = "movie-1", serverId = "server", userId = "user", jellyfinResumeSeconds = 90,
        ))
    }

    @Test fun unrelatedJellyfinCheckpointCannotOverrideServerResume() {
        val record = SmartResumeReducer.reduce(null, checkpoint(position = 135))!!

        assertEquals(90, SmartResumePositionReconciler.reconcileJellyfinItem(
            record, itemId = "another-movie", serverId = "server", userId = "user", jellyfinResumeSeconds = 90,
        ))
    }

    @Test fun finishedMatchingCheckpointPreventsResumingFromStaleServerProgress() {
        val started = SmartResumeReducer.reduce(null, checkpoint(position = 900))!!
        val finished = SmartResumeReducer.reduce(
            started, checkpoint(sequence = 2, position = 995, kind = SmartResumeCheckpointKind.COMPLETED),
        )!!

        assertNull(SmartResumePositionReconciler.reconcileJellyfinItem(
            finished, itemId = "movie-1", serverId = "server", userId = "user", jellyfinResumeSeconds = 900,
        ))
    }

    @Test fun reconciliationKeepsCompletionProtection() {
        val finished = SmartResumeReducer.reduce(
            SmartResumeReducer.reduce(null, checkpoint(position = 900))!!,
            checkpoint(sequence = 2, position = 995, kind = SmartResumeCheckpointKind.COMPLETED),
        )!!
        assertNull(SmartResumePositionReconciler.reconcile(finished, rendererConfirmedSeconds = 800, jellyfinResumeSeconds = 800))
    }

    @Test fun staleGenerationAndSequenceCannotOverwriteRecord() {
        val current = SmartResumeReducer.reduce(null, checkpoint(position = 100))!!
        assertEquals(current, SmartResumeReducer.reduce(current, checkpoint(sequence = 1, position = 500)))
        assertEquals(current, SmartResumeReducer.reduce(current, checkpoint(sequence = 2, position = 500, generation = 0)))
        assertEquals(
            current,
            SmartResumeReducer.reduce(
                current,
                checkpoint(position = 500, generation = 1, sessionId = "older-session"),
            ),
        )
    }

    @Test fun confirmedSeekMayMovePositionBackwards() {
        val current = SmartResumeReducer.reduce(null, checkpoint(position = 400))!!
        val sought = SmartResumeReducer.reduce(current, checkpoint(sequence = 2, position = 120, kind = SmartResumeCheckpointKind.SEEK_CONFIRMED))!!
        assertEquals(120, sought.confirmedPositionSeconds)
    }

    @Test fun lowerNewSessionStartCannotOverwriteSameInProgressCheckpoint() {
        val current = SmartResumeReducer.reduce(null, checkpoint(position = 900))!!

        val restarted = SmartResumeReducer.reduce(
            current, checkpoint(sequence = 1, position = 300, generation = 2, sessionId = "new-session"),
        )!!

        assertEquals(900, restarted.confirmedPositionSeconds)
        assertEquals("new-session", restarted.sessionId)
    }

    @Test fun steadyRendererSamplesAdvanceCrashCheckpointWithinFiveSeconds() {
        var now = 0L
        val store = MemoryStore()
        val tracker = SmartResumeSessionTracker(store, clock = { now }, newSessionId = { "session" })
        tracker.prepare(seed)
        tracker.onRendererStatus(100, 1_000, isPlaying = true)

        now = 4_999L
        tracker.onRendererStatus(104, 1_000, isPlaying = true)
        assertEquals(100, store.current!!.confirmedPositionSeconds)

        now = 5_000L
        tracker.onRendererStatus(105, 1_000, isPlaying = true)
        assertEquals(105, store.current!!.confirmedPositionSeconds)
    }

    @Test fun lateTeardownCannotResurrectCompletion() {
        val current = SmartResumeReducer.reduce(null, checkpoint(position = 900))!!
        val finished = SmartResumeReducer.reduce(current, checkpoint(sequence = 2, position = 995, kind = SmartResumeCheckpointKind.COMPLETED))!!
        assertEquals(finished, SmartResumeReducer.reduce(finished, checkpoint(sequence = 3, position = 900, kind = SmartResumeCheckpointKind.DISCONNECTED)))
    }

    @Test fun historyKeepsPreviousItemsAndUpdatesAnExistingIdentityInPlace() {
        val first = SmartResumeHistoryReducer.reduce(emptyList(), checkpoint(position = 120))
        val secondSeed = seed.copy(mediaId = "movie-2", displayTitle = "Second movie")
        val second = SmartResumeHistoryReducer.reduce(
            first,
            checkpoint(position = 40, generation = 2, sessionId = "second").copy(seed = secondSeed),
        )

        assertEquals(listOf("movie-2", "movie-1"), second.map { it.mediaId })

        val advanced = SmartResumeHistoryReducer.reduce(
            second,
            checkpoint(sequence = 2, position = 65, generation = 2, sessionId = "second", kind = SmartResumeCheckpointKind.PROGRESS)
                .copy(seed = secondSeed),
        )
        assertEquals(65, advanced.first().confirmedPositionSeconds)
        assertEquals(2, advanced.size)
    }

    @Test fun historyRejectsLateOlderSessionUpdatesAfterAnewItemStarts() {
        val first = SmartResumeHistoryReducer.reduce(emptyList(), checkpoint(position = 120))
        val secondSeed = seed.copy(mediaId = "movie-2", displayTitle = "Second movie")
        val second = SmartResumeHistoryReducer.reduce(
            first,
            checkpoint(position = 40, generation = 2, sessionId = "second").copy(seed = secondSeed),
        )

        val afterLateUpdate = SmartResumeHistoryReducer.reduce(
            second,
            checkpoint(sequence = 2, position = 500, kind = SmartResumeCheckpointKind.STOPPED),
        )

        assertEquals(second, afterLateUpdate)
    }

    @Test fun historyIsDeduplicatedAndBounded() {
        var history = emptyList<SmartResumeRecord>()
        repeat(SmartResumeHistoryReducer.MAX_ENTRIES + 5) { index ->
            history = SmartResumeHistoryReducer.reduce(
                history,
                checkpoint(position = 30, generation = index + 1L, sessionId = "session-$index")
                    .copy(seed = seed.copy(mediaId = "movie-$index", displayTitle = "Movie $index")),
            )
        }

        assertEquals(SmartResumeHistoryReducer.MAX_ENTRIES, history.size)
        assertEquals("movie-${SmartResumeHistoryReducer.MAX_ENTRIES + 4}", history.first().mediaId)
        assertTrue(history.none { it.mediaId == "movie-0" })
    }

    @Test fun historyKeepsOnlyTheRollingNinetyDayWindow() {
        val now = SmartResumeHistoryReducer.RETENTION_MILLIS + 50_000L
        fun record(id: String, updatedAt: Long, generation: Long): SmartResumeRecord =
            SmartResumeReducer.reduce(
                null,
                checkpoint(generation = generation, sessionId = "session-$id")
                    .copy(
                        seed = seed.copy(mediaId = id, displayTitle = id),
                        updatedAtMillis = updatedAt,
                    ),
            )!!
        val expired = record("expired", now - SmartResumeHistoryReducer.RETENTION_MILLIS - 1, 1)
        val boundary = record("boundary", now - SmartResumeHistoryReducer.RETENTION_MILLIS, 2)
        val recent = record("recent", now - 1, 3)

        val retained = SmartResumeHistoryReducer.normalize(listOf(expired, boundary, recent), now)

        assertEquals(listOf("recent", "boundary"), retained.map { it.mediaId })
    }

    @Test fun delayedExpiredStartCannotReenterHistory() {
        val now = SmartResumeHistoryReducer.RETENTION_MILLIS + 10_000L
        val expiredStart = checkpoint().copy(updatedAtMillis = now - SmartResumeHistoryReducer.RETENTION_MILLIS - 1)

        assertEquals(emptyList(), SmartResumeHistoryReducer.reduce(emptyList(), expiredStart, now))
    }
}
