package com.adsamcik.streamferry.data.resume

import com.adsamcik.streamferry.core.resume.SmartResumeRecord
import com.adsamcik.streamferry.core.resume.SmartResumeRecordState
import com.adsamcik.streamferry.core.resume.SmartResumeSourceType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class SmartResumeRecordVersioningTest {
    private fun v1Fields() = SmartResumeRecordVersioning.StoredFields(
        sourceType = SmartResumeSourceType.JELLYFIN, mediaId = "movie", displayTitle = "Movie",
        displaySubtitle = null, durationSeconds = 600, serverId = "server", userId = "user", localContentUri = null,
        confirmedPositionSeconds = 120, updatedAtMillis = 99, sessionId = "session", generation = 1, sequence = 1,
        state = SmartResumeRecordState.IN_PROGRESS,
    )

    @Test fun validV1MigratesWithSafeDeviceDefaults() {
        val decoded = SmartResumeRecordVersioning.migrate(1, v1Fields())!!

        assertEquals(SmartResumeRecord.CURRENT_VERSION, decoded.record.version)
        assertEquals(true, decoded.migratedFromV1)
        assertNull(decoded.record.physicalDeviceStableId)
        assertNull(decoded.record.lastSuccessfulProtocol)
        assertEquals(true, decoded.record.isStructurallyValid())
    }

    @Test fun malformedVersionOrProtocolIsDiscarded() {
        assertNull(SmartResumeRecordVersioning.migrate(99, v1Fields()))
        assertNull(SmartResumeRecordVersioning.migrate(2, v1Fields().copy(lastSuccessfulProtocol = "not-a-protocol")))
        assertFalse(SmartResumeRecordVersioning.migrate(1, v1Fields())!!.record.version == 1)
    }
}
