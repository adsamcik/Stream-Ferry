package com.adsamcik.streamferry.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.adsamcik.streamferry.BuildConfig
import com.adsamcik.streamferry.diagnostics.CrashReporter
import com.adsamcik.streamferry.logging.DiagnosticsLogger
import com.adsamcik.streamferry.logging.RedactingLogger
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

    override fun onCreate() {
        super.onCreate()
        // The debug-only ":crash" process only hosts CrashActivity (which reads the report file
        // directly), so skip the full app init there.
        if (getProcessName().endsWith(":crash")) return
        // Install the crash handler FIRST, before any other init, so even a failure while building
        // the container is captured to a redacted, app-private crash report.
        crashReporter = CrashReporter(
            context = this,
            appVersion = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE.toString(),
            isDebug = BuildConfig.DEBUG,
        )
        crashReporter.install()
        logger = RedactingLogger(debugBuild = BuildConfig.DEBUG)
        container = AppContainer(this, logger, crashReporter)
    }

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
