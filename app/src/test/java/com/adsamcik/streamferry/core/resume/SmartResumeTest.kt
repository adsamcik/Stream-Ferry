package com.adsamcik.streamferry.core.resume

import com.adsamcik.streamferry.core.stream.Protocol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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

    @Test fun lateTeardownCannotResurrectCompletion() {
        val current = SmartResumeReducer.reduce(null, checkpoint(position = 900))!!
        val finished = SmartResumeReducer.reduce(current, checkpoint(sequence = 2, position = 995, kind = SmartResumeCheckpointKind.COMPLETED))!!
        assertEquals(finished, SmartResumeReducer.reduce(finished, checkpoint(sequence = 3, position = 900, kind = SmartResumeCheckpointKind.DISCONNECTED)))
    }
}
