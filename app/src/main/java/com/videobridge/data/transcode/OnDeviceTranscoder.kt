package com.videobridge.data.transcode

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Clock
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.effect.Presentation
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultDecoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExoPlayerAssetLoader
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.InAppFragmentedMp4Muxer
import androidx.media3.transformer.Transformer
import com.videobridge.core.transcode.TranscodeTarget
import com.videobridge.core.transcode.VideoCodec
import com.videobridge.logging.DiagnosticsLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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
    private val logger: DiagnosticsLogger,
) {
    private val appContext = context.applicationContext
    private val thread = HandlerThread("ondevice-transcoder").apply { start() }
    private val handler = Handler(thread.looper)

    /** The export currently running on the looper thread, so [cancelInFlight] can abort it on stop. */
    @Volatile private var inFlight: InFlight? = null

    private class InFlight(val transformer: Transformer, val done: CompletableDeferred<Unit>)

    /** Transcode `[startMs, endMs)` of [sourceUri] into [outFile] as a fragmented MP4. Suspends until done.
     *  [sourceHeaders] (e.g. an Authorization header for a Jellyfin origin) are attached to the source HTTP
     *  request only — they stay on the phone and never reach the TV. */
    suspend fun transcodeSegment(
        sourceUri: String,
        startMs: Long,
        endMs: Long,
        target: TranscodeTarget,
        outFile: File,
        sourceHeaders: Map<String, String>? = null,
    ) {
        val done = CompletableDeferred<Unit>()
        val codec = codecLabel(target.videoCodec)
        val maxHeight = target.maxResolution.maxHeightPx
        val startedAt = System.currentTimeMillis()
        logger.trace(TAG, "HW transcode start: $codec ${maxHeight}p seg [${startMs}-${endMs}ms]")
        handler.post {
            try {
                val clipping = MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(startMs)
                    .setEndPositionMs(endMs)
                    .build()
                val mediaItem = MediaItem.Builder()
                    .setUri(Uri.parse(sourceUri))
                    .setClippingConfiguration(clipping)
                    .build()
                // Cap the OUTPUT height to the negotiated tier (preserving aspect ratio). Without this the
                // encoder is handed the source's full resolution — a 4K source then exceeds what most phone
                // HW encoders can do and the export fails, so on-device transcoding of high-res files never
                // works. Presentation only scales down here because the tier is derived from the source.
                val edited = EditedMediaItem.Builder(mediaItem)
                    .setEffects(Effects(emptyList(), listOf(Presentation.createForHeight(maxHeight))))
                    .build()
                val videoMime = videoMimeFor(target.videoCodec)
                val builder = Transformer.Builder(appContext)
                    .setVideoMimeType(videoMime)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .setMuxerFactory(InAppFragmentedMp4Muxer.Factory())
                    .setLooper(thread.looper)
                // A remote (Jellyfin) origin needs its auth header on the source request; the header is set
                // on the input DataSource only, so it's never exposed to the TV. Local files need no headers.
                if (!sourceHeaders.isNullOrEmpty()) {
                    val dataSourceFactory = DefaultHttpDataSource.Factory().setDefaultRequestProperties(sourceHeaders)
                    val assetLoaderFactory = ExoPlayerAssetLoader.Factory(
                        appContext,
                        DefaultDecoderFactory.Builder(appContext).build(),
                        Clock.DEFAULT,
                        DefaultMediaSourceFactory(dataSourceFactory),
                    )
                    builder.setAssetLoaderFactory(assetLoaderFactory)
                }
                val transformer = builder
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, result: ExportResult) {
                            logger.trace(
                                TAG,
                                "HW transcode done: $codec seg [${startMs}-${endMs}ms] " +
                                    "in ${System.currentTimeMillis() - startedAt}ms (${outFile.length() / 1024} KiB)",
                            )
                            done.complete(Unit)
                        }

                        override fun onError(composition: Composition, result: ExportResult, exception: ExportException) {
                            // Always kept (not just under tracing): an on-device transcode failure is a
                            // top reason casting a local/incompatible file fails, so it must be diagnosable.
                            logger.w(TAG, "HW transcode failed: $codec seg [${startMs}-${endMs}ms] errorCode=${exception.errorCode}", exception)
                            done.completeExceptionally(exception)
                        }
                    })
                    .build()
                inFlight = InFlight(transformer, done)
                transformer.start(edited, outFile.absolutePath)
            } catch (t: Throwable) {
                logger.w(TAG, "HW transcode setup failed: $codec seg [${startMs}-${endMs}ms]", t)
                done.completeExceptionally(t)
            }
        }
        try {
            done.await()
        } finally {
            inFlight = null
        }
    }

    /**
     * Aborts the export currently in progress (if any) and unblocks its awaiting caller. Posted to the
     * transcoder's looper because Media3 [Transformer.cancel] must run on the thread the Transformer was
     * built on. Called on stop so the HW muxer stops cleanly *before* the session deletes its cache dir —
     * without this the muxer's output file is yanked mid-write and the export fails with muxing error 7001.
     */
    fun cancelInFlight() {
        val f = inFlight ?: return
        handler.post {
            runCatching { f.transformer.cancel() }
            f.done.completeExceptionally(CancellationException("transcode cancelled on stop"))
        }
    }

    fun release() {
        runCatching { thread.quitSafely() }
    }

    private fun codecLabel(codec: VideoCodec): String = when (codec) {
        VideoCodec.H264 -> "h264"
        VideoCodec.HEVC -> "hevc"
        VideoCodec.VP9 -> "vp9"
        VideoCodec.AV1 -> "av1"
    }

    private fun videoMimeFor(codec: VideoCodec): String = when (codec) {
        VideoCodec.H264 -> MimeTypes.VIDEO_H264
        VideoCodec.HEVC -> MimeTypes.VIDEO_H265
        VideoCodec.VP9 -> MimeTypes.VIDEO_VP9
        VideoCodec.AV1 -> MimeTypes.VIDEO_AV1
    }

    companion object {
        private const val TAG = "OnDeviceTranscoder"
    }
}
