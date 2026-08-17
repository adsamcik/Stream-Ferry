package com.adsamcik.streamferry.diagnostics

import android.content.Intent
import android.net.Uri
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReportShareTest {

    @Test fun reportIsSharedAsAReadGrantedFileInsteadOfAnIntentExtra() {
        val uri = Uri.parse("content://com.adsamcik.streamferry.reportshare/shared-reports/report.txt")
        val intent = ReportShare.createSendIntent(uri, "Stream Ferry diagnostics report")

        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("text/plain", intent.type)
        assertEquals("Stream Ferry diagnostics report", intent.getStringExtra(Intent.EXTRA_SUBJECT))
        assertNull(intent.getStringExtra(Intent.EXTRA_TEXT))
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)

        @Suppress("DEPRECATION")
        val streamUri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
        assertEquals("com.adsamcik.streamferry.reportshare", streamUri.authority)
        assertEquals(uri, streamUri)
        assertEquals(uri, intent.clipData!!.getItemAt(0).uri)
        assertFalse(intent.extras!!.containsKey(Intent.EXTRA_TEXT))
    }

    @Test fun clearCachedReportsDeletesEveryExport() {
        val context = RuntimeEnvironment.getApplication()
        val directory = File(context.cacheDir, "shared-reports").apply { mkdirs() }
        File(directory, "stream-ferry-report-one.txt").writeText("one")
        File(directory, "stream-ferry-report-two.txt").writeText("two")

        ReportShare.clearCachedReports(context)

        assertFalse(directory.exists())
    }
}
