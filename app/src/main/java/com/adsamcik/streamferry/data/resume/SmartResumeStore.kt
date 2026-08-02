package com.adsamcik.streamferry.data.resume

import android.content.Context
import android.util.AtomicFile
import com.adsamcik.streamferry.core.resume.SmartResumeCheckpoint
import com.adsamcik.streamferry.core.resume.SmartResumeRecord
import com.adsamcik.streamferry.core.resume.SmartResumeRecordState
import com.adsamcik.streamferry.core.resume.SmartResumeRecordStore
import com.adsamcik.streamferry.core.resume.SmartResumeReducer
import com.adsamcik.streamferry.core.resume.SmartResumeSourceType
import com.adsamcik.streamferry.core.stream.Protocol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets

/** Atomic, no-backup persistence for one app-wide latest-playback record. */
class SmartResumeStore(context: Context) : SmartResumeRecordStore {
    // Keep the v1 filename: updating it in place is what preserves existing users' checkpoint.
    private val atomicFile = AtomicFile(File(context.applicationContext.noBackupFilesDir, FILE_NAME))
    private val lock = Any()
    private val _record = MutableStateFlow(load())
    val record: StateFlow<SmartResumeRecord?> = _record.asStateFlow()
    override val current: SmartResumeRecord? get() = _record.value

    override fun apply(update: SmartResumeCheckpoint): SmartResumeRecord? = synchronized(lock) {
        val next = SmartResumeReducer.reduce(_record.value, update)
        if (next == _record.value) return@synchronized next
        if (next == null) atomicFile.delete() else write(next)
        _record.value = next
        next
    }

    override fun clear() = synchronized(lock) { atomicFile.delete(); _record.value = null }

    private fun load(): SmartResumeRecord? {
        if (!atomicFile.baseFile.isFile) return null
        val decoded = runCatching {
            atomicFile.openRead().bufferedReader(StandardCharsets.UTF_8).use {
                SmartResumeJsonCodec.decode(JSONObject(it.readText()))
            }
        }.getOrNull()
        val record = decoded?.record?.takeIf { it.isStructurallyValid() }
        if (record == null) {
            atomicFile.delete()
            return null
        }
        // A valid v1 document is immediately made v2, so the next restart no longer needs migration.
        if (decoded.migratedFromV1) runCatching { write(record) }
        return record
    }

    private fun write(record: SmartResumeRecord) {
        val stream = atomicFile.startWrite()
        try {
            stream.write(SmartResumeJsonCodec.encode(record).toString().toByteArray(StandardCharsets.UTF_8))
            stream.flush()
            atomicFile.finishWrite(stream)
        } catch (error: Throwable) {
            atomicFile.failWrite(stream)
            throw error
        }
    }

    private companion object { const val FILE_NAME = "smart_resume_v1.json" }
}

/** Framework-free v1-to-v2 migration and defaulting policy. */
internal object SmartResumeRecordVersioning {
    internal data class StoredFields(
        val sourceType: SmartResumeSourceType,
        val mediaId: String,
        val displayTitle: String,
        val displaySubtitle: String?,
        val durationSeconds: Long?,
        val serverId: String?,
        val userId: String?,
        val localContentUri: String?,
        val confirmedPositionSeconds: Long,
        val updatedAtMillis: Long,
        val sessionId: String,
        val generation: Long,
        val sequence: Long,
        val state: SmartResumeRecordState,
        val physicalDeviceStableId: String? = null,
        val physicalDeviceReference: String? = null,
        val lastSuccessfulProtocol: String? = null,
        val stableEndpointIdentity: String? = null,
    )

    internal data class Decoded(val record: SmartResumeRecord, val migratedFromV1: Boolean)

