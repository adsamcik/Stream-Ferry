package com.adsamcik.streamferry.core

import com.adsamcik.streamferry.core.local.LocalMediaRules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalMediaRulesTest {

    @Test fun videoByExtension() {
        assertTrue(LocalMediaRules.isVideoFile("movie.mkv", null))
        assertTrue(LocalMediaRules.isVideoFile("CLIP.MP4", null)) // case-insensitive
        assertFalse(LocalMediaRules.isVideoFile("notes.txt", null))
        assertFalse(LocalMediaRules.isVideoFile("noextension", null))
    }

    @Test fun videoByMimeEvenWithoutKnownExtension() {
        assertTrue(LocalMediaRules.isVideoFile("blob.bin", "video/mp4"))
        assertTrue(LocalMediaRules.isVideoFile("clip", "VIDEO/WEBM"))
        assertFalse(LocalMediaRules.isVideoFile("doc.pdf", "application/pdf"))
    }

    @Test fun mimeByExtension() {
        assertEquals("video/x-matroska", LocalMediaRules.mimeForName("a.mkv"))
        assertEquals("video/mp4", LocalMediaRules.mimeForName("a.mp4"))
        assertEquals("video/mp2t", LocalMediaRules.mimeForName("a.ts"))
        assertNull(LocalMediaRules.mimeForName("a.unknownext"))
    }

    @Test fun displayTitleCleansFilename() {
        assertEquals("My Movie 2020", LocalMediaRules.displayTitle("My.Movie.2020.mkv"))
        assertEquals("cool clip", LocalMediaRules.displayTitle("cool_clip.mp4"))
        assertEquals("plain", LocalMediaRules.displayTitle("plain"))
    }

    @Test fun extensionOf() {
        assertEquals("mp4", LocalMediaRules.extensionOf("A.MP4"))
        assertEquals("", LocalMediaRules.extensionOf("noext"))
        assertEquals("mkv", LocalMediaRules.extensionOf("a.b.mkv"))
    }
}
