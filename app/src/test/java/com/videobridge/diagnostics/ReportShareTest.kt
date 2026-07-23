package com.videobridge.diagnostics

import android.content.Intent
import android.net.Uri
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReportShareTest {

    @Test fun reportIsSharedAsAReadGrantedFileInsteadOfAnIntentExtra() {
        val uri = Uri.parse("content://com.videobridge.reportshare/shared-reports/report.txt")
        val intent = ReportShare.createSendIntent(uri, "Video Bridge diagnostics report")

        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("text/plain", intent.type)
        assertEquals("Video Bridge diagnostics report", intent.getStringExtra(Intent.EXTRA_SUBJECT))
        assertNull(intent.getStringExtra(Intent.EXTRA_TEXT))
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)

        @Suppress("DEPRECATION")
        val streamUri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
        assertEquals("com.videobridge.reportshare", streamUri.authority)
        assertEquals(uri, streamUri)
        assertEquals(uri, intent.clipData!!.getItemAt(0).uri)
        assertFalse(intent.extras!!.containsKey(Intent.EXTRA_TEXT))
    }
}
