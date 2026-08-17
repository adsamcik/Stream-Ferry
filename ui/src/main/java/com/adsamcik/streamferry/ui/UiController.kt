package com.adsamcik.streamferry.ui

import android.content.Intent
import android.net.Uri
import com.adsamcik.streamferry.domain.MediaItem
import com.adsamcik.streamferry.physical.PhysicalTv
import com.adsamcik.streamferry.source.api.DownloadFormat
import com.adsamcik.streamferry.ui.state.Route
import com.adsamcik.streamferry.ui.theme.ThemeMode

/** Provider-neutral intent surface consumed by Compose. The application owns its implementation. */
interface UiController {
    val sources: List<Pair<String, String>>
    val readMediaVideoPermissions: Array<String>
    val preferDirectPlay: Boolean
    val transcodeLocalOnDevice: Boolean
    val maxVideoHeight: Int
    val autoPlayNextEpisode: Boolean
    val autoSkipSegments: Boolean
    val preferredAudioLanguage: String
    val preferredSubtitleLanguage: String

    fun navigate(route: Route)
    fun dismissError()
    fun onWelcomeContinue()
    fun openServers()
    fun switchServer(serverId: String)
    fun forgetServer(serverId: String)
    fun useLocalOnly()
    fun onServerUrlChanged(url: String)
    fun onAllowHttpChanged(allow: Boolean)
    fun testConnectionAndContinue()
    fun login(username: String, password: String)
    fun startQuickConnect()
    fun cancelQuickConnect()
    fun logout()
    fun mediaPermissionGranted(): Boolean
    fun mediaAccessGranted(): Boolean
    fun batteryOptimizationRequestIntent(): Intent
    fun selectSource(sourceId: String)
    fun onLocalFolderPicked(uri: Uri?)
    fun onLocalFilesPicked(uris: List<Uri>)
    fun onMediaPermissionResult(granted: Boolean)
    fun onItemClicked(item: MediaItem)
    fun popFolder()
    fun refreshGallery()
    fun onSearchQueryChanged(query: String)
    fun clearSearch()
    fun selectPhysicalTv(target: PhysicalTv)
    fun unlinkPhysicalTv(target: PhysicalTv)
    fun resumeSmartResume()
    fun resumePlaybackHistory(historyKey: String)
    fun dismissSmartResume()
    fun removePlaybackHistory(historyKey: String)
    fun clearPlaybackHistory()
    fun downloadSelected(format: DownloadFormat = DownloadFormat.Original)
    fun cancelDownload(itemId: String)
    fun deleteDownload(itemId: String)
    fun openDownloads()
    fun prepareCastDownload(itemId: String)
    fun clearDownloadSelection()
    fun enqueue(item: MediaItem)
    fun removePlaylistEntry(entryId: Long)
    fun clearPlaylist()
    fun skipToNextPlaylistItem()
    fun togglePlayPause()
    fun seekTo(positionSeconds: Long)
    fun setVolume(level: Float)
    fun dismissPlaybackControlIssue()
    fun selectAudioTrack(index: Int?)
    fun selectSubtitleTrack(index: Int?)
    fun selectQuality(bitrateBps: Long?)
    fun selectMaxVideoHeight(height: Int?)
    fun skipBy(deltaSeconds: Long)
    fun selectPreferredCodec(codec: String?)
    fun skipSegment()
    fun markWatched(item: MediaItem, played: Boolean)
    fun resetEpisodeProgress(item: MediaItem)
    fun retryPlayback()
    fun changeTv()
    fun stopPlayback()
    fun setPreferDirectPlay(value: Boolean)
    fun setThemeMode(value: ThemeMode)
    fun setTranscodeLocalOnDevice(value: Boolean)
    fun setMaxVideoHeight(value: Int)
    fun setAutoPlayNextEpisode(value: Boolean)
    fun setAutoSkipSegments(value: Boolean)
    fun setPreferredAudioLanguage(code: String)
    fun setPreferredSubtitleLanguage(code: String)
    fun resetLearnedTvCapabilities()
    fun localNetworkPermissionSettingsIntent(): Intent
    fun refreshDiagnostics()
    fun setTvTracingEnabled(enabled: Boolean)
    fun diagnosticsForExport(): String
    fun crashReportsForExport(): String
    fun dismissCrashAlert()
    fun clearCrashLogs()
    fun deleteAllData()
}
