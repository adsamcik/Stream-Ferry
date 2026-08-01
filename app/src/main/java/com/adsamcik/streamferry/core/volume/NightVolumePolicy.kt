package com.adsamcik.streamferry.core.volume

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** Optional, reduction-only night-volume behavior. It has no scheduler or Android dependency. */
sealed interface NightVolumePolicy {
    data object Off : NightVolumePolicy

    data class Gradual(
        val start: LocalTime,
        val end: LocalTime,
        val targetVolume: Float,
    ) : NightVolumePolicy {
        init {
            require(start != end) { "A gradual night-volume window needs distinct start and end times." }
            require(targetVolume in 0f..1f) { "targetVolume must be in [0, 1]." }
        }
    }

    data class Hard(
        val time: LocalTime,
        val targetVolume: Float,
    ) : NightVolumePolicy {
        init { require(targetVolume in 0f..1f) { "targetVolume must be in [0, 1]." } }
    }
}

data class NightVolumeInput(
    val now: Instant,
    val zoneId: ZoneId,
    val activePlayback: Boolean,
    val volumeSupported: Boolean,
    val effectiveVolume: Float,
    /** True only for a user-originated volume action; it disables automation until the next session. */
    val manualVolumeAdjusted: Boolean = false,
)

/** Persist this only for the lifetime of one active playback session, never as a user preference. */
data class NightVolumeSession(
    val startedAt: Instant? = null,
    val startingVolume: Float? = null,
    val lastAutomaticCommandVolume: Float? = null,
    val lastAutomaticCommandAt: Instant? = null,
    val hardReductionApplied: Boolean = false,
    val automaticChangesSuspended: Boolean = false,
)

data class NightVolumeDecision(
    val session: NightVolumeSession,
    /** A normalized [0,1] command, or null when no safe command should be sent. */
    val commandVolume: Float? = null,
)

/**
 * Pure state machine for the playback owner to poll while it is already active. It deliberately does
 * not know protocol/device identity, so a renderer reconnect cannot reset duplicate suppression. Hard
 * mode is session-anchored: a session first observed after its configured time waits for the next local
 * occurrence, while a session already active at that time receives one reduction.
 */
object NightVolumeScheduler {
    const val MIN_COMMAND_DELTA = 0.03f
    val MIN_COMMAND_INTERVAL: Duration = Duration.ofMinutes(5)

    fun evaluate(
        policy: NightVolumePolicy,
        input: NightVolumeInput,
        previous: NightVolumeSession = NightVolumeSession(),
    ): NightVolumeDecision {
        if (input.manualVolumeAdjusted) {
            return NightVolumeDecision(previous.copy(automaticChangesSuspended = true))
        }
        if (!input.activePlayback || !input.volumeSupported) return NightVolumeDecision(previous)

        val effective = input.effectiveVolume.coerceIn(0f, 1f)
        val session = previous.copy(
            startedAt = previous.startedAt ?: input.now,
            startingVolume = previous.startingVolume ?: effective,
        )
        if (session.automaticChangesSuspended || policy is NightVolumePolicy.Off) return NightVolumeDecision(session)

        return when (policy) {
            is NightVolumePolicy.Off -> NightVolumeDecision(session)
            is NightVolumePolicy.Hard -> evaluateHard(policy, input, effective, session)
            is NightVolumePolicy.Gradual -> evaluateGradual(policy, input, effective, session)
        }
    }

    private fun evaluateHard(
        policy: NightVolumePolicy.Hard,
        input: NightVolumeInput,
        effective: Float,
        session: NightVolumeSession,
    ): NightVolumeDecision {
        val sessionStart = requireNotNull(session.startedAt)
        val hardAt = nextOccurrenceAtOrAfter(sessionStart, input.zoneId, policy.time)
        if (session.hardReductionApplied || input.now < hardAt) return NightVolumeDecision(session)
        val command = sparseReduction(policy.targetVolume, effective, input.now, session)
        return NightVolumeDecision(
            session.copy(
                hardReductionApplied = true,
                lastAutomaticCommandVolume = command ?: session.lastAutomaticCommandVolume,
                lastAutomaticCommandAt = if (command != null) input.now else session.lastAutomaticCommandAt,
            ),
            command,
        )
    }

    private fun evaluateGradual(
        policy: NightVolumePolicy.Gradual,
        input: NightVolumeInput,
        effective: Float,
        session: NightVolumeSession,
    ): NightVolumeDecision {
        val window = activeWindow(input.now, input.zoneId, policy.start, policy.end) ?: return NightVolumeDecision(session)
        val elapsed = Duration.between(window.start.toInstant(), input.now).toMillis().coerceAtLeast(0)
        val total = Duration.between(window.start.toInstant(), window.end.toInstant()).toMillis().coerceAtLeast(1)
        val progress = (elapsed.toDouble() / total.toDouble()).coerceIn(0.0, 1.0).toFloat()
        val start = session.startingVolume ?: effective
        val desired = start + (policy.targetVolume - start) * progress
        val command = sparseReduction(desired, effective, input.now, session)
        return NightVolumeDecision(
            session.copy(
                lastAutomaticCommandVolume = command ?: session.lastAutomaticCommandVolume,
                lastAutomaticCommandAt = if (command != null) input.now else session.lastAutomaticCommandAt,
            ),
            command,
        )
    }

    /** Never issues an increase, a duplicate, or a command too close to the preceding automatic one. */
    private fun sparseReduction(
        desired: Float,
        effective: Float,
        now: Instant,
        session: NightVolumeSession,
    ): Float? {
        val candidate = minOf(desired, effective).coerceIn(0f, 1f)
        if (candidate >= effective - MIN_COMMAND_DELTA) return null
        val priorVolume = session.lastAutomaticCommandVolume
        if (priorVolume != null && candidate >= priorVolume - MIN_COMMAND_DELTA) return null
        val priorTime = session.lastAutomaticCommandAt
        if (priorTime != null && Duration.between(priorTime, now) < MIN_COMMAND_INTERVAL) return null
        return candidate
    }

    private fun nextOccurrenceAtOrAfter(sessionStart: Instant, zone: ZoneId, time: LocalTime): Instant {
        val localStart = sessionStart.atZone(zone)
        val date = if (localStart.toLocalTime() <= time) localStart.toLocalDate() else localStart.toLocalDate().plusDays(1)
        return date.atTime(time).atZone(zone).toInstant()
    }
    private data class NightWindow(val start: ZonedDateTime, val end: ZonedDateTime)

    /**
     * Resolves local times through [ZonedDateTime] (gap shifts forward; overlap chooses the earlier
     * offset per java.time), then interpolates using instants. That makes midnight and DST behavior stable.
     */
    private fun activeWindow(now: Instant, zone: ZoneId, start: LocalTime, end: LocalTime): NightWindow? {
        val localNow = now.atZone(zone)
        val today = windowFor(localNow.toLocalDate(), zone, start, end)
        if (now >= today.start.toInstant() && now < today.end.toInstant()) return today
        val yesterday = windowFor(localNow.toLocalDate().minusDays(1), zone, start, end)
        return yesterday.takeIf { now >= it.start.toInstant() && now < it.end.toInstant() }
    }

    private fun windowFor(date: LocalDate, zone: ZoneId, start: LocalTime, end: LocalTime): NightWindow {
        val startAt = date.atTime(start).atZone(zone)
        val endDate = if (end > start) date else date.plusDays(1)
        return NightWindow(startAt, endDate.atTime(end).atZone(zone))
    }
}
