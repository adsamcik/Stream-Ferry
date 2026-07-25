package com.adsamcik.streamferry.diagnostics

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * Prepares a redacted diagnostic report for sharing without placing its contents in an Intent extra.
 *
 * Android limits activity-launch Binder transactions to roughly 1 MB. A [FileProvider] URI keeps large
 * reports out of that transaction while granting the selected recipient temporary read access only.
 */
object ReportShare {

    fun createIntent(context: Context, report: String, subject: String): Intent {
        val directory = File(context.cacheDir, DIRECTORY).apply {
            mkdirs()
            check(isDirectory) { "Could not create report share directory" }
        }
        val file = File.createTempFile(FILE_PREFIX, ".txt", directory).apply { writeText(report) }
        prune(directory)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.reportshare", file)

        return createSendIntent(uri, subject)
    }

    internal fun createSendIntent(uri: android.net.Uri, subject: String): Intent =
        Intent(Intent.ACTION_SEND).apply {
            type = MIME_TYPE
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("Stream Ferry report", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

    private fun prune(directory: File) {
        directory.listFiles()
            ?.sortedByDescending { it.lastModified() }
            ?.drop(MAX_CACHED_REPORTS)
            ?.forEach { it.delete() }
    }

    private const val DIRECTORY = "shared-reports"
    private const val FILE_PREFIX = "stream-ferry-report-"
    private const val MAX_CACHED_REPORTS = 5
    private const val MIME_TYPE = "text/plain"
}