    fun migrate(version: Int, fields: StoredFields): Decoded? {
        if (version !in 1..SmartResumeRecord.CURRENT_VERSION) return null
        val protocol = fields.lastSuccessfulProtocol?.let { runCatching { Protocol.valueOf(it) }.getOrNull() ?: return null }
        return Decoded(
            SmartResumeRecord(
                version = SmartResumeRecord.CURRENT_VERSION,
                sourceType = fields.sourceType, mediaId = fields.mediaId, displayTitle = fields.displayTitle,
                displaySubtitle = fields.displaySubtitle, durationSeconds = fields.durationSeconds,
                serverId = fields.serverId, userId = fields.userId, localContentUri = fields.localContentUri,
                confirmedPositionSeconds = fields.confirmedPositionSeconds, updatedAtMillis = fields.updatedAtMillis,
                sessionId = fields.sessionId, generation = fields.generation, sequence = fields.sequence, state = fields.state,
                physicalDeviceStableId = fields.physicalDeviceStableId, physicalDeviceReference = fields.physicalDeviceReference,
                lastSuccessfulProtocol = protocol, stableEndpointIdentity = fields.stableEndpointIdentity,
            ),
            migratedFromV1 = version == 1,
        )
    }
}

/** Versioned Android JSON boundary. Malformed documents throw to the store's safe-discard boundary. */
internal object SmartResumeJsonCodec {
    fun encode(r: SmartResumeRecord) = JSONObject().apply {
        put("version", SmartResumeRecord.CURRENT_VERSION); put("sourceType", r.sourceType.name); put("mediaId", r.mediaId)
        put("displayTitle", r.displayTitle); putNullable("displaySubtitle", r.displaySubtitle); putNullable("durationSeconds", r.durationSeconds)
        putNullable("serverId", r.serverId); putNullable("userId", r.userId); putNullable("localContentUri", r.localContentUri)
        put("confirmedPositionSeconds", r.confirmedPositionSeconds); put("updatedAtMillis", r.updatedAtMillis)
        put("sessionId", r.sessionId); put("generation", r.generation); put("sequence", r.sequence); put("state", r.state.name)
        putNullable("physicalDeviceStableId", r.physicalDeviceStableId)
        putNullable("physicalDeviceReference", r.physicalDeviceReference)
        putNullable("lastSuccessfulProtocol", r.lastSuccessfulProtocol?.name)
        putNullable("stableEndpointIdentity", r.stableEndpointIdentity)
    }

    fun decode(o: JSONObject): SmartResumeRecordVersioning.Decoded? = SmartResumeRecordVersioning.migrate(
        version = o.getInt("version"),
        fields = SmartResumeRecordVersioning.StoredFields(
            sourceType = SmartResumeSourceType.valueOf(o.getString("sourceType")), mediaId = o.getString("mediaId"),
            displayTitle = o.getString("displayTitle"), displaySubtitle = o.stringOrNull("displaySubtitle"), durationSeconds = o.longOrNull("durationSeconds"),
            serverId = o.stringOrNull("serverId"), userId = o.stringOrNull("userId"), localContentUri = o.stringOrNull("localContentUri"),
            confirmedPositionSeconds = o.getLong("confirmedPositionSeconds"), updatedAtMillis = o.getLong("updatedAtMillis"),
            sessionId = o.getString("sessionId"), generation = o.getLong("generation"), sequence = o.getLong("sequence"),
            state = SmartResumeRecordState.valueOf(o.getString("state")),
            physicalDeviceStableId = o.stringOrNull("physicalDeviceStableId"),
            physicalDeviceReference = o.stringOrNull("physicalDeviceReference"),
            lastSuccessfulProtocol = o.stringOrNull("lastSuccessfulProtocol"),
            stableEndpointIdentity = o.stringOrNull("stableEndpointIdentity"),
        ),
    )

    private fun JSONObject.putNullable(name: String, value: Any?) { put(name, value ?: JSONObject.NULL) }
    private fun JSONObject.stringOrNull(name: String): String? = if (!has(name) || isNull(name)) null else getString(name)
    private fun JSONObject.longOrNull(name: String): Long? = if (!has(name) || isNull(name)) null else getLong(name)
}
