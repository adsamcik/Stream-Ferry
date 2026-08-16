package com.adsamcik.streamferry.physical

import android.content.Context
import com.adsamcik.streamferry.core.stream.Protocol

/** App-private stable-ID links, user unlink blocks, and per-screen endpoint preference. Never IPs. */
interface PhysicalTvAssociationStore : PhysicalTvStateLookup {
    /** Links a Cast stable ID to a DLNA stable ID, replacing either endpoint's older link. */
    fun link(first: PhysicalEndpointKey, second: PhysicalEndpointKey)
    /** Removes this exact link and blocks its automatic re-merge until the user explicitly links it again. */
    fun unlink(first: PhysicalEndpointKey, second: PhysicalEndpointKey)
    fun recordLastSuccessful(endpoints: Collection<PhysicalEndpointKey>, protocol: Protocol)
    fun setPreferredProtocol(endpoints: Collection<PhysicalEndpointKey>, protocol: Protocol?)
    /** Clears all app-private physical-TV links, blocks, and endpoint choices. */
    fun clear()
}

class InMemoryPhysicalTvAssociationStore : PhysicalTvAssociationStore {
    private val links = linkedSetOf<EndpointPair>()
    private val blocked = linkedSetOf<EndpointPair>()
    private val lastSuccessful = mutableMapOf<String, Protocol>()
    private val preferred = mutableMapOf<String, Protocol>()

    override fun isLinked(first: PhysicalEndpointKey, second: PhysicalEndpointKey): Boolean = synchronized(this) { EndpointPair.of(first, second) in links }
    override fun isBlocked(first: PhysicalEndpointKey, second: PhysicalEndpointKey): Boolean = synchronized(this) { EndpointPair.of(first, second) in blocked }
    override fun linkedPeer(endpoint: PhysicalEndpointKey): PhysicalEndpointKey? = synchronized(this) { links.firstOrNull { it.contains(endpoint) }?.other(endpoint) }

    override fun link(first: PhysicalEndpointKey, second: PhysicalEndpointKey) = synchronized(this) {
        val pair = EndpointPair.of(first, second)
        links.removeAll { it.contains(first) || it.contains(second) }
        blocked.remove(pair)
        clearPreferencesContaining(first)
        clearPreferencesContaining(second)
        links += pair
    }

    override fun unlink(first: PhysicalEndpointKey, second: PhysicalEndpointKey) = synchronized(this) {
        val pair = EndpointPair.of(first, second)
        links.remove(pair)
        blocked += pair
        clearPreferencesContaining(first)
        clearPreferencesContaining(second)
    }

    override fun selectionFor(endpoints: Collection<PhysicalEndpointKey>): EndpointSelectionPreference = synchronized(this) {
        val scope = associationScope(endpoints, links)
        EndpointSelectionPreference(lastSuccessful[scope], preferred[scope])
    }
    override fun recordLastSuccessful(endpoints: Collection<PhysicalEndpointKey>, protocol: Protocol) = synchronized(this) { lastSuccessful[associationScope(endpoints, links)] = protocol }
    override fun setPreferredProtocol(endpoints: Collection<PhysicalEndpointKey>, protocol: Protocol?) = synchronized(this) {
        val scope = associationScope(endpoints, links)
        if (protocol == null) preferred.remove(scope) else preferred[scope] = protocol
        Unit
    }
    override fun clear() = synchronized(this) { links.clear(); blocked.clear(); lastSuccessful.clear(); preferred.clear() }

    private fun clearPreferencesContaining(key: PhysicalEndpointKey) {
        val token = key.storageToken
        lastSuccessful.keys.removeAll { it.split('|').contains(token) }
        preferred.keys.removeAll { it.split('|').contains(token) }
    }
}

/** SharedPreferences implementation with independently bounded, replace-only string-set entries. */
class PersistentPhysicalTvAssociationStore(context: Context) : PhysicalTvAssociationStore {
    private val prefs = context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    override fun isLinked(first: PhysicalEndpointKey, second: PhysicalEndpointKey): Boolean = synchronized(this) { EndpointPair.of(first, second) in links() }
    override fun isBlocked(first: PhysicalEndpointKey, second: PhysicalEndpointKey): Boolean = synchronized(this) { EndpointPair.of(first, second) in blocked() }
    override fun linkedPeer(endpoint: PhysicalEndpointKey): PhysicalEndpointKey? = synchronized(this) { links().firstOrNull { it.contains(endpoint) }?.other(endpoint) }

    override fun link(first: PhysicalEndpointKey, second: PhysicalEndpointKey) = synchronized(this) {
        val pair = EndpointPair.of(first, second)
        val updated = links().filterNot { it.contains(first) || it.contains(second) }.toMutableSet()
        updated += pair
        writeLinks(updated)
        writeBlocked(blocked().filterNot { it == pair }.toSet())
        clearPreferencesContaining(first)
        clearPreferencesContaining(second)
    }

    override fun unlink(first: PhysicalEndpointKey, second: PhysicalEndpointKey) = synchronized(this) {
        val pair = EndpointPair.of(first, second)
        writeLinks(links().filterNot { it == pair }.toSet())
        writeBlocked(blocked() + pair)
        clearPreferencesContaining(first)
        clearPreferencesContaining(second)
    }

