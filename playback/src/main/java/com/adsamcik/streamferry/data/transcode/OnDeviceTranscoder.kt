package com.adsamcik.streamferry.data.transcode

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.InAppFragmentedMp4Muxer
import androidx.media3.transformer.Transformer
import com.adsamcik.streamferry.core.transcode.TranscodeTarget
import com.adsamcik.streamferry.core.transcode.VideoCodec
import com.adsamcik.streamferry.source.api.DiagnosticSink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * Hardware-accelerated on-device transcoder built on AndroidX Media3 Transformer (Surface-to-Surface
 * decode→encode internally). Transcodes a clip `[startMs, endMs)` of a source into a **fragmented MP4**
 * (CMAF) file using the device's HW encoder, per a [TranscodeTarget].
 *
 * Transformer must be created and started on a thread with a Looper, so a dedicated [HandlerThread] is
 * used; the Transformer is configured with that Looper. The public surface exposes no Media3 types, so
 * the `@UnstableApi` opt-in stays confined here.
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

    /** Raised when an export did not complete inside its duration-derived deadline. */
    class ExportDeadlineExceededException(
        message: String,
        /** True only when the cancelled Media3 lifecycle had finished before this exception was raised. */
        val stopped: Boolean,
    ) : Exception(message)

    /** Transcode `[startMs, endMs)` of a local [sourceUri] into [outFile] as a fragmented MP4. */
    suspend fun transcodeSegment(
        sourceUri: String,
        startMs: Long,
        endMs: Long,
        target: TranscodeTarget,
        outFile: File,
        /** Opaque session identity used to cancel only this caller's export during teardown. */
        owner: Any? = null,
        /** Checked on the Transformer looper before start so release/reservation races do not start work. */
        abortIf: (() -> Boolean)? = null,
    ) {
        check(!released) { "on-device transcoder is released" }
        require(endMs > startMs) { "transcode segment must have a positive duration" }
        val localSourceUri = Uri.parse(sourceUri)
        require(localSourceUri.scheme == "content" || localSourceUri.scheme == "file") {
            "on-device transcoding accepts only local content:// or file:// sources"
        }

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
        val maxHeight = target.maxResolution.maxHeightPx
        val startedAt = System.currentTimeMillis()
        val deadlineMs = exportDeadlineMs(startMs, endMs)
        logger.trace(
            TAG,
            "HW transcode start: $codec ${maxHeight}p seg [${startMs}-${endMs}ms], deadline=${deadlineMs}ms",
        )
        val posted = runCatching {
            handler.post {
                if (flight.cancelled || shouldAbort(flight)) {
                    completeFailure(flight, CancellationException("transcode cancelled before start"))
                    return@post
                }
                try {
                    val clipping = MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(startMs)
                        .setEndPositionMs(endMs)
                        .build()
                    val mediaItem = MediaItem.Builder()
                        .setUri(localSourceUri)
                        .setClippingConfiguration(clipping)
                        .build()
                    // Cap the OUTPUT height to the negotiated tier (preserving aspect ratio). Without this
                    // the encoder is handed the source's full resolution, which frequently makes a 4K
                    // source exceed a phone hardware encoder's practical throughput.
                    val edited = EditedMediaItem.Builder(mediaItem)
                        .setEffects(Effects(emptyList(), listOf(Presentation.createForHeight(maxHeight))))
                        .build()
                    val builder = Transformer.Builder(appContext)
                        .setVideoMimeType(videoMimeFor(target.videoCodec))
                        .setAudioMimeType(MimeTypes.AUDIO_AAC)
                        .setMuxerFactory(InAppFragmentedMp4Muxer.Factory())
                        .setLooper(thread.looper)
                    val transformer = builder
                        .addListener(object : Transformer.Listener {
                            override fun onCompleted(composition: Composition, result: ExportResult) {
                                logger.trace(
                                    TAG,
                                    "HW transcode done: $codec seg [${startMs}-${endMs}ms] " +
                                        "in ${System.currentTimeMillis() - startedAt}ms (${outFile.length() / 1024} KiB)",
                                )
                                completeSuccess(flight)
                            }

                            override fun onError(composition: Composition, result: ExportResult, exception: ExportException) {
                                logger.w(
                                    TAG,
                                    "HW transcode failed: $codec seg [${startMs}-${endMs}ms] errorCode=${exception.errorCode}",
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
                        transformer.start(edited, outFile.absolutePath)
                    }
                } catch (t: Throwable) {
                    logger.w(TAG, "HW transcode setup failed: $codec seg [${startMs}-${endMs}ms]", t)
                    completeFailure(flight, t)
                }
            }
        }.getOrDefault(false)
        if (!posted) {
            completeFailure(flight, IllegalStateException("on-device transcoder looper is unavailable"))
        }

        try {
            withTimeout(deadlineMs) { done.await() }
        } catch (e: TimeoutCancellationException) {
            cancel(flight, "transcode deadline exceeded")
            val stopped = awaitStopped(flight, CANCEL_DRAIN_WAIT_MS)
            if (!stopped) {
                // Keep the reservation: a late Media3 callback must never overlap a new hardware export.
                logger.w(TAG, "Timed out waiting for cancelled $codec export to terminate; encoder remains reserved")
            }
            throw ExportDeadlineExceededException(
                "on-device $codec export exceeded its ${deadlineMs}ms deadline for [${startMs}-${endMs}ms]",
                stopped = stopped,
            )
        } catch (e: CancellationException) {
            if (!done.isCompleted) cancel(flight, "transcode caller cancelled")
            throw e
        }
    }

    /** Abort the active export (including a queued-but-not-yet-started one). */
    fun cancelInFlight() {
        inFlight?.let { cancel(it, "transcode cancelled on stop") }
    }

    /**
     * Abort the active export and wait only a finite amount of time for its callback/unwind. This is used
     * before a session removes its temporary directory, preventing the muxer from writing into deleted
     * storage indefinitely.
     */
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
                    // Unblock the HTTP caller now, but leave [stopped] and the reservation for Media3's
                    // completion callback so a late native export cannot overlap the next request.
                    flight.done.completeExceptionally(CancellationException(reason))
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
        flight.done.complete(Unit)
        flight.stopped.complete(Unit)
        clearInFlight(flight)
    }

    private fun completeFailure(flight: InFlight, error: Throwable) {
        flight.done.completeExceptionally(error)
        flight.stopped.complete(Unit)
        clearInFlight(flight)
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

    private fun exportDeadlineMs(startMs: Long, endMs: Long): Long {
        val durationMs = (endMs - startMs).coerceAtLeast(MIN_SEGMENT_DURATION_MS)
        return (EXPORT_STARTUP_GRACE_MS + durationMs * EXPORT_TIME_MULTIPLIER)
            .coerceAtMost(MAX_EXPORT_DEADLINE_MS)
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
        private const val MIN_SEGMENT_DURATION_MS = 1_000L
        private const val EXPORT_STARTUP_GRACE_MS = 10_000L
        private const val EXPORT_TIME_MULTIPLIER = 10L
        private const val MAX_EXPORT_DEADLINE_MS = 90_000L
        private const val CANCEL_DRAIN_WAIT_MS = 5_000L
        private const val RELEASE_WAIT_MS = 5_000L
    }
}
