package com.videobridge.core.session

import com.videobridge.core.redaction.LogRedactor
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * A single active proxy session. The [upstreamUrl] (the real Jellyfin stream URL, which may carry
 * an access token) is held ONLY here in memory and is NEVER serialised to the TV, logs, or disk.
 *
 * Lifetime is a *sliding idle window* ([idleTtlMillis]) bounded by an *absolute ceiling*
 * ([expiresAtMillis]). Each access during active playback renews the effective expiry to
 * `now + idleTtl`, but never beyond the ceiling. This keeps an abandoned, token-bearing session
 * short-lived (it dies one idle window after the last request) while letting a long, continuously
 * playing session survive past a single fixed TTL — hardening and reliability at once.
 */
data class ProxySession(
    val id: String,
    /** Real Jellyfin upstream URL incl. auth — secret. Never exposed to the TV or logs. */
    val upstreamUrl: String,
    /** Authorization header value to send upstream (Jellyfin token) — secret. */
    val upstreamAuthHeader: String?,
    val contentType: String,
    /** Jellyfin PlaySessionId, used for reporting/cleanup. Secret w.r.t. logs. */
    val playSessionId: String?,
    val createdAtMillis: Long,
    /** Absolute hard ceiling: the session can never live past this, even under continuous use. */
    val expiresAtMillis: Long,
    /** Sliding idle window: a session with no access for this long expires, even before the ceiling. */
    val idleTtlMillis: Long = SessionRegistry.DEFAULT_IDLE_TTL_MILLIS,
    /** Whether this session serves an HLS playlist that must be rewritten before reaching the TV. */
    val isHls: Boolean = false,
    /**
     * Total upstream entity length in bytes when known (a direct-play static file), else null. Lets the
     * proxy advertise `Content-Length` + a proper `Content-Range` so the renderer can byte-range **seek**.
     * Null for a live transcode (HLS or progressive), whose length is unknown and which is seeked
     * server-side (re-resolved at the target position) instead.
     */
    val totalLength: Long? = null,
    /** When set, the proxy serves this app-private local file (offline download) instead of upstream. */
    val localFilePath: String? = null,
) {
    // Mutable runtime liveness; not part of value identity. Starts one idle window after creation,
    // never past the absolute ceiling.
    private val slidingExpiryMillis =
        AtomicLong(minOf(createdAtMillis + idleTtlMillis, expiresAtMillis))

    /** Current effective expiry: the sliding idle deadline, clamped to the absolute [expiresAtMillis]. */
    val effectiveExpiresAtMillis: Long get() = slidingExpiryMillis.get()

    fun isExpired(nowMillis: Long): Boolean = nowMillis >= slidingExpiryMillis.get()

    /**
     * Renew liveness on an active access. Moves the effective expiry forward to `now + idleTtl`,
     * clamped to the absolute ceiling [expiresAtMillis], and never backward (safe under concurrent
     * requests for the same session). Returns the new effective expiry.
     */
    fun touch(nowMillis: Long): Long {
        val target = minOf(nowMillis + idleTtlMillis, expiresAtMillis)
        while (true) {
            val current = slidingExpiryMillis.get()
            if (target <= current) return current
            if (slidingExpiryMillis.compareAndSet(current, target)) return target
        }
    }
}

sealed interface SessionLookup {
    data class Ok(val session: ProxySession) : SessionLookup
    data object NotFound : SessionLookup
    data object Expired : SessionLookup
    data object Forbidden : SessionLookup
}

/**
 * Registry of active proxy sessions.
 *
 * Security (§6): high-entropy unguessable IDs, reject unknown/expired sessions, reject path
 * traversal, never act as an open proxy (no arbitrary upstream from the request), revoke + clear
 * on stop. Pure JVM, unit-testable.
 */
