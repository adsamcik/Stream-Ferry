package com.videobridge.playback.streamselection

import com.videobridge.core.stream.MediaProfile
import com.videobridge.core.stream.StreamPreferences
import com.videobridge.core.stream.TargetCapabilities
import com.videobridge.core.stream.StreamDecision
import com.videobridge.domain.StreamSelectionService
import com.videobridge.core.stream.StreamSelectionService as CoreStreamSelectionService

/** Adapts the framework-free core stream-selection logic to the domain interface. */
class DefaultStreamSelectionService(
    private val core: CoreStreamSelectionService = CoreStreamSelectionService(),
) : StreamSelectionService {
    override fun select(caps: TargetCapabilities, media: MediaProfile, prefs: StreamPreferences): StreamDecision =
        core.select(caps, media, prefs)
}
