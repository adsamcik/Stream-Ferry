package com.adsamcik.streamferry.core.net

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TrustedMediaOriginPolicyTest {

    @Test fun relativeReferencesResolveAgainstTheTrustedServerPath() {
        val policy = policy("https://jellyfin.example/jellyfin/")

        assertEquals(
            "https://jellyfin.example/jellyfin/Videos/42/stream",
            policy.resolve("Videos/42/stream")?.toString(),
        )
        assertEquals(
            "https://jellyfin.example/Videos/42/stream",
            policy.resolve("/Videos/42/stream")?.toString(),
        )
        assertEquals(
            "https://jellyfin.example/jellyfin/?static=true",
            policy.resolve("?static=true")?.toString(),
        )
    }


    @Test fun basePathWithoutTrailingSlashStillResolvesAsDirectory() {
        val policy = policy("https://jellyfin.example/jellyfin")

        assertEquals(
            "https://jellyfin.example/jellyfin/Videos/42/stream",
            policy.resolve("Videos/42/stream")?.toString(),
        )
    }

    @Test fun sameOriginHttpsUsesTheEffectiveDefaultPort() {
        val policy = policy("https://jellyfin.example:443/jellyfin/")

        assertNotNull(policy.trustedAbsolute("https://jellyfin.example/Videos/42/stream"))
        assertNull(policy.trustedAbsolute("https://jellyfin.example:8443/Videos/42/stream"))
        assertNull(policy.trustedAbsolute("http://jellyfin.example/Videos/42/stream"))
    }

    @Test fun hlsNestedReferencesCannotLeaveThePinnedOrigin() {
        val policy = policy("https://jellyfin.example/jellyfin/")
        val nestedPlaylist = "https://jellyfin.example/jellyfin/hls/level/playlist.m3u8".toHttpUrl()

        assertEquals(
            "https://jellyfin.example/jellyfin/hls/level/segment-1.m4s",
            policy.resolve("segment-1.m4s", nestedPlaylist)?.toString(),
        )
        assertNull(policy.resolve("//attacker.example/segment.m4s", nestedPlaylist))
        assertNull(policy.resolve("https://attacker.example/segment.m4s", nestedPlaylist))
        assertNull(policy.resolve("http://jellyfin.example/jellyfin/segment.m4s", nestedPlaylist))
        assertNull(policy.resolve("data:text/plain,not-media", nestedPlaylist))
    }

    @Test fun redirectsCanOnlyResolveToTheExactPinnedOrigin() {
        val policy = policy("https://jellyfin.example:8443/jellyfin/")
        val current = "https://jellyfin.example:8443/jellyfin/hls/master.m3u8".toHttpUrl()

        assertEquals(
            "https://jellyfin.example:8443/jellyfin/hls/next.m3u8",
            policy.resolve("next.m3u8", current)?.toString(),
        )
        assertNull(policy.resolve("https://jellyfin.example/jellyfin/hls/next.m3u8", current))
        assertNull(policy.resolve("https://attacker.example/jellyfin/hls/next.m3u8", current))
    }

    @Test fun ipv6AndUserInfoAreHandledWithoutRelaxingTheOriginBoundary() {
        val policy = policy("https://[2001:db8::1]:8443/jellyfin/")

        assertNotNull(policy.trustedAbsolute("https://[2001:db8::1]:8443/Videos/42/stream"))
        assertNull(policy.trustedAbsolute("https://[2001:db8::1]:8444/Videos/42/stream"))
        assertNull(policy.trustedAbsolute("https://user:pass@[2001:db8::1]:8443/Videos/42/stream"))
        assertNull(TrustedMediaOriginPolicy.fromBaseUrl("https://user:pass@jellyfin.example/jellyfin/"))
    }

    private fun policy(baseUrl: String): TrustedMediaOriginPolicy =
        checkNotNull(TrustedMediaOriginPolicy.fromBaseUrl(baseUrl))
}
