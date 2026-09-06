package com.adsamcik.streamferry.playback

import com.adsamcik.streamferry.core.resume.SmartResumeSeed
import com.adsamcik.streamferry.core.resume.SmartResumeSourceType

/** Manual watch edits belong to one playback, never to a title's future queue entries. */
internal class PlaybackWatchStatePolicy {
    class Session internal constructor(internal val owner: Owner?) {
        @Volatile var automatic: Boolean = true
            internal set
    }
    internal data class Owner(val serverId: String, val userId: String, val itemId: String)
    @Volatile var current: Session? = null
        private set
    private var active = false

    @Synchronized fun begin(seed: SmartResumeSeed?, continuation: Boolean, prepareTracking: (Boolean) -> Unit) {
        val owner = seed?.takeIf {
            it.sourceType != SmartResumeSourceType.LOCAL && it.isStructurallyValid()
        }?.let { Owner(it.serverId!!, it.userId!!, it.mediaId) }
        val previous = current
        val next = if (continuation && owner != null && previous?.owner == owner) previous else Session(owner)
        current = next
        active = true
        prepareTracking(next.automatic)
    }

    @Synchronized fun suppress(serverId: String, userId: String, itemId: String, detachTracking: () -> Unit): Boolean {
        val session = current ?: return false
        if (!active || session.owner != Owner(serverId, userId, itemId)) return false
        session.automatic = false
        detachTracking()
        return true
    }

    @Synchronized fun end() { active = false }
}
