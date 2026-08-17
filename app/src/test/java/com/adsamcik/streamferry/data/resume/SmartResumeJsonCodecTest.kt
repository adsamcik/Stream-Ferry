package com.adsamcik.streamferry.data.resume

import com.adsamcik.streamferry.core.resume.SmartResumeCheckpoint
import com.adsamcik.streamferry.core.resume.SmartResumeCheckpointKind
import com.adsamcik.streamferry.core.resume.SmartResumeHistoryReducer
import com.adsamcik.streamferry.core.resume.SmartResumeRecord
import com.adsamcik.streamferry.core.resume.SmartResumeRecordState
import com.adsamcik.streamferry.core.resume.SmartResumeSeed
import com.adsamcik.streamferry.core.resume.SmartResumeSourceType
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SmartResumeRecordVersioningTest {
    private val historyFile: File
        get() = File(RuntimeEnvironment.getApplication().noBackupFilesDir, "smart_resume_v1.json")

    @Before fun setUp() { historyFile.delete() }
    @After fun tearDown() { historyFile.delete() }

    private fun v1Fields() = SmartResumeRecordVersioning.StoredFields(
        sourceType = SmartResumeSourceType.REMOTE, mediaId = "movie", displayTitle = "Movie",
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

    @Test fun legacyProviderNamedSourceTypeMigratesToRemote() {
        val record = SmartResumeRecordVersioning.migrate(1, v1Fields())!!.record
        val legacy = SmartResumeJsonCodec.encode(record).put("sourceType", "JELLYFIN")

        val decoded = SmartResumeJsonCodec.decode(legacy)!!.record

        assertEquals(SmartResumeSourceType.REMOTE, decoded.sourceType)
        assertEquals(record.mediaId, decoded.mediaId)
    }

    @Test fun legacySingleRecordBecomesAHistoryEnvelope() {
        val record = SmartResumeRecordVersioning.migrate(1, v1Fields())!!.record

        val decoded = SmartResumeHistoryJsonCodec.decode(SmartResumeJsonCodec.encode(record))!!

        assertEquals(listOf(record), decoded.records)
        assertEquals(true, decoded.requiresRewrite)
        val envelope = SmartResumeHistoryJsonCodec.encode(decoded.records)
        assertEquals(1, envelope.getInt("version"))
        assertEquals(1, envelope.getJSONArray("records").length())
    }

    @Test fun malformedHistoryEntriesAreDiscardedWithoutLosingValidRecords() {
        val record = SmartResumeRecordVersioning.migrate(1, v1Fields())!!.record
        val envelope = SmartResumeHistoryJsonCodec.encode(listOf(record))
        envelope.getJSONArray("records").put(JSONObject().put("version", 99))

        val decoded = SmartResumeHistoryJsonCodec.decode(envelope)!!

        assertEquals(listOf(record), decoded.records)
        assertEquals(true, decoded.requiresRewrite)
    }

    @Test fun reopeningTheStorePrunesAnExpiredHistoryFile() {
        var now = 100_000L
        val context = RuntimeEnvironment.getApplication()
        val store = SmartResumeStore(context) { now }
        store.apply(
            SmartResumeCheckpoint(
                seed = SmartResumeSeed(
                    SmartResumeSourceType.REMOTE,
                    mediaId = "movie",
                    displayTitle = "Movie",
                    durationSeconds = 600,
                    serverId = "server",
                    userId = "user",
                ),
                sessionId = "session",
                generation = 1,
                sequence = 1,
                confirmedPositionSeconds = 120,
                durationSeconds = 600,
                updatedAtMillis = now,
                kind = SmartResumeCheckpointKind.STARTED,
            ),
        )
        assertEquals(1, store.history.value.size)

        now += SmartResumeHistoryReducer.RETENTION_MILLIS + 1
        val reopened = SmartResumeStore(context) { now }

        assertEquals(emptyList(), reopened.history.value)
        assertFalse(historyFile.exists())
    }
}
