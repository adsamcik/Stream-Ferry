package com.adsamcik.streamferry.ui.screens

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.os.CancellationSignal
import android.util.Size
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w393dp-h851dp-mdpi")
class LocalThumbnailTest {
    @get:Rule val compose = createComposeRule()

    @Test fun thumbnailDecodeTracksTheMeasuredPosterSlot() {
        val requests = CopyOnWriteArrayList<Size>()
        val releaseFirst = CountDownLatch(1)
        val signals = CopyOnWriteArrayList<CancellationSignal>()
        val resolver = mockk<ContentResolver>()
        every { resolver.loadThumbnail(any(), any(), any()) } answers {
            val size = secondArg<Size>()
            val signal = thirdArg<CancellationSignal>()
            signals += signal
            requests += size
            if (requests.size == 1) {
                signal.setOnCancelListener { releaseFirst.countDown() }
                check(releaseFirst.await(10, TimeUnit.SECONDS))
                signal.throwIfCanceled()
            }
            Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
        }
        val context = mockk<Context>()
        every { context.contentResolver } returns resolver
        val width = mutableStateOf(80.dp)
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.size(width.value, 120.dp)) {
                        LocalThumbnail("content://thumbnail-test/slot", Modifier.fillMaxSize())
                    }
                }
            }
        }
        try {
            compose.waitUntil(5000) { requests.isNotEmpty() }
            assertEquals(Size(96, 128), requests.first())
            compose.runOnIdle { width.value = 160.dp }
            compose.waitForIdle()
            compose.waitUntil(5000) { requests.size >= 2 }
            assertEquals(Size(160, 128), requests.last())
            assertTrue(signals.first().isCanceled)
        } finally { releaseFirst.countDown() }
    }

    @Test fun decodedCacheEvictsByBytesWithoutRecyclingVisibleBitmaps() {
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val cache = LocalThumbnailMemoryCache(bitmap.allocationByteCount * 2)
        val first = LocalThumbnailKey("one", 4, 4)
        val second = first.copy(uri = "two")
        val third = first.copy(uri = "three")
        cache.put(first, bitmap)
        cache.put(second, Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888))
        assertNotNull(cache.get(first))
        cache.put(third, Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888))
        assertNull(cache.get(second))
        assertNotNull(cache.get(first))
        assertFalse(bitmap.isRecycled)
        assertEquals(bitmap.allocationByteCount * 2, cache.sizeBytes)
        cache.put(first.copy(uri = "oversized"), Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888))
        assertNotNull(cache.get(first))
        assertNull(cache.get(first.copy(width = 8)))
    }

    @Test fun sizeWaitsForLayoutAndBoundsOversizedRequests() {
        assertNull(thumbnailSize(IntSize.Zero))
        assertEquals(Size(1024, 1024), thumbnailSize(IntSize(8000, 6000)))
    }
}
