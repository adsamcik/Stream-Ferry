package com.adsamcik.streamferry.playback.streamselection

import com.adsamcik.streamferry.core.stream.MediaProfile
import com.adsamcik.streamferry.core.stream.StreamPreferences
import com.adsamcik.streamferry.core.stream.TargetCapabilities
import com.adsamcik.streamferry.core.stream.StreamDecision
import com.adsamcik.streamferry.domain.StreamSelectionService
import com.adsamcik.streamferry.core.stream.StreamSelectionService as CoreStreamSelectionService

/** Adapts the framework-free core stream-selection logic to the domain interface. */
class DefaultStreamSelectionService(
    private val core: CoreStreamSelectionService = CoreStreamSelectionService(),
) : StreamSelectionService {
    override fun select(caps: TargetCapabilities, media: MediaProfile, prefs: StreamPreferences): StreamDecision =
        core.select(caps, media, prefs)
}
