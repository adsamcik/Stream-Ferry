package com.adsamcik.streamferry.data.transcode

import android.annotation.SuppressLint
import androidx.media3.common.util.Size
import androidx.media3.effect.GlMatrixTransformation
import kotlin.math.floor
import com.adsamcik.streamferry.core.transcode.ResolutionTier

/** Same landscape size used by hardware capability admission. */
internal val ResolutionTier.maxWidthPx: Int get() = when (this) {
    ResolutionTier.SD_480P -> 854
    ResolutionTier.HD_720P -> 1280
    ResolutionTier.FHD_1080P -> 1920
    ResolutionTier.UHD_4K -> 3840
}

/** Applied after Media3 has resolved rotation and non-square pixels into display dimensions. */
@SuppressLint("UnsafeOptInUsageError")
internal class EncoderEnvelopeEffect(private val maxWidth: Int, private val maxHeight: Int) : GlMatrixTransformation {
    private val identity = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        val (width, height) = fitEncoderEnvelope(inputWidth, inputHeight, maxWidth, maxHeight)
        // Even encoder rounding can change the aspect by a pixel. Letterbox that remainder
        // instead of stretching the picture, including non-square-pixel source frames.
        val inputAspect = inputWidth.toFloat() / inputHeight
        val outputAspect = width.toFloat() / height
        identity[0] = minOf(1f, inputAspect / outputAspect)
        identity[5] = minOf(1f, outputAspect / inputAspect)
        return Size(width, height)
    }

    override fun getGlMatrixArray(presentationTimeUs: Long): FloatArray = identity
}

/** Aspect fit, with no upscale; even sizes are needed by the admitted 4:2:0 encoders. */
internal fun fitEncoderEnvelope(width: Int, height: Int, maxWidth: Int, maxHeight: Int): Pair<Int, Int> {
    require(width >= 2 && height >= 2 && maxWidth >= 2 && maxHeight >= 2)
    val scale = minOf(1.0, maxWidth.toDouble() / width, maxHeight.toDouble() / height)
    fun even(value: Double) = (floor(value / 2).toInt() * 2).coerceAtLeast(2)
    return even(width * scale) to even(height * scale)
}
