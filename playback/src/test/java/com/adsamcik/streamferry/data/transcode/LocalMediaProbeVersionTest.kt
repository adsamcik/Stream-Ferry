package com.adsamcik.streamferry.data.transcode

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class LocalMediaProbeVersionTest {
    @Test fun `file replacement changes its version and missing files cannot reuse probes`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val file = File.createTempFile("probe-version-", ".mp4")
        try {
            file.writeBytes(byteArrayOf(1, 2, 3))
            val first = LocalMediaProbe.inputVersion(context, file.path)
            assertEquals(first, LocalMediaProbe.inputVersion(context, file.toURI().toString()))
            assertEquals(first, LocalMediaProbe.inputVersion(context, file.toURI().toString().replaceFirst("file:/", "file:///")))
            assertEquals(first, LocalMediaProbe.inputVersion(context, file.toURI().toString().replaceFirst("file:", "FILE:")))
            file.appendBytes(byteArrayOf(4))
            assertNotEquals(first, LocalMediaProbe.inputVersion(context, file.path))
            file.delete()
            assertNull(LocalMediaProbe.inputVersion(context, file.path))
        } finally { file.delete() }
    }

    @Test fun `provider cache versions require modification metadata and lose validity when permission fails`() = runBlocking {
        var readable = true
        var modified: Long? = 20
        registerProvider {
            check(readable) { "Permission revoked" }
            MatrixCursor(arrayOf("_size", "last_modified")).apply { addRow(arrayOf(100L, modified)) }
        }
        val uri = "content://probe-version/video"
        val context = RuntimeEnvironment.getApplication()
        assertEquals(LocalMediaProbe.InputVersion(100, 20), LocalMediaProbe.inputVersion(context, uri))
        modified = 21
        assertEquals(LocalMediaProbe.InputVersion(100, 21), LocalMediaProbe.inputVersion(context, uri))
        modified = null
        assertNull(LocalMediaProbe.inputVersion(context, uri))
        readable = false
        assertNull(LocalMediaProbe.inputVersion(context, uri))
    }

    @Test fun `cancelling a version check interrupts a blocked provider query`() = runBlocking {
        val entered = CountDownLatch(1)
        val interrupted = CountDownLatch(1)
        val release = CountDownLatch(1)
        registerProvider {
            entered.countDown()
            try { release.await(5, TimeUnit.SECONDS) }
            catch (_: InterruptedException) { interrupted.countDown() }
            null
        }
        val job = launch(Dispatchers.Default) {
            LocalMediaProbe.inputVersion(RuntimeEnvironment.getApplication(), "content://probe-version/video")
        }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            withTimeout(2000) { job.cancelAndJoin() }
            assertTrue(interrupted.await(2, TimeUnit.SECONDS))
        } finally { release.countDown(); job.cancelAndJoin() }
    }

    private fun registerProvider(query: () -> Cursor?) {
        val provider = object : ContentProvider() {
            override fun onCreate() = true
            override fun getType(uri: Uri) = "video/mp4"
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = query()
            override fun insert(uri: Uri, values: ContentValues?): Uri? = null
            override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
            override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
        }
        provider.attachInfo(RuntimeEnvironment.getApplication(), ProviderInfo().apply {
            authority = "probe-version"
            exported = true
        })
        ShadowContentResolver.registerProviderInternal("probe-version", provider)
    }
}
