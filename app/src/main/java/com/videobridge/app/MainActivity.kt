package com.videobridge.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModelProvider
import com.videobridge.permissions.AndroidNetworkPermissionManager
import com.videobridge.ui.AppRoot
import com.videobridge.ui.MainViewModel
import com.videobridge.ui.theme.JellyfinBridgeTheme

class MainActivity : ComponentActivity() {

    private val container by lazy { (application as JellyfinBridgeApplication).container }

    private val viewModel: MainViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                MainViewModel(container) as T
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            JellyfinBridgeTheme {
                val state by viewModel.state.collectAsState()
                // Request the local-network (+ notifications) permissions before scanning/playing, then
                // scan. Browsing the library never needs these; only TV playback does, so it is deferred.
                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions(),
                ) {
                    val granted = container.permissions.hasLocalNetworkAccess()
                    viewModel.onLocalNetworkPermissionResult(granted)
                    if (granted) viewModel.scanTargets()
                }
                AppRoot(
                    state = state,
                    viewModel = viewModel,
                    onScanDevices = { permissionLauncher.launch(AndroidNetworkPermissionManager.PLAYBACK_PERMISSIONS) },
                )
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Persist the redacted event log when leaving the app, so a report shared later (after the
        // process is killed in the background) still contains this session's playback/TV events.
        runCatching { container.flushDiagnostics() }
    }
}
