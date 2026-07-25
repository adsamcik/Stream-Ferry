package com.adsamcik.streamferry.core.diagnostics

import com.adsamcik.streamferry.core.redaction.LogRedactor
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Builds a single crash report from non-secret process/device metadata and an uncaught [Throwable].
 *
 * Pure-JVM so it is exhaustively unit-testable. The whole report — header **and** the full stack
 * trace (including the cause chain) — is passed through [LogRedactor], so a token, Jellyfin URL or
 * `Authorization` header that happened to land in an exception message can never be written to disk
 * or shared (§13).
 */
object CrashReportFormatter {

    /** Non-secret process/device facts shown at the top of a report. */
    data class Metadata(
        val timeIso: String,
        val appVersion: String,
        val versionCode: String,
        val androidRelease: String,
        val sdkInt: String,
        val device: String, // manufacturer + model
        val threadName: String,
        val isDebug: Boolean,
    )

    fun format(meta: Metadata, throwable: Throwable): String {
        val stack = StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }.toString()
        val report = buildString {
            appendLine("=== Stream Ferry crash report ===")
            appendLine("time:        ${meta.timeIso}")
            appendLine("app version: ${meta.appVersion} (${meta.versionCode})${if (meta.isDebug) " debug" else ""}")
            appendLine("android:     ${meta.androidRelease} (API ${meta.sdkInt})")
            appendLine("device:      ${meta.device}")
            appendLine("thread:      ${meta.threadName}")
            appendLine("exception:   ${throwable.javaClass.name}: ${throwable.message}")
            appendLine()
            appendLine("--- stack trace ---")
            append(stack)
        }
        // Redact the ENTIRE report (messages can carry URLs/tokens); never write raw secrets to disk.
        return LogRedactor.redact(report)
    }
}
