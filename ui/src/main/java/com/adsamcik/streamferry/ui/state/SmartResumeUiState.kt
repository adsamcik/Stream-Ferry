package com.adsamcik.streamferry.ui.state

import com.adsamcik.streamferry.core.resume.ResumePolicy
import com.adsamcik.streamferry.core.resume.SmartResumeRecord
import com.adsamcik.streamferry.core.resume.SmartResumeRecordState
import com.adsamcik.streamferry.core.resume.SmartResumeSourceType
import com.adsamcik.streamferry.domain.UserSession

data class SmartResumeUiState(
    val historyKey: String,
    val mediaId: String,
    val sourceType: SmartResumeSourceType,
    val title: String,
    val subtitle: String?,
    val sourceLabel: String,
    val positionSeconds: Long,
    val durationSeconds: Long?,
    val progressFraction: Float?,
    val actionLabel: String,
    val updatedAtMillis: Long,
    val isFinished: Boolean,
)

/** A record is intentionally hidden while a different Jellyfin account is active. */
fun SmartResumeRecord.toUiState(currentUser: UserSession?): SmartResumeUiState? {
    val position = resumePositionSeconds() ?: return null
    return toPlaybackHistoryUiState(currentUser)?.copy(
        positionSeconds = position,
        progressFraction = ResumePolicy.progressFraction(position, durationSeconds),
        actionLabel = "Resume at ${formatSmartResumeTime(position)}",
    )
}

/** Maps both resumable and completed records for the playback-history surface. */
fun SmartResumeRecord.toPlaybackHistoryUiState(currentUser: UserSession?): SmartResumeUiState? {
    if (sourceType != SmartResumeSourceType.LOCAL && currentUser != null &&
        (currentUser.serverId != serverId || currentUser.userId != userId)
    ) return null
    val finished = state == SmartResumeRecordState.FINISHED
    val resumablePosition = resumePositionSeconds()
    return SmartResumeUiState(
        historyKey = identityKey(),
        mediaId = mediaId,
        sourceType = sourceType,
        title = displayTitle,
        subtitle = displaySubtitle,
        sourceLabel = when (sourceType) {
            SmartResumeSourceType.REMOTE -> "Server"
            SmartResumeSourceType.DOWNLOADED -> "Downloaded"
            SmartResumeSourceType.LOCAL -> "On this device"
        },
        positionSeconds = confirmedPositionSeconds,
        durationSeconds = durationSeconds,
        progressFraction = if (finished) 1f else ResumePolicy.progressFraction(confirmedPositionSeconds, durationSeconds),
        actionLabel = when {
            finished -> "Watch again"
            resumablePosition != null -> "Resume at ${formatSmartResumeTime(resumablePosition)}"
            else -> "Play from start"
        },
        updatedAtMillis = updatedAtMillis,
        isFinished = finished,
    )
}

fun formatSmartResumeTime(seconds: Long): String {
    val safe = seconds.coerceAtLeast(0)
    val hours = safe / 3600
    val minutes = (safe % 3600) / 60
    val secs = safe % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, secs) else "%d:%02d".format(minutes, secs)
}
