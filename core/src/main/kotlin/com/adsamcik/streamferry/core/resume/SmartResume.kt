package com.adsamcik.streamferry.core.resume

import com.adsamcik.streamferry.core.stream.Protocol
import java.util.UUID
import kotlin.math.abs

/** Source identity for a secret-free, renderer-confirmed playback-history checkpoint. */
enum class SmartResumeSourceType { REMOTE, DOWNLOADED, LOCAL }
enum class SmartResumeRecordState { IN_PROGRESS, FINISHED }
enum class SmartResumeCheckpointKind { STARTED, PROGRESS, SEEK_CONFIRMED, PAUSED, STOPPED, DISCONNECTED, FAILURE, LIFECYCLE, COMPLETED }

data class SavedProgressEstimate(val progress: ProgressEstimate, val capturedAtMillis: Long) {
    fun isValid() = progress.isValid() && capturedAtMillis >= 0
}

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
        SmartResumeSourceType.REMOTE, SmartResumeSourceType.DOWNLOADED ->
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
    val estimatedProgress: SavedProgressEstimate? = null,
) {
    fun seed() = SmartResumeSeed(sourceType, mediaId, displayTitle, displaySubtitle, durationSeconds, serverId, userId, localContentUri)
    fun identityKey() = seed().identityKey()
    fun deviceContext() = SmartResumeDeviceContext(
        physicalDeviceStableId, physicalDeviceReference, lastSuccessfulProtocol, stableEndpointIdentity,
    )
    fun resumePositionSeconds(): Long? = if (state == SmartResumeRecordState.IN_PROGRESS) {
        val estimated = estimatedProgress?.takeIf { it.isValid() && it.progress.anchorPositionSeconds == confirmedPositionSeconds }
            ?.progress?.recoveryPositionSeconds()
        // An estimate near the credits must not erase an otherwise resumable confirmed checkpoint.
        estimated?.let { ResumePolicy.resumePosition(maxOf(it, confirmedPositionSeconds), durationSeconds) }
            ?: ResumePolicy.resumePosition(confirmedPositionSeconds, durationSeconds)
    } else null
    fun isStructurallyValid() = version == CURRENT_VERSION && seed().isStructurallyValid() && deviceContext().isStructurallyValid() &&
        confirmedPositionSeconds >= 0 && updatedAtMillis >= 0 && sessionId.isNotBlank() && generation > 0 && sequence > 0 &&
        (estimatedProgress == null || estimatedProgress.isValid())

    companion object { const val CURRENT_VERSION = 3 }
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
    val estimatedProgress: SavedProgressEstimate? = null,
)

/**
 * Reconciles independently-confirmed renderer and source positions without letting a stale server
 * checkpoint move this device backwards. A fresh TV report can correct either cached position.
 * Finished records remain finished even if late telemetry arrives.
 */
object SmartResumePositionReconciler {
    /**
     * Reconcile a crash-safe renderer checkpoint only when it belongs to the exact source account and
     * library item being played. A stale checkpoint from another show or account must never move playback.
     */
    fun reconcileRemoteItem(
        record: SmartResumeRecord?,
        itemId: String,
        serverId: String,
        userId: String,
        sourceResumeSeconds: Long?,
    ): Long? {
        val matchingRecord = record?.takeIf {
            it.sourceType == SmartResumeSourceType.REMOTE &&
                it.mediaId == itemId &&
                it.serverId == serverId &&
                it.userId == userId
        }
        return if (matchingRecord != null) {
            reconcile(matchingRecord, rendererConfirmedSeconds = null, sourceResumeSeconds = sourceResumeSeconds)
        } else {
            sourceResumeSeconds
        }
    }

    fun reconcile(
        record: SmartResumeRecord?,
        rendererConfirmedSeconds: Long?,
        sourceResumeSeconds: Long?,
    ): Long? {
        if (record?.state == SmartResumeRecordState.FINISHED) return null
        if (rendererConfirmedSeconds != null) return ResumePolicy.resumePosition(rendererConfirmedSeconds, record?.durationSeconds)
        val position = listOfNotNull(record?.resumePositionSeconds(), record?.confirmedPositionSeconds, sourceResumeSeconds)
            .filter { it >= 0 }
            .maxOrNull() ?: return null
        return ResumePolicy.resumePosition(position, record?.durationSeconds)
    }
}

