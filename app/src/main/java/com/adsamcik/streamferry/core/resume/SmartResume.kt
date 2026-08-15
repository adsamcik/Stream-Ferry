package com.adsamcik.streamferry.core.resume

import com.adsamcik.streamferry.core.stream.Protocol
import java.util.UUID
import kotlin.math.abs

/** Source identity for a secret-free, renderer-confirmed playback-history checkpoint. */
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

/**
 * A deliberately small, secret-free hint for reconnecting a saved playback session to a device.
 * These are stable identities only. Callers must never put a proxy URL, host/port, token, session id,
 * or transport address here.
 */
data class SmartResumeDeviceContext(
    val physicalDeviceStableId: String? = null,
    val physicalDeviceReference: String? = null,
    val lastSuccessfulProtocol: Protocol? = null,
    val stableEndpointIdentity: String? = null,
) {
    fun isStructurallyValid(): Boolean =
        listOfNotNull(physicalDeviceStableId, physicalDeviceReference, stableEndpointIdentity).all { it.isNotBlank() }
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
    val physicalDeviceStableId: String? = null,
    val physicalDeviceReference: String? = null,
    val lastSuccessfulProtocol: Protocol? = null,
    val stableEndpointIdentity: String? = null,
) {
    fun seed() = SmartResumeSeed(sourceType, mediaId, displayTitle, displaySubtitle, durationSeconds, serverId, userId, localContentUri)
    fun identityKey() = seed().identityKey()
    fun deviceContext() = SmartResumeDeviceContext(
        physicalDeviceStableId, physicalDeviceReference, lastSuccessfulProtocol, stableEndpointIdentity,
    )
    fun resumePositionSeconds(): Long? = if (state == SmartResumeRecordState.IN_PROGRESS) {
        ResumePolicy.resumePosition(confirmedPositionSeconds, durationSeconds)
    } else null
    fun isStructurallyValid() = version == CURRENT_VERSION && seed().isStructurallyValid() && deviceContext().isStructurallyValid() &&
        confirmedPositionSeconds >= 0 && updatedAtMillis >= 0 && sessionId.isNotBlank() && generation > 0 && sequence > 0

    companion object { const val CURRENT_VERSION = 2 }
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
    val deviceContext: SmartResumeDeviceContext? = null,
)

/**
 * Reconciles independently-confirmed renderer and Jellyfin positions without letting a stale server
 * checkpoint move this device backwards. Finished records remain finished even if late telemetry arrives.
 */
object SmartResumePositionReconciler {
    /**
     * Reconcile a crash-safe renderer checkpoint only when it belongs to the exact Jellyfin account and
     * library item being played. A stale checkpoint from another show or account must never move playback.
     */
    fun reconcileJellyfinItem(
        record: SmartResumeRecord?,
        itemId: String,
        serverId: String,
        userId: String,
        jellyfinResumeSeconds: Long?,
    ): Long? {
        val matchingRecord = record?.takeIf {
            it.sourceType == SmartResumeSourceType.JELLYFIN &&
                it.mediaId == itemId &&
                it.serverId == serverId &&
                it.userId == userId
        }
        return if (matchingRecord != null) {
            reconcile(matchingRecord, rendererConfirmedSeconds = null, jellyfinResumeSeconds = jellyfinResumeSeconds)
        } else {
            jellyfinResumeSeconds
        }
    }

    fun reconcile(
        record: SmartResumeRecord?,
        rendererConfirmedSeconds: Long?,
        jellyfinResumeSeconds: Long?,
    ): Long? {
        if (record?.state == SmartResumeRecordState.FINISHED) return null
        val position = listOfNotNull(record?.confirmedPositionSeconds, rendererConfirmedSeconds, jellyfinResumeSeconds)
            .filter { it >= 0 }
            .maxOrNull() ?: return null
        return ResumePolicy.resumePosition(position, record?.durationSeconds)
    }
}

