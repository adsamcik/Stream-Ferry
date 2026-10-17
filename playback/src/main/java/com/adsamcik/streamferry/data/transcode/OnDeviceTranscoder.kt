package com.adsamcik.streamferry.data.transcode

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import androidx.media3.common.MediaItem
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Clock
import androidx.media3.effect.Presentation
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.DefaultDecoderFactory
import androidx.media3.transformer.ExoPlayerAssetLoader
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import androidx.media3.muxer.Muxer
import com.adsamcik.streamferry.core.transcode.TranscodeTarget
import com.adsamcik.streamferry.core.stream.MediaProfile
import com.adsamcik.streamferry.core.transcode.VideoCodec
import com.adsamcik.streamferry.source.api.DiagnosticSink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Hardware-accelerated on-device transcoder built on AndroidX Media3 Transformer (Surface-to-Surface
 * decode→encode internally). A receiver-specific streaming muxer consumes encoded samples directly.
 *
 * Transformer must be created and started on a thread with a Looper, so a dedicated [HandlerThread] is
 * used; the Transformer is configured with that Looper.
 */
@SuppressLint("UnsafeOptInUsageError")
class OnDeviceTranscoder(
    context: Context,
    private val logger: DiagnosticSink,
) {
    private val appContext = context.applicationContext
    private val thread = HandlerThread("ondevice-transcoder").apply { start() }
    private val handler = Handler(thread.looper)

    /** The export currently reserved or running, so cancellation also covers the pre-start window. */
    @Volatile private var inFlight: InFlight? = null
    @Volatile private var released = false

    private class InFlight(
        val done: CompletableDeferred<Unit>,
        /** Identity token of the session that reserved this export, if any. */
        val owner: Any? = null,
        /** Lets a session that was released just before reservation prevent a late start. */
        val abortIf: (() -> Boolean)? = null,
        /** Completes only after Media3 has actually returned from the export lifecycle. */
        val stopped: CompletableDeferred<Unit> = CompletableDeferred(),
        @Volatile var transformer: Transformer? = null,
        @Volatile var cancelled: Boolean = false,
    )

    /** One continuous decode/encode lifecycle. The muxer publishes bytes while encoding, never a file. */
    suspend fun transcodeStream(
        sourceUri: String,
        target: TranscodeTarget,
        muxerFactory: Muxer.Factory,
        startMs: Long = 0,
        sourceMimeType: String? = null,
        sourceProfile: MediaProfile? = null,
        owner: Any? = null,
        abortIf: (() -> Boolean)? = null,
    ) {
        check(!released) { "on-device transcoder is released" }
        require(startMs >= 0) { "transcode start must not be negative" }
        require(target.videoCodec in setOf(VideoCodec.H264, VideoCodec.HEVC) && !target.tenBit) {
            "on-device output requires an 8-bit H.264 or HEVC target"
        }
        // Downloaded copies use absolute filesystem paths, whereas gallery/SAF items use URIs.
        val parsedSourceUri = Uri.parse(sourceUri)
        val localSourceUri = if (parsedSourceUri.scheme == null) Uri.fromFile(File(sourceUri)) else parsedSourceUri
        require(localSourceUri.scheme in setOf("content", "file", "http", "https")) {
            "on-device transcoding requires readable media"
        }

        // Probe before reserving: failures must not strand the process-wide encoder reservation.
        val profile = sourceProfile ?: if (localSourceUri.scheme in setOf("content", "file")) {
            LocalMediaProbe.probe(appContext, sourceUri, "mp4")
        } else null

        val done = CompletableDeferred<Unit>()
        val flight = InFlight(done, owner, abortIf)
        synchronized(this) {
            check(!released) { "on-device transcoder is released" }
            // The process owns one HW export pipeline. Failing a second concurrent owner is safer than
            // overwriting its cancellation handle and leaving a Transformer orphaned.
            check(inFlight == null) { "another on-device export is already active" }
            inFlight = flight
        }

        val codec = codecLabel(target.videoCodec)
        val sourceHeight = if (profile?.rotationDegrees?.mod(180) == 90) {
            profile.widthPx
        } else {
            profile?.heightPx
        }
        val maxHeight = sourceHeight?.takeIf { it > 0 }
            ?.coerceAtMost(target.maxResolution.maxHeightPx) ?: target.maxResolution.maxHeightPx
        val startedAt = System.currentTimeMillis()
        logger.trace(
            TAG,
            "HW stream transcode start: $codec ${maxHeight}p from ${startMs}ms",
        )
        val posted = runCatching {
            handler.post {
                if (flight.cancelled || shouldAbort(flight)) {
                    completeFailure(flight, CancellationException("transcode cancelled before start"))
                    return@post
                }
                try {
                    val mediaItemBuilder = MediaItem.Builder()
                        .setUri(localSourceUri)
                    sourceMimeType?.let { mediaItemBuilder.setMimeType(it) }
                    if (startMs > 0) {
                        val clipping = MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(startMs)
                            // Progressive extractors may have no seek map (for example unknown-length
                            // TS). Let Media3 read forward to the clip rather than reject its timeline.
                            .setAllowUnseekableMedia(true)
                        mediaItemBuilder.setClippingConfiguration(clipping.build())
                    }
                    val mediaItem = mediaItemBuilder.build()
                    // Cap the OUTPUT height to the negotiated tier (preserving aspect ratio). Without this
                    // the encoder is handed the source's full resolution, which frequently makes a 4K
                    // source exceed a phone hardware encoder's practical throughput.
                    val edited = EditedMediaItem.Builder(mediaItem)
                        .setEffects(Effects(emptyList(), listOf(Presentation.createForHeight(maxHeight))))
                        .build()
                    val builder = Transformer.Builder(appContext)
                        .setVideoMimeType(videoMimeFor(target.videoCodec))
                        .setAudioMimeType(MimeTypes.AUDIO_AAC)
                        // Waiting for the receiver is intentional after the bounded output buffer fills.
                        .setMaxDelayBetweenMuxerSamplesMs(C.TIME_UNSET)
                        // Negotiation already admitted this output. Fail rather than silently change
                        // a user's codec/resolution choice when a device cannot actually encode it.
                        .setEncoderFactory(
                            DefaultEncoderFactory.Builder(appContext)
                                .setEnableFallback(false)
                                .setEnableFormatFallback(false)
                                .setRequestedVideoEncoderSettings(
                                    VideoEncoderSettings.Builder().setMaxBFrames(0).setiFrameIntervalSeconds(2f).build(),
                                )
                                .build(),
                        )
                        .setMuxerFactory(muxerFactory)
                        .setLooper(thread.looper)
                    if (localSourceUri.scheme in setOf("http", "https")) {
                        builder.setAssetLoaderFactory(
                            ExoPlayerAssetLoader.Factory(
                                appContext, DefaultDecoderFactory.Builder(appContext).build(), Clock.DEFAULT,
                                DefaultMediaSourceFactory(appContext),
                            ),
                        )
                    }
                    val transformer = builder
                        .addListener(object : Transformer.Listener {
                            override fun onCompleted(composition: Composition, result: ExportResult) {
                                logger.trace(
                                    TAG,
                                    "HW stream transcode done: $codec in ${System.currentTimeMillis() - startedAt}ms",
                                )
                                completeSuccess(flight)
                            }

                            override fun onError(composition: Composition, result: ExportResult, exception: ExportException) {
                                logger.w(
                                    TAG,
                                    "HW stream transcode failed: $codec errorCode=${exception.errorCode}",
                                    exception,
                                )
                                completeFailure(flight, exception)
                            }
                        })
                        .build()
                    flight.transformer = transformer
                    if (flight.cancelled || shouldAbort(flight)) {
                        runCatching { transformer.cancel() }
                        completeFailure(flight, CancellationException("transcode cancelled before start"))
                    } else {
                        // A shared SDR output contract works for HDR sources too. For an unknown
                        // remote profile Media3 discovers the transfer characteristics during decode.
                        // Media3's start API requires an output path, but the custom muxer ignores it.
                        // Neither streaming muxer creates or writes a file at this path.
                        val unusedOutputPath = File(appContext.cacheDir, "unused-stream-output").absolutePath
                        if (profile == null || profile.isHdr) {
                            val composition = Composition.Builder(EditedMediaItemSequence.Builder(edited).build())
                                .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
                                .build()
                            transformer.start(composition, unusedOutputPath)
                        } else {
                            transformer.start(edited, unusedOutputPath)
                        }
                    }
                } catch (t: Throwable) {
                    logger.w(TAG, "HW stream transcode setup failed: $codec", t)
                    completeFailure(flight, t)
                }
            }
        }.getOrDefault(false)
        if (!posted) {
            completeFailure(flight, IllegalStateException("on-device transcoder looper is unavailable"))
        }

        try {
            done.await()
        } catch (e: CancellationException) {
            if (!done.isCompleted) cancel(flight, "transcode caller cancelled")
            withContext(NonCancellable) { awaitStopped(flight, CANCEL_DRAIN_WAIT_MS) }
            throw e
        }
    }

    /** Abort the active export (including a queued-but-not-yet-started one). */
    fun cancelInFlight() {
        inFlight?.let { cancel(it, "transcode cancelled on stop") }
    }

    /** Abort the active export and wait a finite amount of time for its lifecycle to unwind. */
    fun cancelInFlightAndAwait(timeoutMs: Long = RELEASE_WAIT_MS): Boolean {
        val flight = inFlight ?: return true
        cancel(flight, "transcode cancelled on stop")
        return awaitStopped(flight, timeoutMs)
    }

    /**
     * Cancel and drain an export only when [owner] still owns the active reservation.
     *
     * A playback session may be finishing while a newer session has already started. Global cancellation
     * from stale cleanup would tear down that newer export; identity ownership keeps the two lifecycles
     * isolated. A missing/mismatched reservation means this owner has no writer left to drain.
     */
    fun cancelOwnedInFlightAndAwait(owner: Any, timeoutMs: Long = RELEASE_WAIT_MS): Boolean {
        val flight = inFlight?.takeIf { it.owner === owner } ?: return true
        cancel(flight, "transcode cancelled on owning session stop")
        return awaitStopped(flight, timeoutMs)
    }

    fun release() {
        if (released) return
        released = true
        if (!cancelInFlightAndAwait()) {
            logger.w(TAG, "Timed out waiting for the active on-device export to stop")
        }
        runCatching { thread.quitSafely() }
    }

    private fun cancel(flight: InFlight, reason: String) {
        flight.cancelled = true
        val posted = runCatching {
            handler.post {
                val transformer = flight.transformer
                if (transformer == null) {
                    // The start runnable either has not run (and observed cancelled) or could not create
                    // a Transformer. In both cases there is no native export left to drain.
                    completeFailure(flight, CancellationException(reason))
                } else {
                    runCatching { transformer.cancel() }
                        .onSuccess {
                            // Media3 cancellation returns after its export is released and does not
                            // dispatch onCompleted/onError. Waiting for a callback strands the encoder
                            // reservation, preventing the next conversion after a quality change/Stop.
                            completeFailure(flight, CancellationException(reason))
                        }
                        .onFailure {
                            logger.w(TAG, "Failed to cancel on-device export; encoder remains reserved", it)
                            flight.done.completeExceptionally(CancellationException(reason))
                        }
                }
            }
        }.getOrDefault(false)
        if (!posted) {
            completeFailure(flight, CancellationException(reason))
        }
    }

    private fun shouldAbort(flight: InFlight): Boolean =
        runCatching { flight.abortIf?.invoke() == true }.getOrDefault(true)

    private fun completeSuccess(flight: InFlight) {
        clearInFlight(flight)
        flight.stopped.complete(Unit)
        flight.done.complete(Unit)
    }

    private fun completeFailure(flight: InFlight, error: Throwable) {
        clearInFlight(flight)
        flight.stopped.complete(Unit)
        flight.done.completeExceptionally(error)
    }

    private fun awaitStopped(flight: InFlight, timeoutMs: Long): Boolean = runBlocking {
        withTimeoutOrNull(timeoutMs) {
            // Do not swallow TimeoutCancellationException here: callers use false to retain a
            // working file/cache that Media3 may still be writing.
            flight.stopped.await()
            true
        } ?: false
    }

    private fun clearInFlight(flight: InFlight) {
        synchronized(this) {
            if (inFlight === flight) inFlight = null
        }
    }

    private fun codecLabel(codec: VideoCodec): String = when (codec) {
        VideoCodec.H264 -> "h264"
        VideoCodec.HEVC -> "hevc"
        VideoCodec.VP9, VideoCodec.AV1 -> error("unsupported by the on-device transcoder")
    }

    private fun videoMimeFor(codec: VideoCodec): String = when (codec) {
        VideoCodec.H264 -> MimeTypes.VIDEO_H264
        VideoCodec.HEVC -> MimeTypes.VIDEO_H265
        VideoCodec.VP9, VideoCodec.AV1 -> error("unsupported by the on-device transcoder")
    }

    companion object {
        private const val TAG = "OnDeviceTranscoder"
        private const val CANCEL_DRAIN_WAIT_MS = 5_000L
        private const val RELEASE_WAIT_MS = 5_000L
    }
}
