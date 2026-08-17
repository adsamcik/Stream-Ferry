package com.adsamcik.streamferry.data.resume

import android.content.Context
import android.util.AtomicFile
import com.adsamcik.streamferry.core.resume.SmartResumeCheckpoint
import com.adsamcik.streamferry.core.resume.SmartResumeRecord
import com.adsamcik.streamferry.core.resume.SmartResumeRecordState
import com.adsamcik.streamferry.core.resume.SmartResumeRecordStore
import com.adsamcik.streamferry.core.resume.SmartResumeHistoryReducer
import com.adsamcik.streamferry.core.resume.SmartResumeSourceType
import com.adsamcik.streamferry.core.stream.Protocol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets

/** Atomic, no-backup persistence for rolling app-wide playback history. */
class SmartResumeStore(
    context: Context,
    private val clock: () -> Long = System::currentTimeMillis,
) : SmartResumeRecordStore {
    // Keep the v1 filename: the history envelope migrates the existing single checkpoint in place.
    private val atomicFile = AtomicFile(File(context.applicationContext.noBackupFilesDir, FILE_NAME))
    private val lock = Any()
    private val loaded = load()
    private val _history = MutableStateFlow(loaded.records)
    val history: StateFlow<List<SmartResumeRecord>> = _history.asStateFlow()
    private val _record = MutableStateFlow(loaded.records.firstOrNull())
    val record: StateFlow<SmartResumeRecord?> = _record.asStateFlow()
    override val current: SmartResumeRecord? get() = _record.value

    init {
        if (loaded.requiresRewrite) runCatching { write(loaded.records) }
    }

    override fun apply(update: SmartResumeCheckpoint): SmartResumeRecord? = synchronized(lock) {
        val next = SmartResumeHistoryReducer.reduce(_history.value, update, clock())
        if (next == _history.value) return@synchronized _record.value
        if (next.isEmpty()) atomicFile.delete() else write(next)
        publish(next)
        _record.value
    }

    /** Removes one media identity without discarding the rest of the user's history. */
    fun remove(identityKey: String): Boolean = synchronized(lock) {
        val next = _history.value.filterNot { it.identityKey() == identityKey }
        if (next.size == _history.value.size) return@synchronized false
        if (next.isEmpty()) atomicFile.delete() else write(next)
        publish(next)
        true
    }

    override fun clear() = synchronized(lock) {
        atomicFile.delete()
        publish(emptyList())
    }

    private fun load(): SmartResumeHistoryJsonCodec.Decoded {
        if (!atomicFile.baseFile.isFile) return SmartResumeHistoryJsonCodec.Decoded(emptyList(), false)
        val decoded = runCatching {
            atomicFile.openRead().bufferedReader(StandardCharsets.UTF_8).use {
                SmartResumeHistoryJsonCodec.decode(JSONObject(it.readText()))
            }
        }.getOrNull()
        if (decoded == null) {
            atomicFile.delete()
            return SmartResumeHistoryJsonCodec.Decoded(emptyList(), false)
        }
        val retained = SmartResumeHistoryReducer.normalize(decoded.records, clock())
        if (retained.isEmpty()) {
            atomicFile.delete()
            return SmartResumeHistoryJsonCodec.Decoded(emptyList(), false)
        }
        return decoded.copy(
            records = retained,
            requiresRewrite = decoded.requiresRewrite || retained != decoded.records,
        )
    }

    private fun write(records: List<SmartResumeRecord>) {
        val stream = atomicFile.startWrite()
        try {
            stream.write(SmartResumeHistoryJsonCodec.encode(records).toString().toByteArray(StandardCharsets.UTF_8))
            stream.flush()
            atomicFile.finishWrite(stream)
        } catch (error: Throwable) {
            atomicFile.failWrite(stream)
            throw error
        }
    }

    private fun publish(records: List<SmartResumeRecord>) {
        _history.value = records
        _record.value = records.firstOrNull()
    }

    private companion object { const val FILE_NAME = "smart_resume_v1.json" }
}

/** Versioned history envelope. A legacy top-level record is accepted and rewritten on first load. */
internal object SmartResumeHistoryJsonCodec {
    private const val CURRENT_VERSION = 1

    internal data class Decoded(
        val records: List<SmartResumeRecord>,
        val requiresRewrite: Boolean,
    )

    fun encode(records: List<SmartResumeRecord>) = JSONObject().apply {
        put("version", CURRENT_VERSION)
        put("records", JSONArray().apply {
            SmartResumeHistoryReducer.normalize(records).forEach { put(SmartResumeJsonCodec.encode(it)) }
        })
    }

    fun decode(document: JSONObject): Decoded? {
        if (!document.has("records")) {
            val legacy = SmartResumeJsonCodec.decode(document)?.record?.takeIf { it.isStructurallyValid() }
                ?: return null
            return Decoded(listOf(legacy), requiresRewrite = true)
        }
        if (document.getInt("version") != CURRENT_VERSION) return null
        val encoded = document.getJSONArray("records")
        val decoded = buildList {
            for (index in 0 until encoded.length()) {
                runCatching { SmartResumeJsonCodec.decode(encoded.getJSONObject(index))?.record }
                    .getOrNull()
                    ?.takeIf(SmartResumeRecord::isStructurallyValid)
                    ?.let(::add)
            }
        }
        val normalized = SmartResumeHistoryReducer.normalize(decoded)
        return Decoded(normalized, requiresRewrite = normalized.size != encoded.length())
    }
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
            sourceType = when (val stored = o.getString("sourceType")) {
                "JELLYFIN" -> SmartResumeSourceType.REMOTE
                else -> SmartResumeSourceType.valueOf(stored)
            },
            mediaId = o.getString("mediaId"),
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
