package com.adsamcik.streamferry.app

import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.fragment.app.FragmentActivity
import com.adsamcik.streamferry.permissions.AndroidNetworkPermissionManager
import com.adsamcik.streamferry.ui.AppRoot
import com.adsamcik.streamferry.ui.MainViewModel
import com.adsamcik.streamferry.ui.theme.StreamFerryTheme

/**
 * The AndroidX media-route chooser is implemented with DialogFragments, so the app's host must expose
 * a FragmentManager. FragmentActivity remains a ComponentActivity and is fully compatible with Compose.
 */
class MainActivity : FragmentActivity() {

    private val container by lazy { (application as StreamFerryApplication).container }

    private val viewModel: MainViewModel by viewModels {
        viewModelFactory {
            initializer {
                MainViewModel(
                    container = container,
                    savedStateHandle = createSavedStateHandle(),
                )
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        container.initializeCastContext()
        viewModel.onLocalNetworkPermissionResult(container.permissions.hasLocalNetworkAccess())
        setContent {
            val state by viewModel.state.collectAsStateWithLifecycle()
            StreamFerryTheme(themeMode = state.themeMode) {
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
        // The battery-optimization request is handled by a system activity. Re-read its result when
        // control returns so the Settings toggle immediately reflects the user's choice.
        viewModel.refreshBackgroundPlaybackStatus()
    }

    override fun onStop() {
        super.onStop()
        // Persist the redacted event log when leaving the app, so a report shared later (after the
        // process is killed in the background) still contains this session's playback/TV events.
        runCatching { container.flushDiagnostics() }
        container.checkpointSmartResume()
    }
}
