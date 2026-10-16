package com.adsamcik.streamferry.data.transcode

import com.adsamcik.streamferry.core.stream.MediaProfile
import com.adsamcik.streamferry.core.transcode.TranscodeTarget
import com.adsamcik.streamferry.source.api.DiagnosticSink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

/** One complete MP4 export, retained only for the lifetime of the receiver's local-file stream. */
class PreparedTranscodeFileSession(
    private val transcoder: OnDeviceTranscoder,
    private val sourceUri: String,
    private val target: TranscodeTarget,
    cacheDir: File,
    private val logger: DiagnosticSink,
    private val sourceMimeType: String? = null,
    private val runtimeSeconds: Double? = null,
    private val sourceProfile: MediaProfile? = null,
) {
    private val workDir = File(cacheDir, "prepared-${UUID.randomUUID()}")
    val file = File(workDir, "video.mp4")
    private val owner = Any()
    @Volatile private var released = false

    /** Only a completed file is returned; receivers never see a partially written MP4. */
    suspend fun prepare(onProgress: (Int?) -> Unit = {}): File = withContext(Dispatchers.IO) {
        check(!released) { "on-device output session is released" }
        if (!workDir.mkdirs() && !workDir.isDirectory) throw IOException("Cannot create conversion cache")
        if (workDir.usableSpace < MIN_FREE_WORKING_SPACE_BYTES) {
            release()
            throw IOException("Not enough free space to prepare the converted video")
        }
        try {
            transcoder.transcodeFile(
                sourceUri, target, file, sourceMimeType, runtimeSeconds,
                owner = owner, abortIf = { released }, onProgress = onProgress, sourceProfile = sourceProfile,
            )
            if (released) throw CancellationException("on-device output session stopped")
            if (!file.isFile || file.length() == 0L) throw IOException("The video conversion produced no playable file")
            onProgress(100)
            file
        } catch (error: Throwable) {
            withContext(NonCancellable) { release() }
            throw error
        }
    }

    /** Call after unregistering the served file; stale sessions cannot cancel a newer export. */
    fun release() {
        released = true
        if (transcoder.cancelOwnedInFlightAndAwait(owner)) {
            workDir.deleteRecursively()
        } else {
            // The native muxer may still own this path. Preserve it until it actually stops.
            logger.w(TAG, "Conversion cache retained because its encoder did not finish stopping")
        }
    }

    companion object {
        private const val TAG = "PreparedTranscodeFile"
        private const val MIN_FREE_WORKING_SPACE_BYTES = 64 * 1024 * 1024L
    }
}
