package com.videobridge.permissions

import com.videobridge.domain.NetworkPermissionManager

/** Raised before an operation that would send or accept LAN traffic without user permission. */
class LocalNetworkAccessDeniedException : IllegalStateException(
    "Local network access is required to discover or play to a TV.",
)

/**
 * Single fresh-check boundary for LAN operations. UI state is intentionally not trusted: a permission
 * can be denied, revoked, or bypassed by cached-target playback after the UI decision was made.
 */
class LocalNetworkAccessGate(private val permissions: NetworkPermissionManager) {
    fun isAllowed(): Boolean = permissions.hasLocalNetworkAccess()

    fun requireAccess() {
        if (!isAllowed()) throw LocalNetworkAccessDeniedException()
    }
}
