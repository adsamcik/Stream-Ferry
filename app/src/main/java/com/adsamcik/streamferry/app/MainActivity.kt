package com.adsamcik.streamferry.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModelProvider
import com.adsamcik.streamferry.permissions.AndroidNetworkPermissionManager
import com.adsamcik.streamferry.ui.AppRoot
import com.adsamcik.streamferry.ui.MainViewModel
import com.adsamcik.streamferry.ui.theme.StreamFerryTheme

class MainActivity : ComponentActivity() {

    private val container by lazy { (application as StreamFerryApplication).container }

    private val viewModel: MainViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                MainViewModel(container) as T
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        container.initializeCastContext()
        viewModel.onLocalNetworkPermissionResult(container.permissions.hasLocalNetworkAccess())
        setContent {
            StreamFerryTheme {
                val state by viewModel.state.collectAsState()
                // Request the local-network (+ notifications) permissions before scanning/playing, then
                // scan. Browsing the library never needs these; only TV playback does, so it is deferred.
                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions(),
                ) {
                    val localNetworkGranted = container.permissions.hasLocalNetworkAccess()
                    if (localNetworkGranted) {
                        viewModel.onLocalNetworkPermissionResult(true)
                        viewModel.scanTargets()
                    } else {
                        viewModel.onLocalNetworkPermissionDenied()
                    }
                }
                AppRoot(
                    state = state,
                    viewModel = viewModel,
                    onScanDevices = {
                        if (container.permissions.hasLocalNetworkAccess()) viewModel.scanTargets()
                        else permissionLauncher.launch(AndroidNetworkPermissionManager.PLAYBACK_PERMISSIONS)
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        container.initializeCastContext()
        // Covers settings-driven revocation without treating a denial as an empty device list.
        viewModel.onLocalNetworkPermissionResult(container.permissions.hasLocalNetworkAccess())
    }

    override fun onStop() {
        super.onStop()
        // Persist the redacted event log when leaving the app, so a report shared later (after the
        // process is killed in the background) still contains this session's playback/TV events.
        runCatching { container.flushDiagnostics() }
    }
}
