package com.adsamcik.streamferry.playback

import com.adsamcik.streamferry.domain.PlaybackFailureKind

/** Coarse, UI-safe lifecycle of the one renderer playback session. */
enum class PlaybackPhase {
    CONNECTING, PREPARING, LOADING, WAITING_FOR_PLAYBACK, PLAYING, PAUSED, BUFFERING,
    RECONNECTING, CHANGING_STREAM, CHANGING_PROTOCOL, STOPPED, COMPLETED, FAILED,
}

/** The point in the pipeline where a failure was observed. */
enum class PlaybackFailureStage {
    ENDPOINT_CONNECTION,
    STREAM_RESOLUTION,
    RENDERER_LOAD,
    FIRST_FRAME,
    ESTABLISHED_PLAYBACK,
    SEEK_OR_RELOAD,
    ENDPOINT_DISCONNECT,
    WATCHDOG,
    PROXY,
    UNKNOWN,
}

/** Redaction-safe failure classification used by recovery policy, never a raw renderer message. */
enum class PlaybackFailureCause {
    TRANSIENT_NETWORK,
    ENDPOINT_UNAVAILABLE,
    FORMAT_OR_CODEC,
    AUDIO_OR_SUBTITLE,
    THROUGHPUT,
    SERVER_TRANSCODE,
    ON_DEVICE_TRANSCODE,
    UPSTREAM_OR_SERVER_UNAVAILABLE,
    UNKNOWN,
}

fun PlaybackFailureKind.toRecoveryCause(): PlaybackFailureCause = when (this) {
    PlaybackFailureKind.NETWORK -> PlaybackFailureCause.TRANSIENT_NETWORK
    PlaybackFailureKind.FORMAT -> PlaybackFailureCause.FORMAT_OR_CODEC
    PlaybackFailureKind.UNKNOWN -> PlaybackFailureCause.UNKNOWN
}

enum class PlaybackAttemptSourceKind { ONLINE, LOCAL, ON_DEVICE_TRANSCODE }
enum class PlaybackAttemptRoute { DIRECT, SERVER_TRANSCODE, ON_DEVICE_TRANSCODE }

/** The bounded classes of automatic work. Explicit user changes do not consume this budget. */
enum class RecoveryAttemptKind { SAME_STREAM_NETWORK, FORMAT_COMPATIBILITY, LOWER_RESOLUTION, ALTERNATE_PROTOCOL }

/** A source/phone-gateway preparation failure that changing the TV protocol cannot repair. */
class PlaybackPreparationException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * A concise attempt record. It intentionally has no title, media id, URL, token, IP, or raw exception.
 * [endpoint] is always a label created by [redactPlaybackEndpoint], never a connectable address.
 */
data class PlaybackAttemptDescriptor(
    val generation: Long,
    val endpoint: String? = null,
    val protocol: String? = null,
    val sourceKind: PlaybackAttemptSourceKind? = null,
    val route: PlaybackAttemptRoute? = null,
    val codec: String? = null,
    val container: String? = null,
    val capabilitySummary: String? = null,
    val startPositionSeconds: Long? = null,
    val audioStreamIndex: Int? = null,
    val subtitleStreamIndex: Int? = null,
    val reason: String? = null,
    val failureStage: PlaybackFailureStage? = null,
    val failureCause: PlaybackFailureCause? = null,
    val automaticRecovery: RecoveryAttemptKind? = null,
)

/** Never expose a host, IP, path, query, or credentials through playback status/history. */
fun redactPlaybackEndpoint(value: String?): String? = when {
    value.isNullOrBlank() -> null
    value.equals("local-file", ignoreCase = true) -> "local"
    else -> "lan://…"
}

data class RecoveryBudget(
    /** Includes all automatically scheduled recovery attempts in this session. */
    val maxAutomaticAttempts: Int = 6,
    val maxSameStreamNetworkRetries: Int = 1,
    /** Format, resolution, and alternate-protocol variants share a conservative cap. */
    val maxCompatibilityOrQualityVariants: Int = 3,
)

data class RecoveryBudgetUsage(
    val automaticAttempts: Int = 0,
    val sameStreamNetworkRetries: Int = 0,
    val compatibilityOrQualityVariants: Int = 0,
)

