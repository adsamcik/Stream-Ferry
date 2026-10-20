package com.adsamcik.streamferry.app

import android.app.Application
import android.content.Context
import dev.tracebox.Tracebox
import dev.tracebox.TraceboxConfiguration
import dev.tracebox.api.TraceboxPolicy
import dev.tracebox.api.LogLevel
import dev.tracebox.api.TraceboxHandle
import dev.tracebox.api.CaptureKind
import dev.tracebox.ui.compose.TraceboxDiagnosticsUi
import dev.tracebox.ui.compose.TraceboxDiagnosticsUiConfiguration
import dev.tracebox.ui.compose.TraceboxPackageActions
import dev.tracebox.ui.compose.TraceboxPrimaryAction
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.adsamcik.streamferry.BuildConfig
import com.adsamcik.streamferry.diagnostics.CrashReporter
import com.adsamcik.streamferry.logging.DiagnosticsLogger
import com.adsamcik.streamferry.logging.TraceboxDiagnosticsLogger
import com.adsamcik.streamferry.playback.proxy.PlaybackServiceDependencies
import com.adsamcik.streamferry.playback.proxy.PlaybackServiceOwner

/**
 * Application entry point. Owns the process-wide [DiagnosticsLogger] and the lightweight manual DI
 * container ([AppContainer]). No DI framework is used (dependency policy §14).
 */
class StreamFerryApplication : Application(), ImageLoaderFactory, PlaybackServiceOwner {

    lateinit var logger: DiagnosticsLogger
        private set

    lateinit var crashReporter: CrashReporter
        private set

    lateinit var container: AppContainer
        private set

    lateinit var tracebox: TraceboxHandle
        private set

    val diagnosticsUiConfiguration: TraceboxDiagnosticsUiConfiguration
        get() = TraceboxDiagnosticsUiConfiguration(
            showHeading = false,
            showCasualStatus = false,
            primaryAction = TraceboxPrimaryAction.REVIEW_ONLY,
            packageActions = TraceboxPackageActions(upload = false, deleteAllData = false),
            advancedControls = dev.tracebox.ui.compose.TraceboxAdvancedControls(
                initiallyExpanded = true,
                statusDetails = false,
                captureKinds = diagnosticsPolicy().captures,
            ),
            defaultPolicy = diagnosticsPolicy(),
        )

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        tracebox = Tracebox.install(this, TraceboxConfiguration.Builder()
            .setInitialPolicy(diagnosticsPolicy())
            .setPersistRequestedProfile(true)
            .build())
    }

    override fun onCreate() {
        super.onCreate()
        TraceboxDiagnosticsUi.configure(diagnosticsUiConfiguration)
        crashReporter = CrashReporter(tracebox, packageManager.getPackageInfo(packageName, 0).lastUpdateTime)
        logger = TraceboxDiagnosticsLogger(tracebox, BuildConfig.VERSION_CODE.toString())
        container = AppContainer(this, logger, crashReporter)
    }

    private fun diagnosticsPolicy() = TraceboxPolicy(
        minimumLogLevel = LogLevel.VERBOSE,
        mirrorToLogcat = BuildConfig.DEBUG,
        captures = setOf(CaptureKind.JVM_CRASH, CaptureKind.HANDLED_EXCEPTION, CaptureKind.ANR, CaptureKind.OS_EXIT),
    )

    /** Provide the container's auth-aware, memory-only image loader to Coil's singleton (§15 posters). */
    override fun newImageLoader(): ImageLoader =
        if (::container.isInitialized) container.imageLoader else ImageLoader.Builder(this).build()

    override fun playbackServiceDependencies(): PlaybackServiceDependencies? =
        if (::container.isInitialized && ::logger.isInitialized) {
            PlaybackServiceDependencies(container.mediaSessionController, logger)
        } else {
            null
        }
}