/** Pure stale-write and position-regression guard. */
object SmartResumeReducer {
    fun reduce(current: SmartResumeRecord?, update: SmartResumeCheckpoint): SmartResumeRecord? {
        if (!update.seed.isStructurallyValid() || update.generation <= 0 || update.sequence <= 0 || update.confirmedPositionSeconds < 0 ||
            update.deviceContext?.isStructurallyValid() == false) return current
        if (current == null) return if (update.kind == SmartResumeCheckpointKind.STARTED) create(update) else null
        if (current.sessionId != update.sessionId) {
            if (update.kind != SmartResumeCheckpointKind.STARTED || update.generation <= current.generation) return current
            // A delayed/optimistic zero position from a fresh renderer session must not wipe the last
            // durable checkpoint for the exact same unfinished item. A confirmed backward seek in the
            // previous session is already represented by current.confirmedPositionSeconds.
            val minimumPosition = current.takeIf {
                it.state == SmartResumeRecordState.IN_PROGRESS && it.identityKey() == update.seed.identityKey()
            }?.confirmedPositionSeconds
            return create(update, minimumPosition)
        }
        if (update.generation != current.generation || current.identityKey() != update.seed.identityKey() ||
            update.sequence <= current.sequence || current.state == SmartResumeRecordState.FINISHED) return current
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
            physicalDeviceStableId = update.deviceContext?.physicalDeviceStableId ?: current.physicalDeviceStableId,
            physicalDeviceReference = update.deviceContext?.physicalDeviceReference ?: current.physicalDeviceReference,
            lastSuccessfulProtocol = update.deviceContext?.lastSuccessfulProtocol ?: current.lastSuccessfulProtocol,
            stableEndpointIdentity = update.deviceContext?.stableEndpointIdentity ?: current.stableEndpointIdentity,
        )
    }

    private fun create(
        update: SmartResumeCheckpoint,
        minimumPositionSeconds: Long? = null,
    ) = SmartResumeRecord(
        sourceType = update.seed.sourceType,
        mediaId = update.seed.mediaId,
        displayTitle = update.seed.displayTitle,
        displaySubtitle = update.seed.displaySubtitle,
        durationSeconds = update.durationSeconds?.takeIf { it > 0 } ?: update.seed.durationSeconds,
        serverId = update.seed.serverId,
        userId = update.seed.userId,
        localContentUri = update.seed.localContentUri,
        confirmedPositionSeconds = maxOf(update.confirmedPositionSeconds, minimumPositionSeconds ?: 0L),
        updatedAtMillis = update.updatedAtMillis,
        sessionId = update.sessionId,
        generation = update.generation,
        sequence = update.sequence,
        state = if (update.kind == SmartResumeCheckpointKind.COMPLETED) SmartResumeRecordState.FINISHED else SmartResumeRecordState.IN_PROGRESS,
        physicalDeviceStableId = update.deviceContext?.physicalDeviceStableId,
        physicalDeviceReference = update.deviceContext?.physicalDeviceReference,
        lastSuccessfulProtocol = update.deviceContext?.lastSuccessfulProtocol,
        stableEndpointIdentity = update.deviceContext?.stableEndpointIdentity,
    )
}

/**
 * Keeps a rolling, newest-first history while preserving [SmartResumeReducer]'s stale-session and
 * position-regression guarantees. Generations are app-wide and therefore remain the authoritative
 * ordering signal even if the wall clock changes between playback sessions. A generous count ceiling
 * remains as a corruption/abuse guard; normal retention is governed by [RETENTION_MILLIS].
 */
object SmartResumeHistoryReducer {
    const val RETENTION_DAYS = 90L
    const val RETENTION_MILLIS = RETENTION_DAYS * 24 * 60 * 60 * 1_000L
    const val MAX_ENTRIES = 500

    fun reduce(
        history: List<SmartResumeRecord>,
        update: SmartResumeCheckpoint,
        nowMillis: Long = update.updatedAtMillis,
    ): List<SmartResumeRecord> {
        val normalized = normalize(history, nowMillis)
        val current = normalized.firstOrNull()
        val next = SmartResumeReducer.reduce(current, update) ?: return normalized
        if (next == current) return normalized
        return normalize(
            listOf(next) + normalized.filterNot { it.identityKey() == next.identityKey() },
            nowMillis,
        )
    }

