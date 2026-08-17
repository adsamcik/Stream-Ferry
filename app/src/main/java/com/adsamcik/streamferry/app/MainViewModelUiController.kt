package com.adsamcik.streamferry.app

import android.content.Intent
import android.net.Uri
import com.adsamcik.streamferry.domain.MediaItem
import com.adsamcik.streamferry.physical.PhysicalTv
import com.adsamcik.streamferry.source.api.DownloadFormat
import com.adsamcik.streamferry.ui.UiController
import com.adsamcik.streamferry.ui.state.Route
import com.adsamcik.streamferry.ui.theme.ThemeMode

/** Application-side adapter; Compose never receives the concrete source-aware coordinator. */
class MainViewModelUiController(
    private val viewModel: MainViewModel,
) : UiController {
    override val sources get() = viewModel.sources
    override val readMediaVideoPermissions get() = viewModel.readMediaVideoPermissions
    override val preferDirectPlay get() = viewModel.preferDirectPlay
    override val transcodeLocalOnDevice get() = viewModel.transcodeLocalOnDevice
    override val maxVideoHeight get() = viewModel.maxVideoHeight
    override val autoPlayNextEpisode get() = viewModel.autoPlayNextEpisode
    override val autoSkipSegments get() = viewModel.autoSkipSegments
    override val preferredAudioLanguage get() = viewModel.preferredAudioLanguage
    override val preferredSubtitleLanguage get() = viewModel.preferredSubtitleLanguage

    override fun navigate(route: Route) = viewModel.navigate(route)
    override fun dismissError() { viewModel.dismissError() }
    override fun onWelcomeContinue() = viewModel.onWelcomeContinue()
    override fun openServers() { viewModel.openServers() }
    override fun switchServer(serverId: String) = viewModel.switchServer(serverId)
    override fun forgetServer(serverId: String) = viewModel.forgetServer(serverId)
    override fun useLocalOnly() = viewModel.useLocalOnly()
    override fun onServerUrlChanged(url: String) { viewModel.onServerUrlChanged(url) }
    override fun onAllowHttpChanged(allow: Boolean) { viewModel.onAllowHttpChanged(allow) }
    override fun testConnectionAndContinue() = viewModel.testConnectionAndContinue()
    override fun login(username: String, password: String) { viewModel.login(username, password) }
    override fun startQuickConnect() = viewModel.startQuickConnect()
    override fun cancelQuickConnect() = viewModel.cancelQuickConnect()
    override fun logout() = viewModel.logout()
    override fun mediaPermissionGranted() = viewModel.mediaPermissionGranted()
    override fun mediaAccessGranted() = viewModel.mediaAccessGranted()
    override fun batteryOptimizationRequestIntent(): Intent = viewModel.batteryOptimizationRequestIntent()
    override fun selectSource(sourceId: String) = viewModel.selectSource(sourceId)
    override fun onLocalFolderPicked(uri: Uri?) = viewModel.onLocalFolderPicked(uri)
    override fun onLocalFilesPicked(uris: List<Uri>) = viewModel.onLocalFilesPicked(uris)
    override fun onMediaPermissionResult(granted: Boolean) = viewModel.onMediaPermissionResult(granted)
    override fun onItemClicked(item: MediaItem) = viewModel.onItemClicked(item)
    override fun popFolder() = viewModel.popFolder()
    override fun refreshGallery() = viewModel.refreshGallery()
    override fun onSearchQueryChanged(query: String) = viewModel.onSearchQueryChanged(query)
    override fun clearSearch() = viewModel.clearSearch()
    override fun selectPhysicalTv(target: PhysicalTv) = viewModel.selectPhysicalTv(target)
    override fun unlinkPhysicalTv(target: PhysicalTv) = viewModel.unlinkPhysicalTv(target)
    override fun resumeSmartResume() { viewModel.resumeSmartResume() }
    override fun resumePlaybackHistory(historyKey: String) { viewModel.resumePlaybackHistory(historyKey) }
    override fun dismissSmartResume() = viewModel.dismissSmartResume()
    override fun removePlaybackHistory(historyKey: String) = viewModel.removePlaybackHistory(historyKey)
    override fun clearPlaybackHistory() = viewModel.clearPlaybackHistory()
    override fun downloadSelected(format: DownloadFormat) = viewModel.downloadSelected(format)
    override fun cancelDownload(itemId: String) = viewModel.cancelDownload(itemId)
    override fun deleteDownload(itemId: String) { viewModel.deleteDownload(itemId) }
    override fun openDownloads() { viewModel.openDownloads() }
    override fun prepareCastDownload(itemId: String) { viewModel.prepareCastDownload(itemId) }
    override fun clearDownloadSelection() { viewModel.clearDownloadSelection() }
    override fun enqueue(item: MediaItem) = viewModel.enqueue(item)
    override fun removePlaylistEntry(entryId: Long) = viewModel.removePlaylistEntry(entryId)
    override fun clearPlaylist() = viewModel.clearPlaylist()
    override fun skipToNextPlaylistItem() = viewModel.skipToNextPlaylistItem()
    override fun togglePlayPause() = viewModel.togglePlayPause()
    override fun seekTo(positionSeconds: Long) = viewModel.seekTo(positionSeconds)
    override fun setVolume(level: Float) = viewModel.setVolume(level)
    override fun dismissPlaybackControlIssue() { viewModel.dismissPlaybackControlIssue() }
    override fun selectAudioTrack(index: Int?) { viewModel.selectAudioTrack(index) }
    override fun selectSubtitleTrack(index: Int?) { viewModel.selectSubtitleTrack(index) }
    override fun selectQuality(bitrateBps: Long?) { viewModel.selectQuality(bitrateBps) }
    override fun selectMaxVideoHeight(height: Int?) { viewModel.selectMaxVideoHeight(height) }
    override fun skipBy(deltaSeconds: Long) = viewModel.skipBy(deltaSeconds)
    override fun selectPreferredCodec(codec: String?) { viewModel.selectPreferredCodec(codec) }
    override fun skipSegment() { viewModel.skipSegment() }
    override fun markWatched(item: MediaItem, played: Boolean) = viewModel.markWatched(item, played)
    override fun resetEpisodeProgress(item: MediaItem) = viewModel.resetEpisodeProgress(item)
    override fun retryPlayback() = viewModel.retryPlayback()
    override fun changeTv() { viewModel.changeTv() }
    override fun stopPlayback() = viewModel.stopPlayback()
    override fun setPreferDirectPlay(value: Boolean) = viewModel.setPreferDirectPlay(value)
    override fun setThemeMode(value: ThemeMode) = viewModel.setThemeMode(value)
    override fun setTranscodeLocalOnDevice(value: Boolean) = viewModel.setTranscodeLocalOnDevice(value)
    override fun setMaxVideoHeight(value: Int) = viewModel.setMaxVideoHeight(value)
    override fun setAutoPlayNextEpisode(value: Boolean) = viewModel.setAutoPlayNextEpisode(value)
    override fun setAutoSkipSegments(value: Boolean) = viewModel.setAutoSkipSegments(value)
    override fun setPreferredAudioLanguage(code: String) = viewModel.setPreferredAudioLanguage(code)
    override fun setPreferredSubtitleLanguage(code: String) = viewModel.setPreferredSubtitleLanguage(code)
    override fun resetLearnedTvCapabilities() = viewModel.resetLearnedTvCapabilities()
    override fun localNetworkPermissionSettingsIntent(): Intent = viewModel.localNetworkPermissionSettingsIntent()
    override fun refreshDiagnostics() { viewModel.refreshDiagnostics() }
    override fun setTvTracingEnabled(enabled: Boolean) = viewModel.setTvTracingEnabled(enabled)
    override fun diagnosticsForExport() = viewModel.diagnosticsForExport()
    override fun crashReportsForExport() = viewModel.crashReportsForExport()
    override fun dismissCrashAlert() { viewModel.dismissCrashAlert() }
    override fun clearCrashLogs() { viewModel.clearCrashLogs() }
    override fun deleteAllData() = viewModel.deleteAllData()
}
