package com.adsamcik.streamferry.data.cache

import com.adsamcik.streamferry.domain.JellyfinLibraryStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-scoped reachability signal for the active Jellyfin library. It contains no server address,
 * token, or exception detail, so it is safe to surface directly as an availability indicator.
 */
class JellyfinConnectionMonitor {
    private val _status = MutableStateFlow(JellyfinLibraryStatus.UNKNOWN)
    val status: StateFlow<JellyfinLibraryStatus> = _status.asStateFlow()

    fun markOnline() {
        _status.value = JellyfinLibraryStatus.ONLINE
    }

    fun markUnavailable() {
        _status.value = JellyfinLibraryStatus.UNAVAILABLE
    }

    fun reset() {
        _status.value = JellyfinLibraryStatus.UNKNOWN
    }
}
