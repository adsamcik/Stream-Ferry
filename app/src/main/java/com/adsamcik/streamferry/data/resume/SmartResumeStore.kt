package com.adsamcik.streamferry.data.resume

import android.content.Context
import android.util.AtomicFile
import com.adsamcik.streamferry.core.resume.SmartResumeCheckpoint
import com.adsamcik.streamferry.core.resume.SmartResumeRecord
import com.adsamcik.streamferry.core.resume.SmartResumeRecordState
import com.adsamcik.streamferry.core.resume.SmartResumeRecordStore
import com.adsamcik.streamferry.core.resume.SmartResumeReducer
import com.adsamcik.streamferry.core.resume.SmartResumeSourceType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets

/** Atomic, no-backup persistence for one app-wide latest-playback record. */
class SmartResumeStore(context: Context) : SmartResumeRecordStore {
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
        val record = runCatching {
            atomicFile.openRead().bufferedReader(StandardCharsets.UTF_8).use { decode(JSONObject(it.readText())) }
        }.getOrNull()
        return record?.takeIf { it.isStructurallyValid() } ?: run { atomicFile.delete(); null }
    }

    private fun write(record: SmartResumeRecord) {
        val stream = atomicFile.startWrite()
        try {
            stream.write(encode(record).toString().toByteArray(StandardCharsets.UTF_8))
            stream.flush()
            atomicFile.finishWrite(stream)
        } catch (error: Throwable) {
            atomicFile.failWrite(stream)
            throw error
        }
    }

    private fun encode(r: SmartResumeRecord) = JSONObject().apply {
        put("version", r.version); put("sourceType", r.sourceType.name); put("mediaId", r.mediaId)
        put("displayTitle", r.displayTitle); putNullable("displaySubtitle", r.displaySubtitle); putNullable("durationSeconds", r.durationSeconds)
        putNullable("serverId", r.serverId); putNullable("userId", r.userId); putNullable("localContentUri", r.localContentUri)
        put("confirmedPositionSeconds", r.confirmedPositionSeconds); put("updatedAtMillis", r.updatedAtMillis)
        put("sessionId", r.sessionId); put("generation", r.generation); put("sequence", r.sequence); put("state", r.state.name)
    }

    private fun decode(o: JSONObject) = SmartResumeRecord(
        version = o.getInt("version"), sourceType = SmartResumeSourceType.valueOf(o.getString("sourceType")), mediaId = o.getString("mediaId"),
        displayTitle = o.getString("displayTitle"), displaySubtitle = o.stringOrNull("displaySubtitle"), durationSeconds = o.longOrNull("durationSeconds"),
        serverId = o.stringOrNull("serverId"), userId = o.stringOrNull("userId"), localContentUri = o.stringOrNull("localContentUri"),
        confirmedPositionSeconds = o.getLong("confirmedPositionSeconds"), updatedAtMillis = o.getLong("updatedAtMillis"),
        sessionId = o.getString("sessionId"), generation = o.getLong("generation"), sequence = o.getLong("sequence"),
        state = SmartResumeRecordState.valueOf(o.getString("state")),
    )

    private fun JSONObject.putNullable(name: String, value: Any?) { put(name, value ?: JSONObject.NULL) }
    private fun JSONObject.stringOrNull(name: String): String? = if (!has(name) || isNull(name)) null else getString(name)
    private fun JSONObject.longOrNull(name: String): Long? = if (!has(name) || isNull(name)) null else getLong(name)

    private companion object { const val FILE_NAME = "smart_resume_v1.json" }
}
