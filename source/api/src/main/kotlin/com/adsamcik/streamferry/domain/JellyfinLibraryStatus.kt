package com.adsamcik.streamferry.domain

/**
 * Availability of the active Jellyfin library, independent from authentication setup state.
 *
 * A server can be unavailable while a verified account's cached metadata and downloaded media remain
 * usable. Keeping that distinction in the domain model prevents the UI from treating cache fallback as
 * a successful live connection.
 */
enum class JellyfinLibraryStatus {
    /** No request has established the active server's reachability yet. */
    UNKNOWN,
    /** A fresh authenticated Jellyfin library request succeeded. */
    ONLINE,
    /** A fresh request could not reach the active server; cached metadata may still be browseable. */
    UNAVAILABLE,
}

/** Stable, non-secret identity of a Jellyfin account's locally scoped metadata. */
data class JellyfinLibraryScope(val serverId: String, val userId: String) {
    val cacheKey: String get() = "server_${serverId}_user_${userId}"
}
