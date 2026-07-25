package com.adsamcik.streamferry.core.resume

import java.util.UUID
import kotlin.math.abs

/** Source identity for the app-wide, secret-free latest-playback checkpoint. */
enum class SmartResumeSourceType { JELLYFIN, DOWNLOADED, LOCAL }
enum class SmartResumeRecordState { IN_PROGRESS, FINISHED }
enum class SmartResumeCheckpointKind { STARTED, PROGRESS, SEEK_CONFIRMED, PAUSED, STOPPED, DISCONNECTED, FAILURE, LIFECYCLE, COMPLETED }

/**
 * Data safe to retain across restarts. URLs, tokens, proxy addresses and renderer metadata are
 * deliberately excluded: the record is only an identity, display label and confirmed position.
 */
data class SmartResumeSeed(
    val sourceType: SmartResumeSourceType,
    val mediaId: String,
    val displayTitle: String,
    val displaySubtitle: String? = null,
    val durationSeconds: Long? = null,
    val serverId: String? = null,
    val userId: String? = null,
    val localContentUri: String? = null,
) {
    fun identityKey(): String = listOf(sourceType.name, serverId.orEmpty(), userId.orEmpty(), mediaId, localContentUri.orEmpty())
        .joinToString("\u001f")

    fun isStructurallyValid(): Boolean = when (sourceType) {
        SmartResumeSourceType.JELLYFIN, SmartResumeSourceType.DOWNLOADED ->
            mediaId.isNotBlank() && displayTitle.isNotBlank() && !serverId.isNullOrBlank() && !userId.isNullOrBlank()
        SmartResumeSourceType.LOCAL -> mediaId.isNotBlank() && displayTitle.isNotBlank() && !localContentUri.isNullOrBlank()
    }
}

data class SmartResumeRecord(
    val version: Int = CURRENT_VERSION,
    val sourceType: SmartResumeSourceType,
    val mediaId: String,
    val displayTitle: String,
    val displaySubtitle: String? = null,
    val durationSeconds: Long? = null,
    val serverId: String? = null,
    val userId: String? = null,
    val localContentUri: String? = null,
    val confirmedPositionSeconds: Long,
    val updatedAtMillis: Long,
    val sessionId: String,
    val generation: Long,
    val sequence: Long,
    val state: SmartResumeRecordState,
) {
    fun seed() = SmartResumeSeed(sourceType, mediaId, displayTitle, displaySubtitle, durationSeconds, serverId, userId, localContentUri)
    fun identityKey() = seed().identityKey()
    fun resumePositionSeconds(): Long? = if (state == SmartResumeRecordState.IN_PROGRESS) {
        ResumePolicy.resumePosition(confirmedPositionSeconds, durationSeconds)
    } else null
    fun isStructurallyValid() = version == CURRENT_VERSION && seed().isStructurallyValid() &&
        confirmedPositionSeconds >= 0 && updatedAtMillis >= 0 && sessionId.isNotBlank() && generation > 0 && sequence > 0

    companion object { const val CURRENT_VERSION = 1 }
}

data class SmartResumeCheckpoint(
    val seed: SmartResumeSeed,
    val sessionId: String,
    val generation: Long,
    val sequence: Long,
    val confirmedPositionSeconds: Long,
    val durationSeconds: Long?,
    val updatedAtMillis: Long,
    val kind: SmartResumeCheckpointKind,
)

/** Pure stale-write and position-regression guard. */
object SmartResumeReducer {
    fun reduce(current: SmartResumeRecord?, update: SmartResumeCheckpoint): SmartResumeRecord? {
        if (!update.seed.isStructurallyValid() || update.sequence <= 0 || update.confirmedPositionSeconds < 0) return current
        if (current == null) return if (update.kind == SmartResumeCheckpointKind.STARTED) create(update) else null
        if (current.sessionId != update.sessionId) {
            return if (update.kind == SmartResumeCheckpointKind.STARTED && update.generation > current.generation) create(update) else current
        }
        if (current.identityKey() != update.seed.identityKey() || update.sequence <= current.sequence || current.state == SmartResumeRecordState.FINISHED) return current
        val completed = update.kind == SmartResumeCheckpointKind.COMPLETED
        val regressionAllowed = completed || update.kind == SmartResumeCheckpointKind.SEEK_CONFIRMED
        if (!regressionAllowed && update.confirmedPositionSeconds < current.confirmedPositionSeconds) return current
        return current.copy(
            displayTitle = update.seed.displayTitle,
            displaySubtitle = update.seed.displaySubtitle,
            durationSeconds = update.durationSeconds?.takeIf { it > 0 } ?: current.durationSeconds,
            confirmedPositionSeconds = if (completed) maxOf(current.confirmedPositionSeconds, update.confirmedPositionSeconds) else update.confirmedPositionSeconds,
            updatedAtMillis = maxOf(current.updatedAtMillis, update.updatedAtMillis),
            sequence = update.sequence,
            state = if (completed) SmartResumeRecordState.FINISHED else SmartResumeRecordState.IN_PROGRESS,
        )
    }

