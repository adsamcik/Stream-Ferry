package com.adsamcik.streamferry.core.resume

import kotlin.test.*

class EstimatedResumeTest {
    private class Store : SmartResumeRecordStore {
        override var current: SmartResumeRecord? = null
        var writes = 0
        override fun apply(update: SmartResumeCheckpoint) = SmartResumeReducer.reduce(current, update).also { current = it; writes++ }
        override fun clear() { current = null }
    }
    private val seed = SmartResumeSeed(SmartResumeSourceType.LOCAL, "movie", "Movie", durationSeconds = 1000, localContentUri = "content://movie")
    private fun estimate(position: Long = 160, anchor: Long = 100) = ProgressEstimate(position, anchor, 60_000, 6, ProgressConfidence.DELIVERY)

    @Test fun `frequent estimates between real statuses do not bypass the disk write interval`() {
        val store = Store()
        var now = 0L
        val tracker = SmartResumeSessionTracker(store, { now })
        tracker.prepare(seed)
        tracker.onRendererStatus(100, 1000, true)
        for (i in 1..4) {
            now = i * 1000L
            tracker.noteEstimate(ProgressEstimate(100L + i, 99L + i, 1000, 5, ProgressConfidence.CLOCK))
            tracker.onRendererStatus(100L + i, 1000, true)
        }
        assertEquals(1, store.writes)
        now = 5000
        tracker.onRendererStatus(105, 1000, true)
        assertEquals(2, store.writes)
    }

    @Test fun `growing uncertainty freezes a saved checkpoint until better evidence or confirmation`() {
        val store = Store()
        var now = 0L
        val tracker = SmartResumeSessionTracker(store, { now })
        tracker.prepare(seed)
        tracker.onRendererStatus(100, 1000, true)
        now = 60_000
        tracker.noteEstimate(estimate())
        now = 120_000
        tracker.noteEstimate(estimate().copy(anchorAgeMillis = 120_000, uncertaintySeconds = 12))
        tracker.checkpoint(SmartResumeCheckpointKind.LIFECYCLE)
        assertEquals(154, store.current!!.resumePositionSeconds())
        tracker.onRendererStatus(120, 1000, true, allowRegression = true)
        assertEquals(120, store.current!!.resumePositionSeconds())
    }

    @Test fun `crash recovery retains separate anchor estimate and rewind without extrapolating after restart`() {
        val store = Store()
        var now = 1000L
        val tracker = SmartResumeSessionTracker(store, { now })
        tracker.prepare(seed)
        tracker.onRendererStatus(100, 1000, true)
        now += 60_000
        tracker.noteEstimate(estimate())
        val record = store.current!!
        assertEquals(100, record.confirmedPositionSeconds)
        assertEquals(154, record.resumePositionSeconds())
        assertEquals(ProgressConfidence.DELIVERY, record.estimatedProgress?.progress?.confidence)
        now += 24 * 3600_000
        assertEquals(154, record.resumePositionSeconds())
        assertEquals(SmartResumeRecordState.IN_PROGRESS, record.state)
    }

    @Test fun `fresh lower confirmation replaces estimate immediately and remote backward seek is accepted`() {
        val store = Store()
        var now = 0L
        val tracker = SmartResumeSessionTracker(store, { now })
        tracker.prepare(seed)
        tracker.onRendererStatus(100, 1000, true)
        now = 60_000
        tracker.noteEstimate(estimate())
        tracker.onRendererStatus(120, 1000, true, allowRegression = true)
        assertNull(store.current!!.estimatedProgress)
        assertEquals(120, store.current!!.resumePositionSeconds())
        tracker.onRendererStatus(60, 1000, true, allowRegression = true)
        assertEquals(60, store.current!!.resumePositionSeconds())
        assertEquals(50, SmartResumePositionReconciler.reconcile(store.current, 50, 160))
    }

    @Test fun `seek invalidates old estimate and rejects old anchor until confirmed`() {
        val store = Store()
        var now = 0L
        val tracker = SmartResumeSessionTracker(store, { now })
        tracker.prepare(seed)
        tracker.onRendererStatus(100, 1000, true)
        now = 60_000
        tracker.noteEstimate(estimate())
        tracker.noteSeekRequested(400)
        assertNull(store.current!!.estimatedProgress)
        tracker.noteEstimate(estimate())
        tracker.onRendererStatus(400, 1000, true)
        tracker.noteEstimate(estimate())
        tracker.checkpoint(SmartResumeCheckpointKind.LIFECYCLE)
        assertEquals(400, store.current!!.confirmedPositionSeconds)
        assertNull(store.current!!.estimatedProgress)
    }

    @Test fun `estimated credits never mark complete or erase a resumable anchor`() {
        val store = Store()
        var now = 0L
        val tracker = SmartResumeSessionTracker(store, { now })
        tracker.prepare(seed)
        tracker.onRendererStatus(950, 1000, true)
        now = 60_000
        tracker.noteEstimate(estimate(1000,950))
        assertEquals(950, store.current!!.resumePositionSeconds())
        assertEquals(SmartResumeRecordState.IN_PROGRESS, store.current!!.state)
        tracker.complete()
        tracker.noteEstimate(estimate(1000,950))
        tracker.checkpoint(SmartResumeCheckpointKind.FAILURE)
        assertEquals(SmartResumeRecordState.FINISHED, store.current!!.state)
        assertNull(store.current!!.estimatedProgress)
    }
}