    fun normalize(
        history: List<SmartResumeRecord>,
        nowMillis: Long? = null,
    ): List<SmartResumeRecord> {
        val cutoffMillis = nowMillis?.let { (it - RETENTION_MILLIS).coerceAtLeast(0L) }
        return history
        .asSequence()
        .filter(SmartResumeRecord::isStructurallyValid)
        .filter { cutoffMillis == null || it.updatedAtMillis >= cutoffMillis }
        .groupBy(SmartResumeRecord::identityKey)
        .values
        .mapNotNull { records ->
            records.maxWithOrNull(
                compareBy<SmartResumeRecord> { it.generation }
                    .thenBy { it.sequence }
                    .thenBy { it.updatedAtMillis },
            )
        }
        .sortedWith(
            compareByDescending<SmartResumeRecord> { it.generation }
                .thenByDescending { it.sequence }
                .thenByDescending { it.updatedAtMillis },
        )
        .take(MAX_ENTRIES)
        .toList()
    }
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
 * Renderer positions own Smart Resume. A new item cannot become the latest history entry until the
 * renderer confirms playback; a terminal completion cannot be resurrected by late teardown events.
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
        var deviceContext: SmartResumeDeviceContext? = null,
    )
    private var session: Session? = null

    @Synchronized fun prepare(seed: SmartResumeSeed?, deviceContext: SmartResumeDeviceContext? = null) {
        if (seed == null || !seed.isStructurallyValid()) { session = null; return }
        val current = store.current
        session = Session(
            seed, newSessionId(), (current?.generation ?: 0) + 1, sequence = 0L,
            deviceContext = deviceContext?.takeIf { it.isStructurallyValid() },
        )
    }

    /** Supplies or refreshes safe device identities after preparation, once a renderer has connected. */
    @Synchronized fun updateDeviceContext(deviceContext: SmartResumeDeviceContext?) {
        if (deviceContext?.isStructurallyValid() == true) session?.deviceContext = deviceContext
    }

    @Synchronized fun noteSeekRequested(positionSeconds: Long) { session?.pendingSeekSeconds = positionSeconds.coerceAtLeast(0) }

    /** A confirmed renderer status can also atomically persist the safe endpoint that succeeded. */
    @Synchronized fun onRendererStatus(
        positionSeconds: Long,
        durationSeconds: Long?,
        isPlaying: Boolean,
        deviceContext: SmartResumeDeviceContext? = null,
    ) {
        val s = session ?: return
        if (deviceContext?.isStructurallyValid() == true) s.deviceContext = deviceContext
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

    @Synchronized fun checkpoint(kind: SmartResumeCheckpointKind, deviceContext: SmartResumeDeviceContext? = null) {
        val s = session ?: return
        if (!s.active || kind == SmartResumeCheckpointKind.COMPLETED) return
        if (deviceContext?.isStructurallyValid() == true) s.deviceContext = deviceContext
        persist(s, kind)
    }

    @Synchronized fun complete(deviceContext: SmartResumeDeviceContext? = null) {
        session?.takeIf { it.active }?.let {
            if (deviceContext?.isStructurallyValid() == true) it.deviceContext = deviceContext
            persist(it, SmartResumeCheckpointKind.COMPLETED)
        }
    }
    @Synchronized fun detach() { session = null }

    private fun persist(s: Session, kind: SmartResumeCheckpointKind) {
        val now = clock()
        val persisted = runCatching {
            store.apply(
                SmartResumeCheckpoint(
                    s.seed.copy(durationSeconds = s.durationSeconds ?: s.seed.durationSeconds), s.id,
                    s.generation, ++s.sequence, s.positionSeconds, s.durationSeconds, now, kind, s.deviceContext,
                ),
            )
        }.getOrNull() ?: return
        s.lastWriteMillis = now
        s.lastPersistedPosition = persisted.confirmedPositionSeconds
    }

    companion object {
        // Bounds loss after a sudden process death while keeping AtomicFile writes sparse.
        const val DEFAULT_WRITE_INTERVAL_MS = 5_000L
        const val POSITION_TOLERANCE_SECONDS = 5L
        private const val POSITION_REGRESSION_TOLERANCE_SECONDS = 2L
    }
}