    private fun create(update: SmartResumeCheckpoint) = SmartResumeRecord(
        sourceType = update.seed.sourceType,
        mediaId = update.seed.mediaId,
        displayTitle = update.seed.displayTitle,
        displaySubtitle = update.seed.displaySubtitle,
        durationSeconds = update.durationSeconds?.takeIf { it > 0 } ?: update.seed.durationSeconds,
        serverId = update.seed.serverId,
        userId = update.seed.userId,
        localContentUri = update.seed.localContentUri,
        confirmedPositionSeconds = update.confirmedPositionSeconds,
        updatedAtMillis = update.updatedAtMillis,
        sessionId = update.sessionId,
        generation = update.generation,
        sequence = update.sequence,
        state = if (update.kind == SmartResumeCheckpointKind.COMPLETED) SmartResumeRecordState.FINISHED else SmartResumeRecordState.IN_PROGRESS,
    )
}

interface SmartResumeRecordStore {
    val current: SmartResumeRecord?
    fun apply(update: SmartResumeCheckpoint): SmartResumeRecord?
    fun clear()
}

object NoOpSmartResumeRecordStore : SmartResumeRecordStore {
    override val current: SmartResumeRecord? = null
    override fun apply(update: SmartResumeCheckpoint): SmartResumeRecord? = null
    override fun clear() = Unit
}

/**
 * Renderer positions own Smart Resume. A new item cannot overwrite the existing checkpoint until
 * the renderer confirms playback; a terminal completion cannot be resurrected by late teardown events.
 */
class SmartResumeSessionTracker(
    private val store: SmartResumeRecordStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newSessionId: () -> String = { UUID.randomUUID().toString() },
    private val writeIntervalMillis: Long = DEFAULT_WRITE_INTERVAL_MS,
) {
    private data class Session(
        val seed: SmartResumeSeed,
        val id: String,
        val generation: Long,
        var sequence: Long,
        var active: Boolean = false,
        var positionSeconds: Long = 0,
        var durationSeconds: Long? = null,
        var lastWriteMillis: Long = 0,
        var lastPersistedPosition: Long = 0,
        var pendingSeekSeconds: Long? = null,
    )
    private var session: Session? = null

    @Synchronized fun prepare(seed: SmartResumeSeed?) {
        if (seed == null || !seed.isStructurallyValid()) { session = null; return }
        val current = store.current
        session = Session(seed, newSessionId(), (current?.generation ?: 0) + 1, sequence = 0L)
    }

    @Synchronized fun noteSeekRequested(positionSeconds: Long) { session?.pendingSeekSeconds = positionSeconds.coerceAtLeast(0) }

    @Synchronized fun onRendererStatus(positionSeconds: Long, durationSeconds: Long?, isPlaying: Boolean) {
        val s = session ?: return
        val position = positionSeconds.coerceAtLeast(0)
        if (durationSeconds != null && durationSeconds > 0) s.durationSeconds = durationSeconds
        if (!s.active) {
            if (!isPlaying && position <= 0) return
            s.active = true
            s.positionSeconds = position
            persist(s, SmartResumeCheckpointKind.STARTED)
            return
        }
        val seekConfirmed = s.pendingSeekSeconds?.let { abs(it - position) <= POSITION_TOLERANCE_SECONDS } == true
        if (seekConfirmed) {
            s.pendingSeekSeconds = null
            s.positionSeconds = position
            persist(s, SmartResumeCheckpointKind.SEEK_CONFIRMED)
            return
        }
        if (position + POSITION_REGRESSION_TOLERANCE_SECONDS < s.positionSeconds) return
        s.positionSeconds = maxOf(s.positionSeconds, position)
        val crossedThreshold = s.lastPersistedPosition < ResumePolicy.MIN_RESUME_SECONDS && s.positionSeconds >= ResumePolicy.MIN_RESUME_SECONDS
        if (crossedThreshold || clock() - s.lastWriteMillis >= writeIntervalMillis) persist(s, SmartResumeCheckpointKind.PROGRESS)
    }

    @Synchronized fun checkpoint(kind: SmartResumeCheckpointKind) {
        val s = session ?: return
        if (!s.active || kind == SmartResumeCheckpointKind.COMPLETED) return
        persist(s, kind)
    }

    @Synchronized fun complete() { session?.takeIf { it.active }?.let { persist(it, SmartResumeCheckpointKind.COMPLETED) } }
    @Synchronized fun detach() { session = null }

    private fun persist(s: Session, kind: SmartResumeCheckpointKind) {
        val now = clock()
        val persisted = runCatching {
            store.apply(SmartResumeCheckpoint(s.seed.copy(durationSeconds = s.durationSeconds ?: s.seed.durationSeconds), s.id, s.generation, ++s.sequence, s.positionSeconds, s.durationSeconds, now, kind))
        }.getOrNull() ?: return
        s.lastWriteMillis = now
        s.lastPersistedPosition = persisted.confirmedPositionSeconds
    }

    companion object {
        const val DEFAULT_WRITE_INTERVAL_MS = 10_000L
        const val POSITION_TOLERANCE_SECONDS = 5L
        private const val POSITION_REGRESSION_TOLERANCE_SECONDS = 2L
    }
}
