package com.adsamcik.streamferry.data.subtitle

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.media3.common.text.Cue
import androidx.media3.common.util.Size
import androidx.media3.effect.BitmapOverlay
import androidx.media3.ui.SubtitleView
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** Uses Media3's subtitle painter for text, styling, positioning and bitmap cues on either output. */
@SuppressLint("UnsafeOptInUsageError")
internal class SubtitleOverlay(
    private val context: Context,
    private val source: SubtitleCueSource,
    private val sourceStartUs: Long,
) : BitmapOverlay() {
    private val main = Handler(Looper.getMainLooper())
    private var size = Size(1, 1)
    private var lastCues: List<Cue>? = null
    private var bitmap: Bitmap? = null
    // The view is detached and all its creation/layout/drawing is confined to the main thread.
    private var view: SubtitleView? = null

    override fun configure(videoSize: Size) {
        super.configure(videoSize)
        size = videoSize
        lastCues = null
    }

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        val cues = source.cuesAt(sourceStartUs + presentationTimeUs)
        if (cues == lastCues && bitmap != null) return bitmap!!
        val render = FutureTask {
            val subtitleView = view ?: SubtitleView(context).also { view = it }
            subtitleView.setCues(cues)
            val width = View.MeasureSpec.makeMeasureSpec(size.width, View.MeasureSpec.EXACTLY)
            val height = View.MeasureSpec.makeMeasureSpec(size.height, View.MeasureSpec.EXACTLY)
            subtitleView.measure(width, height)
            subtitleView.layout(0, 0, size.width, size.height)
            Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888).also {
                subtitleView.draw(Canvas(it))
            }
        }
        if (Looper.myLooper() == main.looper) render.run() else check(main.post(render))
        val result = try { render.get(5, TimeUnit.SECONDS) } catch (error: Exception) {
            render.cancel(false)
            throw IllegalStateException("Couldn't render the selected subtitles.", error)
        }
        lastCues = cues.toList()
        bitmap = result
        return result
    }

    override fun release() {
        super.release()
        bitmap = null
        main.post { view = null }
    }
}
