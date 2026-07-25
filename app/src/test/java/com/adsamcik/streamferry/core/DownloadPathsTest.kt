package com.adsamcik.streamferry.core

import com.adsamcik.streamferry.core.download.DownloadPaths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DownloadPathsTest {

    @Test fun safeBaseNameStripsPathTraversalAndSeparators() {
        assertEquals("etcpasswd", DownloadPaths.safeBaseName("../../etc/passwd"))
        assertEquals("abc123", DownloadPaths.safeBaseName("a/b\\c\u0000..1\t2 3"))
        val n = DownloadPaths.safeBaseName("a4f9-7c2d_ID")
        assertTrue(n.all { it.isLetterOrDigit() || it == '-' || it == '_' })
    }

    @Test fun safeBaseNameNeverEmpty() {
        assertEquals("item", DownloadPaths.safeBaseName("////"))
        assertEquals("item", DownloadPaths.safeBaseName(""))
    }

    @Test fun extensionFromContainer() {
        assertEquals("mp4", DownloadPaths.extensionForContainer("mp4"))
        assertEquals("mkv", DownloadPaths.extensionForContainer("matroska,webm"))
        assertEquals("ts", DownloadPaths.extensionForContainer("mpegts"))
        assertEquals(null, DownloadPaths.extensionForContainer("weirdformat"))
    }

    @Test fun extensionFromMime() {
        assertEquals("mp4", DownloadPaths.extensionForMime("video/mp4"))
        assertEquals("mkv", DownloadPaths.extensionForMime("video/x-matroska"))
        assertEquals("bin", DownloadPaths.extensionForMime(null))
    }

    @Test fun fileNamePrefersContainerThenMimeAndIsSafe() {
        assertEquals("abc.mkv", DownloadPaths.fileName("abc", "matroska", "video/mp4"))
        assertEquals("abc.mp4", DownloadPaths.fileName("abc", null, "video/mp4"))
        val name = DownloadPaths.fileName("../../evil", "mp4", null)
        assertEquals("evil.mp4", name)
        assertFalse(name.contains("/"))
        assertFalse(name.contains(".."))
    }
}
