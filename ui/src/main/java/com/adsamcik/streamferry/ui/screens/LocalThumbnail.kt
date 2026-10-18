package com.adsamcik.streamferry.ui.screens

import android.graphics.Bitmap
import android.net.Uri
import android.os.CancellationSignal
import android.util.LruCache
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class LocalThumbnailKey(val uri: String, val width: Int, val height: Int)

/** Eviction releases only our reference; a bitmap may still be displayed by another card. */
internal class LocalThumbnailMemoryCache(private val maxBytes: Int = 12 * 1024 * 1024) {
    private val cache = object : LruCache<LocalThumbnailKey, Bitmap>(maxBytes) {
        override fun sizeOf(key: LocalThumbnailKey, value: Bitmap): Int = value.allocationByteCount
    }

    fun get(key: LocalThumbnailKey): Bitmap? = cache.get(key)
    fun put(key: LocalThumbnailKey, bitmap: Bitmap) {
        if (bitmap.allocationByteCount <= maxBytes) cache.put(key, bitmap)
    }
    internal val sizeBytes: Int get() = cache.size()
}

private val localThumbnails = LocalThumbnailMemoryCache()

internal fun thumbnailSize(size: IntSize): Size? {
    if (size.width <= 0 || size.height <= 0) return null
    // Round tiny layout differences to the same request, with a bound for unusually large slots.
    fun dimension(value: Int): Int = ((value.coerceAtMost(1024) + 31) / 32 * 32).coerceAtLeast(32)
    return Size(dimension(size.width), dimension(size.height))
}

/** Decode for the actual poster slot; loading/failure leaves the existing initials visible. */
@Composable
internal fun LocalThumbnail(uriString: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var measuredSize by remember { mutableStateOf(IntSize.Zero) }
    val size = thumbnailSize(measuredSize)
    val key = size?.let { LocalThumbnailKey(uriString, it.width, it.height) }
    val bitmap by produceState<Bitmap?>(null, key, context.contentResolver) {
        val cached = key?.let(localThumbnails::get)
        value = cached
        if (key == null || cached != null) return@produceState
        val signal = CancellationSignal()
        val load = launch {
            val loaded = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.loadThumbnail(Uri.parse(key.uri), Size(key.width, key.height), signal)
                }.getOrNull()
            }
            // Publish on the producer context only after cancellable IO has returned. A retired load
            // cannot replace the next URI/size's bitmap or make its producer skip the new request.
            loaded?.let { localThumbnails.put(key, it) }
            value = loaded
        }
        awaitDispose {
            signal.cancel()
            load.cancel()
        }
    }
    Box(modifier.onSizeChanged { measuredSize = it }) {
        bitmap?.let {
            Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
    }
}
