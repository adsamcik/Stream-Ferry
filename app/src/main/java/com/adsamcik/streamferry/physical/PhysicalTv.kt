package com.adsamcik.streamferry.physical

import com.adsamcik.streamferry.core.stream.Protocol
import com.adsamcik.streamferry.domain.DiscoveredTarget
import com.adsamcik.streamferry.domain.TargetDiscoveryMetadata
import java.util.Base64
import java.util.Locale

/** A protocol-qualified stable identifier. Network hosts/IP addresses cannot form one. */
data class PhysicalEndpointKey(val protocol: Protocol, val stableId: String) {
    init { require(stableId.isNotBlank()) }
    val storageToken: String get() = "${protocol.name}:${Base64.getUrlEncoder().withoutPadding().encodeToString(stableId.toByteArray())}"

    companion object {
        fun from(target: DiscoveredTarget): PhysicalEndpointKey? = when (target.protocol) {
            Protocol.CAST -> target.discoveryMetadata.castDeviceId.stableId()?.let { PhysicalEndpointKey(Protocol.CAST, it) }
            Protocol.DLNA -> (target.discoveryMetadata.dlnaUdn ?: target.discoveryMetadata.dlnaUsn).stableId()
                ?.let { PhysicalEndpointKey(Protocol.DLNA, it) }
        }

        internal fun fromStorageToken(token: String): PhysicalEndpointKey? = runCatching {
            val separator = token.indexOf(':')
            require(separator > 0)
            val protocol = Protocol.valueOf(token.substring(0, separator))
            val id = String(Base64.getUrlDecoder().decode(token.substring(separator + 1))).stableId() ?: return null
            PhysicalEndpointKey(protocol, id)
        }.getOrNull()
    }
}

enum class PhysicalMatchOutcome { CONFIDENT, AMBIGUOUS, NO_MATCH }

/** Machine-readable explanation for a conservative physical-screen comparison. */
enum class PhysicalMatchReason {
    PERSISTED_STABLE_ID_ASSOCIATION,
    MATCHING_VALIDATED_HOST_AND_MODEL,
    MISSING_STABLE_ID,
    SAME_PROTOCOL,
    NO_SHARED_VALIDATED_HOST,
    SHARED_HOST_WITHOUT_INDEPENDENT_DEVICE_EVIDENCE,
    NAME_ONLY_MATCH,
    CONFLICTING_DEVICE_EVIDENCE,
    SEPARATE_CAST_DONGLE_SIGNATURE,
    ONE_TO_MANY_HIGH_CONFIDENCE_CANDIDATES,
    USER_UNLINKED,
}

data class PhysicalTvMatch(
    val castTarget: DiscoveredTarget,
    val dlnaTarget: DiscoveredTarget,
    val outcome: PhysicalMatchOutcome,
    val reasons: Set<PhysicalMatchReason>,
)

/** Read-only state used by the pure matcher and aggregator. */
interface PhysicalTvStateLookup {
    fun isLinked(first: PhysicalEndpointKey, second: PhysicalEndpointKey): Boolean
    fun isBlocked(first: PhysicalEndpointKey, second: PhysicalEndpointKey): Boolean
    fun linkedPeer(endpoint: PhysicalEndpointKey): PhysicalEndpointKey?
    fun selectionFor(endpoints: Collection<PhysicalEndpointKey>): EndpointSelectionPreference
}

object EmptyPhysicalTvStateLookup : PhysicalTvStateLookup {
    override fun isLinked(first: PhysicalEndpointKey, second: PhysicalEndpointKey) = false
    override fun isBlocked(first: PhysicalEndpointKey, second: PhysicalEndpointKey) = false
    override fun linkedPeer(endpoint: PhysicalEndpointKey): PhysicalEndpointKey? = null
    override fun selectionFor(endpoints: Collection<PhysicalEndpointKey>) = EndpointSelectionPreference()
}

data class EndpointSelectionPreference(
    val lastSuccessfulProtocol: Protocol? = null,
    val preferredProtocol: Protocol? = null,
)

/** A physical screen while retaining the available underlying Cast and DLNA endpoints. */
data class PhysicalTv(
    val id: String,
    val displayName: String,
    val castEndpoint: DiscoveredTarget? = null,
    val dlnaEndpoint: DiscoveredTarget? = null,
    val selectionPreference: EndpointSelectionPreference = EndpointSelectionPreference(),
) {
    init { require(castEndpoint != null || dlnaEndpoint != null) }
    val availableEndpoints: List<DiscoveredTarget> get() = listOfNotNull(castEndpoint, dlnaEndpoint)
    /** Last-successful wins, then explicit preference, then a deterministic Cast → DLNA fallback. */
    fun selectEndpoint(): DiscoveredTarget? = PhysicalTvEndpointSelector.select(availableEndpoints, selectionPreference)
}