    override fun selectionFor(endpoints: Collection<PhysicalEndpointKey>): EndpointSelectionPreference = synchronized(this) {
        val scope = associationScope(endpoints, links())
        EndpointSelectionPreference(readProtocol(KEY_LAST_SUCCESSFUL, scope), readProtocol(KEY_PREFERRED, scope))
    }
    override fun recordLastSuccessful(endpoints: Collection<PhysicalEndpointKey>, protocol: Protocol) = synchronized(this) { writeProtocol(KEY_LAST_SUCCESSFUL, associationScope(endpoints, links()), protocol) }
    override fun setPreferredProtocol(endpoints: Collection<PhysicalEndpointKey>, protocol: Protocol?) = synchronized(this) {
        val scope = associationScope(endpoints, links())
        if (protocol == null) removeProtocol(KEY_PREFERRED, scope) else writeProtocol(KEY_PREFERRED, scope, protocol)
    }
    override fun clear() = synchronized(this) { prefs.edit().clear().apply() }

    private fun links(): Set<EndpointPair> = readPairs(KEY_LINKS)
    private fun blocked(): Set<EndpointPair> = readPairs(KEY_BLOCKED)
    private fun readPairs(key: String): Set<EndpointPair> = prefs.getStringSet(key, emptySet()).orEmpty().mapNotNull(EndpointPair::fromStorage).toSet()
    private fun writeLinks(value: Set<EndpointPair>) = writePairs(KEY_LINKS, value)
    private fun writeBlocked(value: Set<EndpointPair>) = writePairs(KEY_BLOCKED, value)
    private fun writePairs(key: String, value: Set<EndpointPair>) {
        prefs.edit().putStringSet(key, value.map { it.storageValue }.sorted().takeLast(MAX_ENTRIES).toSet()).apply()
    }

    private fun readProtocol(key: String, scope: String): Protocol? = prefs.getStringSet(key, emptySet()).orEmpty()
        .firstOrNull { it.substringBefore('#') == scope }?.substringAfter('#', "")
        ?.let { runCatching { Protocol.valueOf(it) }.getOrNull() }
    private fun writeProtocol(key: String, scope: String, protocol: Protocol) {
        val updated = prefs.getStringSet(key, emptySet()).orEmpty().filterNot { it.substringBefore('#') == scope }.toMutableSet()
        updated += "$scope#${protocol.name}"
        prefs.edit().putStringSet(key, updated.sorted().takeLast(MAX_ENTRIES).toSet()).apply()
    }
    private fun removeProtocol(key: String, scope: String) {
        prefs.edit().putStringSet(key, prefs.getStringSet(key, emptySet()).orEmpty().filterNot { it.substringBefore('#') == scope }.toSet()).apply()
    }
    private fun clearPreferencesContaining(endpoint: PhysicalEndpointKey) {
        val token = endpoint.storageToken
        listOf(KEY_LAST_SUCCESSFUL, KEY_PREFERRED).forEach { key ->
            val updated = prefs.getStringSet(key, emptySet()).orEmpty().filterNot { it.substringBefore('#').split('|').contains(token) }.toSet()
            prefs.edit().putStringSet(key, updated).apply()
        }
    }

    private companion object {
        const val FILE_NAME = "stream_ferry_physical_tvs"
        const val KEY_LINKS = "stable_endpoint_links_v1"
        const val KEY_BLOCKED = "unlinked_stable_endpoint_pairs_v1"
        const val KEY_LAST_SUCCESSFUL = "last_successful_protocol_v1"
        const val KEY_PREFERRED = "preferred_protocol_v1"
        const val MAX_ENTRIES = 64
    }
}

private data class EndpointPair private constructor(val first: PhysicalEndpointKey, val second: PhysicalEndpointKey) {
    init { require(first.protocol != second.protocol) { "Physical endpoint links must cross protocols." } }
    val storageValue: String get() = "${first.storageToken}|${second.storageToken}"
    fun contains(key: PhysicalEndpointKey): Boolean = first == key || second == key
    fun other(key: PhysicalEndpointKey): PhysicalEndpointKey? = when (key) { first -> second; second -> first; else -> null }
    companion object {
        fun of(first: PhysicalEndpointKey, second: PhysicalEndpointKey): EndpointPair = listOf(first, second).sortedBy { it.storageToken }.let { EndpointPair(it[0], it[1]) }
        fun fromStorage(value: String): EndpointPair? {
            val parts = value.split('|')
            if (parts.size != 2) return null
            val first = PhysicalEndpointKey.fromStorageToken(parts[0]) ?: return null
            val second = PhysicalEndpointKey.fromStorageToken(parts[1]) ?: return null
            return runCatching { of(first, second) }.getOrNull()
        }
    }
}

private fun associationScope(endpoints: Collection<PhysicalEndpointKey>, links: Collection<EndpointPair>): String {
    val distinct = endpoints.distinct().sortedBy { it.storageToken }
    require(distinct.isNotEmpty()) { "A physical TV preference needs at least one stable endpoint ID." }
    return if (distinct.size == 2 && EndpointPair.of(distinct[0], distinct[1]) in links) distinct.joinToString("|") { it.storageToken }
    else distinct.first().storageToken // Unlinked endpoints deliberately never share protocol choices.
}