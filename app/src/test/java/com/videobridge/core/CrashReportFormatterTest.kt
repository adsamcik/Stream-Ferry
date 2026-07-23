package com.videobridge.core

import com.videobridge.core.diagnostics.CrashReportFormatter
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CrashReportFormatterTest {

    private val meta = CrashReportFormatter.Metadata(
        timeIso = "2026-06-20T23:00:00.000+0200",
        appVersion = "0.2.2",
        versionCode = "4",
        androidRelease = "17",
        sdkInt = "37",
        device = "Samsung SM-S921",
        threadName = "main",
        isDebug = false,
    )

    @Test fun includesMetadataExceptionAndStackTrace() {
        val report = CrashReportFormatter.format(meta, IllegalStateException("boom"))
        assertTrue(report.contains("Video Bridge crash report"))
        assertTrue(report.contains("0.2.2 (4)"))
        assertTrue(report.contains("17 (API 37)"))
        assertTrue(report.contains("Samsung SM-S921"))
        assertTrue(report.contains("java.lang.IllegalStateException: boom"))
        assertTrue(report.contains("--- stack trace ---"))
        // The throwing test frame appears in the rendered stack trace.
        assertTrue(report.contains("CrashReportFormatterTest"))
    }

    @Test fun redactsSecretsThatLandInAnExceptionMessage() {
        val ex = RuntimeException("request failed: http://10.0.0.5:8096/Users?api_key=SECRETTOKEN123")
        val report = CrashReportFormatter.format(meta, ex)
        assertFalse(report.contains("SECRETTOKEN123"), "access token must never be written to a crash report")
        assertFalse(report.contains("api_key=SECRETTOKEN123"))
        // The host is masked too (a Jellyfin host is sensitive per the threat model); the token query is dropped.
        assertFalse(report.contains("10.0.0.5"))
    }

    @Test fun includesCauseChain() {
        val ex = RuntimeException("outer", IllegalArgumentException("inner cause detail"))
        val report = CrashReportFormatter.format(meta, ex)
        assertTrue(report.contains("Caused by"))
        assertTrue(report.contains("inner cause detail"))
    }
}
