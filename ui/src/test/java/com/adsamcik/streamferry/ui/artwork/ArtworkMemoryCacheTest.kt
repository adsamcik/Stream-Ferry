package com.adsamcik.streamferry.ui.artwork

import android.graphics.Bitmap
import coil.ImageLoader
import coil.decode.DataSource
import coil.memory.MemoryCache
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.adsamcik.streamferry.source.api.*
import io.mockk.*
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class ArtworkMemoryCacheTest {
    @Before fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun tearDown() { Dispatchers.resetMain(); unmockkAll() }

    private class Fixture {
        val context = RuntimeEnvironment.getApplication()
        val keyer = ArtworkRefKeyer()
        val first = SourceInstanceId(SourceProviderId("test"), "account-a")
        val second = first.copy(value = "account-b")
        val artwork = mockk<ArtworkProvider>()
        var fetches = 0
        private val png = ByteArrayOutputStream().also {
            Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it)
        }.toByteArray()
        val loader: ImageLoader

        init {
            coEvery { artwork.open(any()) } answers { fetches++; response() }
            val registry = SourceRegistry(listOf(first, second).map { source ->
                mockk<SourceBackend>(relaxed = true).also {
                    every { it.identity } returns SourceInstance(source, "Test source")
                    every { it.artwork } returns artwork
                }
            })
            loader = ImageLoader.Builder(context)
                .components { add(keyer); add(ArtworkRefFetcher.Factory { registry }) }
                .memoryCache { MemoryCache.Builder(context).maxSizeBytes(128 * 1024).build() }
                .diskCachePolicy(CachePolicy.DISABLED)
                .build()
        }

        fun response() = Result.success(ArtworkResponse("image/png", png.size.toLong(), png.inputStream()))
        suspend fun load(ref: ArtworkRef) = assertIs<SuccessResult>(loader.execute(
            ImageRequest.Builder(context).data(ref).size(32).allowHardware(false).build(),
        ))
    }

    @Test fun repeatedArtworkUsesMemoryButAccountsVersionsAndLogoutRemainIsolated() = runTest {
        val f = Fixture()
        try {
            val ref = ArtworkRef(f.first, "poster:movie:version-1")
            f.load(ref)
            assertEquals(DataSource.MEMORY_CACHE, f.load(ref).dataSource)
            assertEquals(1, f.fetches)
            f.load(ref.copy(opaqueId = "poster:movie:version-2"))
            f.load(ref.copy(source = f.second))
            assertEquals(3, f.fetches)
            f.keyer.invalidate()
            f.loader.memoryCache?.clear()
            assertEquals(DataSource.NETWORK, f.load(ref).dataSource)
            assertEquals(4, f.fetches)
        } finally { f.loader.shutdown() }
    }

    @Test fun requestFinishingAfterLogoutCannotRepopulateTheCurrentGeneration() = runTest {
        val f = Fixture()
        try {
            val started = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            coEvery { f.artwork.open(any()) } coAnswers {
                f.fetches++
                if (f.fetches == 1) { started.complete(Unit); finish.await() }
                f.response()
            }
            val ref = ArtworkRef(f.first, "poster:movie:version-1")
            val oldRequest = async { f.load(ref) }
            started.await()
            f.keyer.invalidate()
            f.loader.memoryCache?.clear()
            finish.complete(Unit)
            oldRequest.await()
            assertEquals(DataSource.NETWORK, f.load(ref).dataSource)
            assertEquals(2, f.fetches)
        } finally { f.loader.shutdown() }
    }
}
