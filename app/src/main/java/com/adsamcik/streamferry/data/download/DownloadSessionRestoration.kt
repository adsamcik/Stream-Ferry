package com.adsamcik.streamferry.data.download

import com.adsamcik.streamferry.domain.UserSession
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/** A sticky foreground-service restart may restore only the active profile's verified live account. */
internal suspend fun restoreDownloadOwner(
    ensureOnlineSession: suspend () -> Result<UserSession>,
    currentSession: () -> UserSession?,
): DownloadOwner? {
    val verified = ensureOnlineSession().getOrNull()
    coroutineContext.ensureActive()
    return verified?.takeIf { it == currentSession() }?.let { DownloadOwner(it.serverId, it.userId) }
}