data class RecoveryBudgetStatus(
    val automaticAttemptsRemaining: Int,
    val sameStreamNetworkRetriesRemaining: Int,
    val compatibilityOrQualityVariantsRemaining: Int,
)

fun RecoveryBudget.status(usage: RecoveryBudgetUsage): RecoveryBudgetStatus = RecoveryBudgetStatus(
    automaticAttemptsRemaining = (maxAutomaticAttempts - usage.automaticAttempts).coerceAtLeast(0),
    sameStreamNetworkRetriesRemaining = (maxSameStreamNetworkRetries - usage.sameStreamNetworkRetries).coerceAtLeast(0),
    compatibilityOrQualityVariantsRemaining =
        (maxCompatibilityOrQualityVariants - usage.compatibilityOrQualityVariants).coerceAtLeast(0),
)

fun RecoveryBudget.canSchedule(usage: RecoveryBudgetUsage, kind: RecoveryAttemptKind): Boolean {
    if (usage.automaticAttempts >= maxAutomaticAttempts) return false
    return when (kind) {
        RecoveryAttemptKind.SAME_STREAM_NETWORK -> usage.sameStreamNetworkRetries < maxSameStreamNetworkRetries
        RecoveryAttemptKind.FORMAT_COMPATIBILITY,
        RecoveryAttemptKind.LOWER_RESOLUTION,
        RecoveryAttemptKind.ALTERNATE_PROTOCOL ->
            usage.compatibilityOrQualityVariants < maxCompatibilityOrQualityVariants
    }
}

private fun RecoveryBudgetUsage.consume(kind: RecoveryAttemptKind): RecoveryBudgetUsage = copy(
    automaticAttempts = automaticAttempts + 1,
    sameStreamNetworkRetries = sameStreamNetworkRetries + if (kind == RecoveryAttemptKind.SAME_STREAM_NETWORK) 1 else 0,
    compatibilityOrQualityVariants = compatibilityOrQualityVariants + if (kind != RecoveryAttemptKind.SAME_STREAM_NETWORK) 1 else 0,
)

/**
 * Input for the coordinator/UI-owned alternate-protocol attempt. A renderer disconnect or a format
 * rejection can warrant trying a different endpoint after same-endpoint work is exhausted; upstream
 * Source/server failures cannot, because changing the receiver protocol would not repair the source.
 */
data class ProtocolSwitchInput(
    val isLocalSession: Boolean,
    val isOnlineSession: Boolean,
    val hasAlternateProtocol: Boolean,
    val hasAlreadySwitchedProtocol: Boolean,
    val sameEndpointRecoveryExhausted: Boolean,
    val failureStage: PlaybackFailureStage,
    val failureCause: PlaybackFailureCause,
    val budget: RecoveryBudget,
    val usage: RecoveryBudgetUsage,
)

fun isAlternateProtocolEligible(input: ProtocolSwitchInput): Boolean =
    (input.isLocalSession || input.isOnlineSession) && input.hasAlternateProtocol &&
        !input.hasAlreadySwitchedProtocol && input.sameEndpointRecoveryExhausted &&
        input.failureCause != PlaybackFailureCause.UPSTREAM_OR_SERVER_UNAVAILABLE &&
        input.budget.canSchedule(input.usage, RecoveryAttemptKind.ALTERNATE_PROTOCOL)

/** A reserved, redacted hand-off for exactly one coordinator-owned protocol switch. */
class PlaybackRecoveryContinuation internal constructor(
    internal val session: PlaybackRecoverySession,
    internal val reservedKind: RecoveryAttemptKind,
)
/**
 * Immutable, pure session ledger. The engine owns side effects; this type owns generations, concise
 * redacted history, and recovery-budget admission so duplicate/stale callbacks cannot start more work.
 */
