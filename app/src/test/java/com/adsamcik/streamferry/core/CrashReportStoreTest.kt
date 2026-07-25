package com.adsamcik.streamferry.core

import com.adsamcik.streamferry.core.diagnostics.CrashReportFormatter
import com.adsamcik.streamferry.core.diagnostics.CrashReportStore
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CrashReportStoreTest {

    private fun newStore(maxFiles: Int = 20): CrashReportStore =
        CrashReportStore(Files.createTempDirectory("crashstore").toFile(), maxFiles)

    @Test fun writeThenReadBackNewestFirst() {
        val s = newStore()
        assertEquals(0, s.count())
        assertNull(s.latest())
        s.write("first", "20260620-000001-000")
        s.write("second", "20260620-000002-000")
        assertEquals(2, s.count())
        assertEquals("second", s.latest()) // newest first
        assertTrue(s.combined().startsWith("second"))
        assertTrue(s.combined().contains("first"))
    }

    @Test fun pruneKeepsOnlyMostRecent() {
        val s = newStore(maxFiles = 3)
        for (i in 1..6) s.write("report-$i", "20260620-00000$i-000")
        assertEquals(3, s.count())
        assertEquals("report-6", s.latest())
        assertFalse(s.combined().contains("report-1")) // oldest pruned
        assertFalse(s.combined().contains("report-3"))
        assertTrue(s.combined().contains("report-4"))
    }

    @Test fun clearRemovesAll() {
        val s = newStore()
        s.write("x", "20260620-000001-000")
        s.clear()
        assertEquals(0, s.count())
        assertNull(s.latest())
    }

    @Test fun endToEndFormattedReportIsStoredRedacted() {
        val s = newStore()
        val meta = CrashReportFormatter.Metadata(
            timeIso = "2026-06-20T23:00:00.000+0200",
            appVersion = "0.2.2",
            versionCode = "4",
            androidRelease = "17",
            sdkInt = "37",
            device = "Pixel",
            threadName = "main",
            isDebug = false,
        )
        val ex = RuntimeException("failed http://10.0.0.5:8096?api_key=SECRETTOKEN")
        s.write(CrashReportFormatter.format(meta, ex), "20260620-000009-000")
        val readBack = s.latest()!!
        assertTrue(readBack.contains("java.lang.RuntimeException"))
        assertFalse(readBack.contains("SECRETTOKEN"), "the persisted report must not contain the token")
    }

    // --- latest-build-only sharing ---

    private fun report(versionCode: String, body: String = "boom") =
        "=== Stream Ferry crash report ===\napp version: 0.2.30 ($versionCode) debug\n\n--- stack trace ---\n$body"

    @Test fun versionCodeIsParsedFromReportHeader() {
        assertEquals("27", CrashReportStore.versionCodeOf(report("27")))
        assertEquals("4", CrashReportStore.versionCodeOf("app version: 0.2.2 (4)\nrest"))
        assertNull(CrashReportStore.versionCodeOf("no version line here"))
    }

    @Test fun combinedForVersionIncludesOnlyThatBuild() {
        val s = newStore()
        s.write(report("26", "old crash"), "20260620-000001-000")
        s.write(report("27", "new crash"), "20260620-000002-000")
        s.write(report("27", "newer crash"), "20260620-000003-000")
        assertEquals(2, s.countForVersion("27"))
        assertEquals(1, s.countForVersion("26"))
        val combined = s.combinedForVersion("27")
        assertTrue(combined.contains("new crash"))
        assertTrue(combined.contains("newer crash"))
        assertFalse(combined.contains("old crash"), "an older build's report must not be shared")
        assertTrue(combined.indexOf("newer crash") < combined.indexOf("new crash"), "newest first")
    }

    @Test fun latestForVersionIsNewestOfThatBuildOnly() {
        val s = newStore()
        s.write(report("27", "first-on-27"), "20260620-000001-000")
        s.write(report("26", "on-26"), "20260620-000002-000") // newer overall, but different build
        assertTrue(s.latestForVersion("27")!!.contains("first-on-27"))
        assertNull(s.latestForVersion("99")) // no reports for this build
    }
}