/** Pure stale-write and position-regression guard. */
object SmartResumeReducer {
    fun reduce(current: SmartResumeRecord?, update: SmartResumeCheckpoint): SmartResumeRecord? {
        if (!update.seed.isStructurallyValid() || update.generation <= 0 || update.sequence <= 0 || update.confirmedPositionSeconds < 0 ||
            update.deviceContext?.isStructurallyValid() == false || update.estimatedProgress?.isValid() == false) return current
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
            estimatedProgress = update.estimatedProgress?.takeIf {
                !completed && it.progress.anchorPositionSeconds == update.confirmedPositionSeconds
            },
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
        var estimatedProgress: SavedProgressEstimate? = null,
        var hasPersistedEstimate: Boolean = false,
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

    /** Shared by legacy resume storage, queue checkpoints and reconnect; never an SDK display tick. */
    @Synchronized fun recoveryPositionSeconds(): Long? {
        val s = session?.takeIf { it.active } ?: return null
        val estimate = s.estimatedProgress?.progress?.takeIf { it.anchorPositionSeconds == s.positionSeconds }
            ?.recoveryPositionSeconds()?.let { ResumePolicy.resumePosition(it, s.durationSeconds) }
        return maxOf(s.positionSeconds, estimate ?: 0)
    }

    /** In-memory intent for a live continuation; durable checkpoints remain renderer-confirmed. */
    @Synchronized fun pendingSeekPositionSeconds(): Long? = session?.pendingSeekSeconds

    @Synchronized fun cancelPendingSeek(positionSeconds: Long) {
        session?.takeIf { it.pendingSeekSeconds == positionSeconds }?.pendingSeekSeconds = null
    }

    @Synchronized fun noteSeekRequested(positionSeconds: Long) {
        session?.let { s ->
            s.pendingSeekSeconds = positionSeconds.coerceAtLeast(0)
            if (s.estimatedProgress != null) { s.estimatedProgress = null; persist(s, SmartResumeCheckpointKind.PROGRESS) }
        }
    }

    @Synchronized fun noteEstimate(estimate: ProgressEstimate) {
        val s = session ?: return
        if (!s.active || s.pendingSeekSeconds != null || !estimate.isValid() ||
            estimate.anchorPositionSeconds != s.positionSeconds || estimate.positionSeconds <= s.positionSeconds) return
        val previous = s.estimatedProgress?.progress
        if (previous != null && estimate.confidence == previous.confidence &&
            estimate.recoveryPositionSeconds() < previous.recoveryPositionSeconds()) return
        s.estimatedProgress = SavedProgressEstimate(estimate, clock())
        if (clock() - s.lastWriteMillis >= writeIntervalMillis) persist(s, SmartResumeCheckpointKind.PROGRESS)
    }

    /** A confirmed renderer status can also atomically persist the safe endpoint that succeeded. */
    @Synchronized fun onRendererStatus(
        positionSeconds: Long,
        durationSeconds: Long?,
        isPlaying: Boolean,
        deviceContext: SmartResumeDeviceContext? = null,
        allowRegression: Boolean = false,
    ) {
        val s = session ?: return
        val hadEstimate = s.hasPersistedEstimate
        s.estimatedProgress = null
        if (deviceContext?.isStructurallyValid() == true) s.deviceContext = deviceContext
        val position = positionSeconds.coerceAtLeast(0)
        if (durationSeconds != null && durationSeconds > 0) s.durationSeconds = durationSeconds
        if (!s.active) {
            if (!isPlaying && position <= 0) return
            s.active = true
            s.pendingSeekSeconds = null
            s.positionSeconds = position
            persist(s, SmartResumeCheckpointKind.STARTED)
            return
        }
        // Callers allowing regression have already filtered stale renderer samples. Their first
        // accepted confirmation may be beyond the target after a slow poll; retire live seek intent.
        val seekConfirmed = s.pendingSeekSeconds?.let { allowRegression || abs(it - position) <= POSITION_TOLERANCE_SECONDS } == true ||
            (allowRegression && position < s.positionSeconds)
        if (seekConfirmed) {
            s.pendingSeekSeconds = null
            s.positionSeconds = position
            persist(s, SmartResumeCheckpointKind.SEEK_CONFIRMED)
            return
        }
        if (position + POSITION_REGRESSION_TOLERANCE_SECONDS < s.positionSeconds) return
        s.positionSeconds = maxOf(s.positionSeconds, position)
        val crossedThreshold = s.lastPersistedPosition < ResumePolicy.MIN_RESUME_SECONDS && s.positionSeconds >= ResumePolicy.MIN_RESUME_SECONDS
        if (hadEstimate || crossedThreshold || clock() - s.lastWriteMillis >= writeIntervalMillis) persist(s, SmartResumeCheckpointKind.PROGRESS)
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
                    s.estimatedProgress.takeUnless { kind == SmartResumeCheckpointKind.COMPLETED },
                ),
            )
        }.getOrNull() ?: return
        s.lastWriteMillis = now
        s.lastPersistedPosition = persisted.confirmedPositionSeconds
        s.hasPersistedEstimate = persisted.estimatedProgress != null
    }

    companion object {
        // Bounds loss after a sudden process death while keeping AtomicFile writes sparse.
        const val DEFAULT_WRITE_INTERVAL_MS = 5_000L
        const val POSITION_TOLERANCE_SECONDS = 5L
        private const val POSITION_REGRESSION_TOLERANCE_SECONDS = 2L
    }
}
