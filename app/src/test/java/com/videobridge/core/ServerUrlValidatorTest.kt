package com.videobridge.core

import com.videobridge.core.net.ServerUrlValidator
import com.videobridge.core.net.ServerUrlValidator.Result
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ServerUrlValidatorTest {

    @Test fun httpsRemoteIsValid() {
        val r = ServerUrlValidator.validate("https://jellyfin.example.com", userApprovedHttp = false)
        assertIs<Result.Valid>(r)
        assertEquals("https://jellyfin.example.com", r.baseUrl)
        assertFalse(r.isHttp)
        assertFalse(r.isLan)
    }

    @Test fun missingSchemeDefaultsToHttps() {
        val r = ServerUrlValidator.validate("jellyfin.example.com", userApprovedHttp = false)
        assertIs<Result.Valid>(r)
        assertEquals("https://jellyfin.example.com", r.baseUrl)
    }

    @Test fun trailingSlashIsStrippedAndPathPreserved() {
        val r = ServerUrlValidator.validate("https://host.example.com/jellyfin/", userApprovedHttp = false)
        assertIs<Result.Valid>(r)
        assertEquals("https://host.example.com/jellyfin", r.baseUrl)
    }

    @Test fun remoteHttpIsRejected() {
        val r = ServerUrlValidator.validate("http://jellyfin.example.com", userApprovedHttp = true)
        assertIs<Result.Invalid>(r)
    }

    @Test fun lanHttpNeedsApprovalThenValid() {
        val pending = ServerUrlValidator.validate("http://192.168.1.50:8096", userApprovedHttp = false)
        assertIs<Result.NeedsHttpApproval>(pending)
        assertEquals("http://192.168.1.50:8096", pending.baseUrl)

        val approved = ServerUrlValidator.validate("http://192.168.1.50:8096", userApprovedHttp = true)
        assertIs<Result.Valid>(approved)
        assertTrue(approved.isHttp)
        assertTrue(approved.isLan)
    }

    @Test fun localhostHttpIsLan() {
        assertIs<Result.NeedsHttpApproval>(ServerUrlValidator.validate("http://localhost:8096", false))
        assertIs<Result.Valid>(ServerUrlValidator.validate("http://localhost:8096", true))
    }

    @Test fun dotLocalHttpIsLan() {
        assertIs<Result.Valid>(ServerUrlValidator.validate("http://media.local:8096", userApprovedHttp = true))
    }

    @Test fun singleLabelHostIsLan() {
        assertIs<Result.Valid>(ServerUrlValidator.validate("http://mediaserver:8096", userApprovedHttp = true))
    }

    @Test fun blankIsInvalid() {
        assertIs<Result.Invalid>(ServerUrlValidator.validate("   ", userApprovedHttp = true))
    }

    @Test fun unsupportedSchemeIsInvalid() {
        assertIs<Result.Invalid>(ServerUrlValidator.validate("ftp://host/x", userApprovedHttp = true))
    }

    @Test fun privateRangesDetected() {
        assertTrue(ServerUrlValidator.isPrivateHost("10.1.2.3"))
        assertTrue(ServerUrlValidator.isPrivateHost("172.16.5.5"))
        assertTrue(ServerUrlValidator.isPrivateHost("172.31.255.1"))
        assertFalse(ServerUrlValidator.isPrivateHost("172.32.0.1"))
        assertTrue(ServerUrlValidator.isPrivateHost("192.168.0.1"))
        assertTrue(ServerUrlValidator.isPrivateHost("100.64.0.1"))
        assertFalse(ServerUrlValidator.isPrivateHost("8.8.8.8"))
        assertFalse(ServerUrlValidator.isPrivateHost("jellyfin.example.com"))
    }

    @Test fun netbirdMeshDomainsAreLan() {
        // NetBird peer DNS names resolve to private CGNAT peer addresses, so count as LAN.
        assertTrue(ServerUrlValidator.isPrivateHost("netbird.cloud"))
        assertTrue(ServerUrlValidator.isPrivateHost("jellyfin.netbird.cloud"))
        assertTrue(ServerUrlValidator.isPrivateHost("peer.account.netbird.cloud"))
        assertTrue(ServerUrlValidator.isPrivateHost("NETBIRD.CLOUD")) // case-insensitive
        assertTrue(ServerUrlValidator.isPrivateHost("netbird.local"))
        assertTrue(ServerUrlValidator.isPrivateHost("jellyfin.netbird.local"))
        // Must NOT match look-alikes that are not actually under the mesh domain.
        assertFalse(ServerUrlValidator.isPrivateHost("mynetbird.cloud"))
        assertFalse(ServerUrlValidator.isPrivateHost("netbird.cloud.evil.com"))
        assertFalse(ServerUrlValidator.isPrivateHost("app.netbird.io")) // management domain is public
    }

    @Test fun tailscaleMagicDnsIsLan() {
        // Tailscale MagicDNS names (<host>.<tailnet>.ts.net) resolve to 100.64/10 peers.
        assertTrue(ServerUrlValidator.isPrivateHost("nas.tail1234.ts.net"))
        assertTrue(ServerUrlValidator.isPrivateHost("ts.net"))
        assertFalse(ServerUrlValidator.isPrivateHost("notts.net"))
        assertFalse(ServerUrlValidator.isPrivateHost("ts.net.example.com"))
        assertIs<Result.Valid>(
            ServerUrlValidator.validate("http://nas.tail1234.ts.net:8096", userApprovedHttp = true),
        )
    }

    @Test fun reservedLocalTldsAreLan() {
        assertTrue(ServerUrlValidator.isPrivateHost("host.home.arpa"))   // RFC 8375
        assertTrue(ServerUrlValidator.isPrivateHost("jf.internal"))      // ICANN-reserved
        assertTrue(ServerUrlValidator.isPrivateHost("jf.intranet"))
        assertTrue(ServerUrlValidator.isPrivateHost("jf.private"))
        assertTrue(ServerUrlValidator.isPrivateHost("jf.corp"))
        assertTrue(ServerUrlValidator.isPrivateHost("jf.localhost"))     // RFC 6761
        // Still public: a normal delegated domain.
        assertFalse(ServerUrlValidator.isPrivateHost("jellyfin.example.com"))
        assertFalse(ServerUrlValidator.isPrivateHost("private.example.com"))
    }

    @Test fun fqdnTrailingDotIsHandled() {
        assertTrue(ServerUrlValidator.isPrivateHost("media.local."))
        assertTrue(ServerUrlValidator.isPrivateHost("nas.tail1234.ts.net."))
        assertIs<Result.Valid>(ServerUrlValidator.validate("http://media.local.:8096", userApprovedHttp = true))
    }

    @Test fun ipv6PrivateRangesDetected() {
        assertTrue(ServerUrlValidator.isPrivateHost("::1"))               // loopback
        assertTrue(ServerUrlValidator.isPrivateHost("fd7a:115c:a1e0::1")) // Tailscale ULA
        assertTrue(ServerUrlValidator.isPrivateHost("fc00::1"))           // ULA
        assertTrue(ServerUrlValidator.isPrivateHost("fe80::1%eth0"))      // link-local w/ zone id
        assertFalse(ServerUrlValidator.isPrivateHost("2001:4860:4860::8888")) // public (Google DNS)
    }

    @Test fun netbirdCloudHttpNeedsApprovalThenValid() {
        val pending = ServerUrlValidator.validate("http://jellyfin.netbird.cloud:8096", userApprovedHttp = false)
        assertIs<Result.NeedsHttpApproval>(pending)
        assertEquals("http://jellyfin.netbird.cloud:8096", pending.baseUrl)

        val approved = ServerUrlValidator.validate("http://jellyfin.netbird.cloud:8096", userApprovedHttp = true)
        assertIs<Result.Valid>(approved)
        assertTrue(approved.isHttp)
        assertTrue(approved.isLan)
    }

    @Test fun netbirdLocalHttpIsLan() {
        assertIs<Result.Valid>(
            ServerUrlValidator.validate("http://jellyfin.netbird.local:8096", userApprovedHttp = true),
        )
    }
}
