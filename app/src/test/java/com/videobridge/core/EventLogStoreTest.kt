package com.videobridge.core

import com.videobridge.core.diagnostics.EventLogStore
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EventLogStoreTest {

    private fun newStore(maxSessions: Int = 8): EventLogStore =
        EventLogStore(Files.createTempDirectory("eventstore").toFile(), maxSessions)

    @Test fun writeThenReadBackForVersion() {
        val s = newStore()
        assertEquals(0, s.countForVersion("30"))
        s.writeSession("30", "20260705-000001-000", listOf("a", "b", "c"))
        assertEquals(3, s.countForVersion("30"))
        assertEquals(listOf("a", "b", "c"), s.linesForVersion("30", maxLines = 100))
    }

    @Test fun overwritingSameSessionReplacesItsLines() {
        val s = newStore()
        s.writeSession("30", "20260705-000001-000", listOf("a", "b"))
        s.writeSession("30", "20260705-000001-000", listOf("a", "b", "c", "d")) // same stamp -> overwrite
        assertEquals(listOf("a", "b", "c", "d"), s.linesForVersion("30", 100))
        assertEquals(1, s.sessions().size)
    }

    @Test fun mergesSessionsOldestFirst() {
        val s = newStore()
        s.writeSession("30", "20260705-000001-000", listOf("s1-a", "s1-b"))
        s.writeSession("30", "20260705-000002-000", listOf("s2-a"))
        assertEquals(listOf("s1-a", "s1-b", "s2-a"), s.linesForVersion("30", 100))
    }

    @Test fun keepsOnlyCurrentBuildEvents() {
        val s = newStore()
        s.writeSession("29", "20260705-000001-000", listOf("old-build"))
        s.writeSession("30", "20260705-000002-000", listOf("new-build"))
        assertEquals(listOf("new-build"), s.linesForVersion("30", 100))
        assertEquals(listOf("old-build"), s.linesForVersion("29", 100))
    }

    @Test fun boundingKeepsNewestLines() {
        val s = newStore()
        s.writeSession("30", "20260705-000001-000", listOf("1", "2", "3", "4", "5"))
        assertEquals(listOf("3", "4", "5"), s.linesForVersion("30", maxLines = 3))
    }

    @Test fun pruneKeepsOnlyMostRecentSessions() {
        val s = newStore(maxSessions = 2)
        for (i in 1..5) s.writeSession("30", "20260705-00000$i-000", listOf("s$i"))
        assertEquals(2, s.sessions().size)
        assertEquals(listOf("s4", "s5"), s.linesForVersion("30", 100)) // oldest pruned
    }

    @Test fun clearRemovesAll() {
        val s = newStore()
        s.writeSession("30", "20260705-000001-000", listOf("a"))
        s.clear()
        assertEquals(0, s.countForVersion("30"))
        assertTrue(s.sessions().isEmpty())
    }

    @Test fun missingVersionHeaderIsIgnoredForVersionFilter() {
        val dir = Files.createTempDirectory("eventstore").toFile()
        // A stray file without our build header must not be attributed to any build.
        java.io.File(dir, "events-20260705-000009-000.txt").writeText("no header\nline")
        val s = EventLogStore(dir)
        assertFalse(s.linesForVersion("30", 100).contains("line"))
    }
}