object PhysicalTvEndpointSelector {
    fun select(
        endpoints: Collection<DiscoveredTarget>,
        preference: EndpointSelectionPreference = EndpointSelectionPreference(),
    ): DiscoveredTarget? {
        val byProtocol = endpoints.sortedBy { it.id }.associateBy { it.protocol }
        return preference.lastSuccessfulProtocol?.let(byProtocol::get)
            ?: preference.preferredProtocol?.let(byProtocol::get)
            ?: byProtocol[Protocol.CAST]
            ?: byProtocol[Protocol.DLNA]
    }
}

/**
 * Smart Resume may automatically reuse a screen only from an exact persisted stable identity. The
 * display reference is intentionally absent from this API so a room/name match cannot become control
 * authority by accident.
 */
object PhysicalTvResumeMatcher {
    fun findConfident(
        physicalTvs: Collection<PhysicalTv>,
        physicalStableId: String?,
        endpointStorageToken: String?,
    ): PhysicalTv? {
        physicalStableId?.let { id ->
            physicalTvs.singleOrNull { it.id == id }?.let { return it }
        }
        val endpointToken = endpointStorageToken ?: return null
        return physicalTvs.singleOrNull { tv ->
            tv.availableEndpoints.mapNotNull(PhysicalEndpointKey::from)
                .any { it.storageToken == endpointToken }
        }
    }
}

/** Pure, deterministic, deliberately conservative cross-protocol matching policy. */
object PhysicalTvMatcher {
    fun match(
        first: DiscoveredTarget,
        second: DiscoveredTarget,
        state: PhysicalTvStateLookup = EmptyPhysicalTvStateLookup,
    ): PhysicalTvMatch {
        val cast = listOf(first, second).singleOrNull { it.protocol == Protocol.CAST }
        val dlna = listOf(first, second).singleOrNull { it.protocol == Protocol.DLNA }
        if (cast == null || dlna == null) {
            return PhysicalTvMatch(first, second, PhysicalMatchOutcome.NO_MATCH, setOf(PhysicalMatchReason.SAME_PROTOCOL))
        }
        val castKey = PhysicalEndpointKey.from(cast)
        val dlnaKey = PhysicalEndpointKey.from(dlna)
        if (castKey != null && dlnaKey != null && state.isBlocked(castKey, dlnaKey)) {
            return PhysicalTvMatch(cast, dlna, PhysicalMatchOutcome.NO_MATCH, setOf(PhysicalMatchReason.USER_UNLINKED))
        }
        if (castKey != null && dlnaKey != null && state.isLinked(castKey, dlnaKey)) {
            return PhysicalTvMatch(
                cast, dlna, PhysicalMatchOutcome.CONFIDENT,
                setOf(PhysicalMatchReason.PERSISTED_STABLE_ID_ASSOCIATION),
            )
        }

        val hostsMatch = cast.discoveryMetadata.validatedHosts()
            .intersect(dlna.discoveryMetadata.validatedHosts()).isNotEmpty()
        val sameName = normalizedLabel(cast.displayName) != null && normalizedLabel(cast.displayName) == normalizedLabel(dlna.displayName)
        val missingStableId = castKey == null || dlnaKey == null
        if (!hostsMatch) {
            return PhysicalTvMatch(cast, dlna, PhysicalMatchOutcome.NO_MATCH, buildSet {
                add(PhysicalMatchReason.NO_SHARED_VALIDATED_HOST)
                if (missingStableId) add(PhysicalMatchReason.MISSING_STABLE_ID)
                if (sameName) add(PhysicalMatchReason.NAME_ONLY_MATCH)
            })
        }
        if (castLooksLikeSeparateDongle(cast.discoveryMetadata)) {
            return PhysicalTvMatch(cast, dlna, PhysicalMatchOutcome.NO_MATCH, setOf(PhysicalMatchReason.SEPARATE_CAST_DONGLE_SIGNATURE))
        }
        if (sameSpecificModel(cast.discoveryMetadata, dlna.discoveryMetadata)) {
            return PhysicalTvMatch(
                cast, dlna, PhysicalMatchOutcome.CONFIDENT,
                setOf(PhysicalMatchReason.MATCHING_VALIDATED_HOST_AND_MODEL),
            )
        }
        return PhysicalTvMatch(cast, dlna, PhysicalMatchOutcome.AMBIGUOUS, buildSet {
            add(PhysicalMatchReason.SHARED_HOST_WITHOUT_INDEPENDENT_DEVICE_EVIDENCE)
            if (missingStableId) add(PhysicalMatchReason.MISSING_STABLE_ID)
            if (sameName) add(PhysicalMatchReason.NAME_ONLY_MATCH)
            if (hasConflictingSpecificModel(cast.discoveryMetadata, dlna.discoveryMetadata)) {
                add(PhysicalMatchReason.CONFLICTING_DEVICE_EVIDENCE)
            }
        })
    }

