package com.adsamcik.streamferry.data.dlna

/** Bounded state for the deferred REL_TIME seek needed to resume a newly loaded DLNA URI. */
internal data class PendingDlnaResumeSeek(
    val positionSeconds: Long,
    val failedAttempts: Int = 0,
)

internal object DlnaResumeSeekPolicy {
    const val MAX_ATTEMPTS = 3

    /** Return the retry state, or null once the bounded attempt budget is exhausted. */
    fun afterFailure(pending: PendingDlnaResumeSeek): PendingDlnaResumeSeek? =
        pending.copy(failedAttempts = pending.failedAttempts + 1)
            .takeIf { it.failedAttempts < MAX_ATTEMPTS }
}
