package com.adsamcik.streamferry.ui.state

import com.adsamcik.streamferry.core.resume.ResumePolicy
import com.adsamcik.streamferry.core.resume.SmartResumeRecord
import com.adsamcik.streamferry.core.resume.SmartResumeSourceType
import com.adsamcik.streamferry.domain.UserSession

data class SmartResumeUiState(
    val title: String,
    val subtitle: String?,
    val sourceLabel: String,
    val positionSeconds: Long,
    val durationSeconds: Long?,
    val progressFraction: Float?,
    val actionLabel: String,
)

/** A record is intentionally hidden while a different Jellyfin account is active. */
fun SmartResumeRecord.toUiState(currentUser: UserSession?): SmartResumeUiState? {
    val position = resumePositionSeconds() ?: return null
    if (sourceType != SmartResumeSourceType.LOCAL && currentUser != null &&
        (currentUser.serverId != serverId || currentUser.userId != userId)
    ) return null
    return SmartResumeUiState(
        title = displayTitle,
        subtitle = displaySubtitle,
        sourceLabel = when (sourceType) {
            SmartResumeSourceType.JELLYFIN -> "Jellyfin"
            SmartResumeSourceType.DOWNLOADED -> "Downloaded"
            SmartResumeSourceType.LOCAL -> "On this device"
        },
        positionSeconds = position,
        durationSeconds = durationSeconds,
        progressFraction = ResumePolicy.progressFraction(position, durationSeconds),
        actionLabel = "Resume at ${formatSmartResumeTime(position)}",
    )
}

fun formatSmartResumeTime(seconds: Long): String {
    val safe = seconds.coerceAtLeast(0)
    val hours = safe / 3600
    val minutes = (safe % 3600) / 60
    val secs = safe % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, secs) else "%d:%02d".format(minutes, secs)
}