    private fun castLooksLikeSeparateDongle(metadata: TargetDiscoveryMetadata): Boolean {
        val model = normalizedLabel(metadata.modelName) ?: return false
        return model.contains("chromecast") || model.contains("google tv") || model.contains("google-tv")
    }

    private fun sameSpecificModel(first: TargetDiscoveryMetadata, second: TargetDiscoveryMetadata): Boolean =
        specificModel(first.modelName)?.let { it == specificModel(second.modelName) } == true

    private fun hasConflictingSpecificModel(first: TargetDiscoveryMetadata, second: TargetDiscoveryMetadata): Boolean =
        specificModel(first.modelName)?.let { it != specificModel(second.modelName) } == true
}

/** Aggregates only mutual one-to-one confident pairs; uncertain endpoints stay separate. */
object PhysicalTvAggregator {
    data class Result(val physicalTvs: List<PhysicalTv>, val matches: List<PhysicalTvMatch>)

    fun aggregate(
        targets: Collection<DiscoveredTarget>,
        state: PhysicalTvStateLookup = EmptyPhysicalTvStateLookup,
    ): Result {
        val castTargets = targets.filter { it.protocol == Protocol.CAST }.sortedWith(targetOrder)
        val dlnaTargets = targets.filter { it.protocol == Protocol.DLNA }.sortedWith(targetOrder)
        val rawMatches = castTargets.flatMap { cast -> dlnaTargets.map { dlna -> PhysicalTvMatcher.match(cast, dlna, state) } }
        val confident = rawMatches.filter { it.outcome == PhysicalMatchOutcome.CONFIDENT }
        val byCast = confident.groupBy { it.castTarget.id }
        val byDlna = confident.groupBy { it.dlnaTarget.id }
        val mutual = confident.filter { match ->
            byCast.getValue(match.castTarget.id).size == 1 && byDlna.getValue(match.dlnaTarget.id).size == 1
        }
        val mutualPairs = mutual.map { it.castTarget.id to it.dlnaTarget.id }.toSet()
        val matches = rawMatches.map { match ->
            if (match.outcome == PhysicalMatchOutcome.CONFIDENT && (match.castTarget.id to match.dlnaTarget.id) !in mutualPairs) {
                match.copy(
                    outcome = PhysicalMatchOutcome.AMBIGUOUS,
                    reasons = match.reasons + PhysicalMatchReason.ONE_TO_MANY_HIGH_CONFIDENCE_CANDIDATES,
                )
            } else match
        }
        val pairedCast = mutual.mapTo(mutableSetOf()) { it.castTarget.id }
        val pairedDlna = mutual.mapTo(mutableSetOf()) { it.dlnaTarget.id }
        val physicalTvs = buildList {
            mutual.forEach { add(physicalTv(it.castTarget, it.dlnaTarget, state)) }
            castTargets.filterNot { it.id in pairedCast }.forEach { add(physicalTv(it, null, state)) }
            dlnaTargets.filterNot { it.id in pairedDlna }.forEach { add(physicalTv(null, it, state)) }
        }.sortedWith(compareBy<PhysicalTv> { it.displayName.lowercase(Locale.ROOT) }.thenBy { it.id })
        return Result(physicalTvs, matches)
    }

    private fun physicalTv(cast: DiscoveredTarget?, dlna: DiscoveredTarget?, state: PhysicalTvStateLookup): PhysicalTv {
        val endpoints = listOfNotNull(cast, dlna)
        val keys = endpoints.mapNotNull(PhysicalEndpointKey::from)
        val physicalKeys = (keys + keys.mapNotNull(state::linkedPeer)).distinct()
        val rawId = physicalKeys.map { it.storageToken }.sorted().takeIf { it.isNotEmpty() }?.joinToString("|")
            ?: endpoints.joinToString("|") { "${it.protocol.name}:${it.id}" }
        return PhysicalTv(
            id = "physical:$rawId",
            displayName = cast?.displayName ?: requireNotNull(dlna).displayName,
            castEndpoint = cast,
            dlnaEndpoint = dlna,
            selectionPreference = if (physicalKeys.isEmpty()) EndpointSelectionPreference() else state.selectionFor(physicalKeys),
        )
    }

    private val targetOrder = compareBy<DiscoveredTarget> { it.id }.thenBy { it.displayName }
}

private fun TargetDiscoveryMetadata.validatedHosts(): Set<String> =
    listOfNotNull(validatedSourceHost, validatedDescriptionHost).mapNotNull(::normalizedHost).toSet()

private fun String?.stableId(): String? = normalizedLabel(this)?.take(256)
private fun normalizedHost(value: String?): String? = normalizedLabel(value)
private fun normalizedLabel(value: String?): String? = value?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }
private fun specificModel(value: String?): String? {
    val model = normalizedLabel(value)?.replace(Regex("[^a-z0-9]+"), "") ?: return null
    return model.takeIf { it.length >= 5 && it !in setOf("smarttv", "androidtv", "mediarenderer", "unknown") }
}
