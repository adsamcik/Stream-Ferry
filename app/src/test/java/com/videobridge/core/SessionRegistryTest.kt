package com.videobridge.core

import com.videobridge.core.session.SessionLookup
import com.videobridge.core.session.SessionRegistry
import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SessionRegistryTest {

    private fun registry(now: () -> Long = { 0L }) =
        SessionRegistry(random = SecureRandom(), clock = now)

    @Test fun createdSessionResolves() {
        val reg = registry()
        val s = reg.create("http://up/stream?api_key=x", "auth", "video/mp4", "ps1")
        val r = reg.resolve("/session/${s.id}/stream")
        assertIs<SessionLookup.Ok>(r)
        assertEquals(s.id, r.session.id)
    }

    @Test fun totalLengthCarriedForDirectPlay() {
        // Direct play threads Jellyfin's MediaSource size onto the session so the proxy can advertise
        // Content-Length and the renderer can byte-seek. A transcode/unknown source leaves it null.
        val reg = registry()
        val direct = reg.create("http://up/stream", "auth", "video/mp4", "ps", totalLength = 123_456L)
        assertEquals(123_456L, direct.totalLength)
        val transcode = reg.create("http://up/master.m3u8", "auth", "application/vnd.apple.mpegurl", "ps", isHls = true)
        assertEquals(null, transcode.totalLength)
    }

    @Test fun highEntropyUniqueIds() {
        val reg = registry()
        val ids = (1..200).map { reg.create("u", null, "video/mp4", null).id }.toSet()
        assertEquals(200, ids.size)
        ids.forEach { assertTrue(it.length >= 32) }
    }

    @Test fun unknownSessionNotFound() {
        val reg = registry()
        assertIs<SessionLookup.NotFound>(reg.resolve("/session/deadbeefdeadbeefdeadbeefdeadbeef/stream"))
    }

    @Test fun expiredSession() {
        var t = 0L
        val reg = registry { t }
        val s = reg.create("u", null, "video/mp4", null, ttlMillis = 1000)
        t = 2000
        assertIs<SessionLookup.Expired>(reg.resolve("/session/${s.id}/stream"))
    }

    @Test fun pathTraversalRejected() {
        val reg = registry()
        val s = reg.create("u", null, "video/mp4", null)
        assertIs<SessionLookup.Forbidden>(reg.resolve("/session/${s.id}/../../etc/passwd"))
        assertIs<SessionLookup.Forbidden>(reg.resolve("/session/${s.id}/%2e%2e/secret"))
        assertIs<SessionLookup.Forbidden>(reg.resolve("/../../session/${s.id}/stream"))
    }

    @Test fun unknownSubPathForbidden() {
        val reg = registry()
        val s = reg.create("u", null, "video/mp4", null)
        assertIs<SessionLookup.Forbidden>(reg.resolve("/session/${s.id}/debug"))
        assertIs<SessionLookup.Forbidden>(reg.resolve("/session/${s.id}/../stream"))
    }

    @Test fun nonSessionPathForbidden() {
        val reg = registry()
        assertIs<SessionLookup.Forbidden>(reg.resolve("/etc/passwd"))
        assertIs<SessionLookup.Forbidden>(reg.resolve("/admin/stream"))
    }

    @Test fun revokeRemovesSession() {
        val reg = registry()
        val s = reg.create("u", null, "video/mp4", null)
        reg.revoke(s.id)
        assertIs<SessionLookup.NotFound>(reg.resolve("/session/${s.id}/stream"))
    }

    @Test fun revokeAllClears() {
        val reg = registry()
        reg.create("u", null, "video/mp4", null)
        reg.create("u", null, "video/mp4", null)
        assertEquals(2, reg.activeCount())
        reg.revokeAll()
        assertEquals(0, reg.activeCount())
    }

    @Test fun upstreamUrlNeverInId() {
        val reg = registry()
        val s = reg.create("http://jelly/secret?api_key=abc", "auth", "video/mp4", "ps")
        assertFalse(s.id.contains("secret"))
        assertFalse(s.id.contains("abc"))
        assertNotEquals(s.upstreamUrl, s.id)
    }

    // ----- Sliding idle TTL bounded by an absolute ceiling (G-1) -----

    @Test fun slidingTtlRenewsOnActiveAccess() {
        var t = 0L
        val reg = registry { t }
        // Idle window 1000ms; absolute ceiling far away.
        val s = reg.create("u", null, "video/mp4", null, ttlMillis = 1_000_000, idleTtlMillis = 1000)
        // Each access before the current idle deadline renews the window.
        t = 900; assertIs<SessionLookup.Ok>(reg.resolve("/session/${s.id}/stream"))
        t = 1800; assertIs<SessionLookup.Ok>(reg.resolve("/session/${s.id}/stream"))
        t = 2700; assertIs<SessionLookup.Ok>(reg.resolve("/session/${s.id}/stream"))
        // Total elapsed (2700) exceeds the initial idle window (1000), yet playback survives.
        assertEquals(3700L, s.effectiveExpiresAtMillis)
    }

    @Test fun idleSessionExpiresBeforeCeiling() {
        var t = 0L
        val reg = registry { t }
        val s = reg.create("u", null, "video/mp4", null, ttlMillis = 1_000_000, idleTtlMillis = 1000)
        t = 1500 // past the idle window but far below the absolute ceiling
        assertIs<SessionLookup.Expired>(reg.resolve("/session/${s.id}/stream"))
    }

    @Test fun absoluteCeilingCapsRenewal() {
        var t = 0L
        val reg = registry { t }
        // Idle window large, ceiling small: renewal can never push expiry past the ceiling.
        val s = reg.create("u", null, "video/mp4", null, ttlMillis = 5000, idleTtlMillis = 10_000)
        t = 1000; assertIs<SessionLookup.Ok>(reg.resolve("/session/${s.id}/stream"))
        t = 4000; assertIs<SessionLookup.Ok>(reg.resolve("/session/${s.id}/stream"))
        assertEquals(5000L, s.effectiveExpiresAtMillis) // clamped to ceiling despite touches
        t = 6000; assertIs<SessionLookup.Expired>(reg.resolve("/session/${s.id}/stream"))
    }

    // ----- Constant-time id match (G-9): a near-miss id must not resolve -----

    @Test fun nearMissIdNotFound() {
        val reg = registry()
        val s = reg.create("u", null, "video/mp4", null)
        val last = s.id.last()
        val alt = if (last == 'A') 'B' else 'A' // a different, still id-shape-valid char
        val nearMiss = s.id.dropLast(1) + alt
        assertNotEquals(s.id, nearMiss)
        assertIs<SessionLookup.NotFound>(reg.resolve("/session/$nearMiss/stream"))
        // The exact id still resolves.
        assertIs<SessionLookup.Ok>(reg.resolve("/session/${s.id}/stream"))
    }

    @Test fun createPurgesExpiredSessions() {
        var t = 0L
        val reg = registry { t }
        reg.create("u", null, "video/mp4", null, ttlMillis = 1000)
        assertEquals(1, reg.activeCount())
        t = 2000
        reg.create("u", null, "video/mp4", null, ttlMillis = 1000) // create() purges the now-expired one
        assertEquals(1, reg.activeCount())
    }
}