data class PlaybackRecoverySession(
    val phase: PlaybackPhase = PlaybackPhase.STOPPED,
    val generation: Long = 0,
    val attempts: List<PlaybackAttemptDescriptor> = emptyList(),
    val budget: RecoveryBudget = RecoveryBudget(),
    val usage: RecoveryBudgetUsage = RecoveryBudgetUsage(),
    /** Claimed atomically at reservation time, even if cancellation happens before renderer load. */
    val alternateProtocolReserved: Boolean = false,
) {
    val budgetStatus: RecoveryBudgetStatus get() = budget.status(usage)

    /** Starts a fresh user session while preserving globally monotonic generation numbers. */
    fun startSession(): PlaybackRecoverySession = copy(
        phase = PlaybackPhase.CONNECTING,
        generation = generation + 1,
        attempts = emptyList(),
        usage = RecoveryBudgetUsage(),
        alternateProtocolReserved = false,
    )
    /** Continue a reserved automatic recovery across the engine stop/start boundary. */
    fun continueFrom(continuation: PlaybackRecoveryContinuation): PlaybackRecoverySession = copy(
        phase = PlaybackPhase.CONNECTING,
        generation = maxOf(generation, continuation.session.generation) + 1,
        attempts = continuation.session.attempts,
        budget = continuation.session.budget,
        usage = continuation.session.usage,
        alternateProtocolReserved = continuation.session.alternateProtocolReserved,
    )

    /** Records every renderer load attempt. The ring is deliberately short and already redacted. */
    fun beginAttempt(template: PlaybackAttemptDescriptor): PlaybackRecoverySession {
        val nextGeneration = generation + 1
        val attempt = template.copy(generation = nextGeneration)
        // A controller can issue duplicate load callbacks for the same URI. Keep the generation monotonic
        // (so stale callbacks are still rejected) but do not fill diagnostics with the same logical load.
        val equivalentToLatest = attempts.lastOrNull()?.let { previous ->
            previous.copy(generation = 0, failureStage = null, failureCause = null) ==
                attempt.copy(generation = 0, failureStage = null, failureCause = null)
        } == true
        return copy(
            phase = PlaybackPhase.WAITING_FOR_PLAYBACK,
            generation = nextGeneration,
            attempts = if (equivalentToLatest) attempts.dropLast(1) + attempt
                else (attempts + attempt).takeLast(MAX_ATTEMPT_HISTORY),
        )
    }

    /** Atomically reserve one automatic recovery before its coroutine is launched. */
    fun reserveRecovery(kind: RecoveryAttemptKind, phase: PlaybackPhase): PlaybackRecoverySession? {
        if (!budget.canSchedule(usage, kind) || this.phase == PlaybackPhase.STOPPED) return null
        return copy(phase = phase, usage = usage.consume(kind))
    }
    /** Reserve the single alternate-protocol continuation before the coordinator launches it. */
    fun reserveAlternateProtocol(input: ProtocolSwitchInput): PlaybackRecoveryContinuation? {
        if (alternateProtocolReserved) return null
        if (!isAlternateProtocolEligible(input)) return null
        val reserved = reserveRecovery(RecoveryAttemptKind.ALTERNATE_PROTOCOL, PlaybackPhase.CHANGING_PROTOCOL)
            ?: return null
        return PlaybackRecoveryContinuation(
            reserved.copy(alternateProtocolReserved = true),
            RecoveryAttemptKind.ALTERNATE_PROTOCOL,
        )
    }


    /** Attach a classified failure to only the latest redacted attempt. */
    fun recordFailure(stage: PlaybackFailureStage, cause: PlaybackFailureCause): PlaybackRecoverySession = copy(
        attempts = if (attempts.isEmpty()) attempts else attempts.dropLast(1) +
            attempts.last().copy(failureStage = stage, failureCause = cause),
    )

    fun transition(next: PlaybackPhase): PlaybackRecoverySession = copy(phase = next)

    /** A stopped session invalidates queued watchdog/recovery work as well as renderer callbacks. */
    fun stop(): PlaybackRecoverySession = copy(phase = PlaybackPhase.STOPPED, generation = generation + 1)

    fun fail(): PlaybackRecoverySession = copy(phase = PlaybackPhase.FAILED)

    fun acceptsEvent(eventGeneration: Long): Boolean =
        phase != PlaybackPhase.STOPPED && eventGeneration == generation

    companion object { const val MAX_ATTEMPT_HISTORY = 6 }
}