class SessionRegistry(
    private val random: SecureRandom = SecureRandom(),
    private val clock: () -> Long = System::currentTimeMillis,
    /** Absolute ceiling lifetime for a session if no explicit ceiling is given. */
    private val defaultTtlMillis: Long = DEFAULT_TTL_MILLIS,
    /** Sliding idle window for a session if no explicit idle window is given. */
    private val defaultIdleTtlMillis: Long = DEFAULT_IDLE_TTL_MILLIS,
) {
    private val sessions = ConcurrentHashMap<String, ProxySession>()

    /** Only these path suffixes are routable within a session; everything else is rejected. */
    private val allowedSubPaths = setOf("stream", "playlist.m3u8", "test")

    fun create(
        upstreamUrl: String,
        upstreamAuthHeader: String?,
        contentType: String,
        playSessionId: String?,
        isHls: Boolean = false,
        totalLength: Long? = null,
        localFilePath: String? = null,
        ttlMillis: Long = defaultTtlMillis,
        idleTtlMillis: Long = defaultIdleTtlMillis,
    ): ProxySession {
        // Opportunistic GC of stale sessions (expiry is otherwise only reclaimed lazily on resolve).
        purgeExpired()
        val id = newSessionId()
        val now = clock()
        val session = ProxySession(
            id = id,
            upstreamUrl = upstreamUrl,
            upstreamAuthHeader = upstreamAuthHeader,
            contentType = contentType,
            playSessionId = playSessionId,
            createdAtMillis = now,
            expiresAtMillis = now + ttlMillis,
            idleTtlMillis = idleTtlMillis,
            isHls = isHls,
            totalLength = totalLength,
            localFilePath = localFilePath,
        )
        sessions[id] = session
        return session
    }

    /**
     * Resolve an incoming request path of the form `/session/{id}/{sub}` to a session, applying
     * all security checks. Returns a typed [SessionLookup] so the proxy can map to 404/410/403.
     */
    fun resolve(requestPath: String): SessionLookup {
        val parts = normalize(requestPath) ?: return SessionLookup.Forbidden
        if (parts.isEmpty()) return SessionLookup.NotFound
        // Any path whose first segment is not "session" is not a proxy route at all.
        if (parts[0] != "session") return SessionLookup.Forbidden
        if (parts.size < 3) return SessionLookup.NotFound
        val id = parts[1]
        if (!isValidIdShape(id)) return SessionLookup.NotFound
        val sub = parts.subList(2, parts.size)
        // Reject anything that is not an explicitly allowed sub-path. This blocks path traversal,
        // arbitrary file access, and debug endpoints.
        if (sub.size != 1 || sub[0] !in allowedSubPaths) return SessionLookup.Forbidden
        val session = matchSession(id) ?: return SessionLookup.NotFound
        val now = clock()
        if (session.isExpired(now)) {
            sessions.remove(session.id)
            return SessionLookup.Expired
        }
        // Active access renews the sliding idle window (bounded by the absolute ceiling).
        session.touch(now)
        return SessionLookup.Ok(session)
    }

    fun revoke(id: String) { sessions.remove(id) }

    /** True only while the opaque id is still registered; used to reject relay work racing teardown. */
    fun isActive(id: String): Boolean = sessions.containsKey(id)

    /** Revoke all sessions and clear references (called on stop / delete-all-data). */
    fun revokeAll() { sessions.clear() }

    fun activeCount(): Int = sessions.size

    /** Remove expired sessions; returns number purged. */
    fun purgeExpired(): Int {
        val now = clock()
        val expired = sessions.filterValues { it.isExpired(now) }.keys
        expired.forEach { sessions.remove(it) }
        return expired.size
    }

    private fun newSessionId(): String {
        val bytes = ByteArray(SESSION_ID_BYTES)
        random.nextBytes(bytes)
        // URL-safe base64 without padding -> 256 bits of entropy, unguessable.
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun isValidIdShape(id: String): Boolean =
        id.length in MIN_ID_LEN..MAX_ID_LEN && id.all { it.isLetterOrDigit() || it == '-' || it == '_' }

    /**
     * Find the session whose id matches [id], comparing with a constant-time digest equality so the
     * lookup does not leak — via response timing — how many leading characters of a guessed id were
     * correct. The id space is 256-bit and the active set is tiny, so the linear scan is negligible.
     * The map remains keyed by id for O(1) revoke/purge; only this security-sensitive match is
     * constant-time.
     */
    private fun matchSession(id: String): ProxySession? {
        val presented = id.toByteArray(Charsets.US_ASCII)
        var match: ProxySession? = null
        for (session in sessions.values) {
            // No early break: keep the scan time independent of which entry (if any) matched.
            if (MessageDigest.isEqual(presented, session.id.toByteArray(Charsets.US_ASCII))) {
                match = session
            }
        }
        return match
    }

    /**
     * Normalise and reject traversal. Returns the decoded, cleaned path segments, or null if the
     * path attempts traversal / contains illegal segments.
     */
    private fun normalize(requestPath: String): List<String>? {
        // Reject encoded traversal and NUL bytes before decoding tricks can apply.
        val lowered = requestPath.lowercase()
        if (lowered.contains("%2e%2e") || lowered.contains("..") || requestPath.contains('\u0000')) {
            return null
        }
        val withoutQuery = requestPath.substringBefore('?')
        val decoded = try {
            java.net.URLDecoder.decode(withoutQuery, "UTF-8")
        } catch (_: Exception) {
            return null
        }
        if (decoded.contains("..") || decoded.contains('\u0000') || decoded.contains('\\')) return null
        return decoded.trim('/').split('/').filter { it.isNotEmpty() }
    }

    companion object {
        const val SESSION_ID_BYTES = 32 // 256 bits
        const val MIN_ID_LEN = 32
        const val MAX_ID_LEN = 64

        /** Absolute ceiling: a session can never outlive this, even under continuous playback. */
        const val DEFAULT_TTL_MILLIS = 24 * 60 * 60 * 1000L // 24h hard ceiling
        /** Sliding idle window: a session with no proxy access for this long expires. */
        const val DEFAULT_IDLE_TTL_MILLIS = 4 * 60 * 60 * 1000L // 4h idle
    }
}

/** Helper to redact a session reference for logs. */
fun ProxySession.logId(): String = "session:${id.take(6)}… (${LogRedactor.redactUrl(upstreamUrl)})"
