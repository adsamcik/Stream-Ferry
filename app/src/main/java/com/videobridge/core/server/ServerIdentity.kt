package com.videobridge.core.server

/**
 * Server identity pinning (anti-spoof). On first connect we pin the Jellyfin server's stable
 * **Id** (from `/System/Info/Public`); on every later reconnect — including HTTP LAN — we re-fetch it
 * and refuse to send the access token if it doesn't match the pin, so a different box that grabs the
 * same LAN IP can't impersonate the server. The token is the real verifier (only the genuine server
 * accepts it); pinning just prevents leaking the token to an impostor. Sufficient assuming no targeted
 * attacker who can clone the (publicly readable) Id. Pure + unit-tested.
 */
object ServerIdentity {

    /**
     * Whether a server reporting [fetchedId] may be trusted as the one pinned at [pinnedId].
     * - No pin yet (first connect) => trust (the caller pins [fetchedId]).
     * - Pinned, server reports an id => must match exactly.
     * - Pinned but server reports no id => not disproven; allow (no targeted attack assumption).
     */
    fun matches(pinnedId: String?, fetchedId: String?): Boolean {
        if (pinnedId.isNullOrBlank()) return true
        if (fetchedId.isNullOrBlank()) return true
        return pinnedId == fetchedId
    }

    /** True only when both ids are present AND differ — a definite spoof/mismatch worth blocking. */
    fun isMismatch(pinnedId: String?, fetchedId: String?): Boolean =
        !pinnedId.isNullOrBlank() && !fetchedId.isNullOrBlank() && pinnedId != fetchedId
}
