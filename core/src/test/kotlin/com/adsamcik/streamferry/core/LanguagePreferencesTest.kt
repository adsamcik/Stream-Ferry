package com.adsamcik.streamferry.core

import com.adsamcik.streamferry.core.language.LanguageMatcher
import com.adsamcik.streamferry.core.language.LanguagePreferenceResolver
import com.adsamcik.streamferry.core.language.Languages
import com.adsamcik.streamferry.core.language.SubtitleMemory
import com.adsamcik.streamferry.core.language.TrackLanguage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LanguagePreferencesTest {

    private fun tracks(vararg langs: Pair<Int, String?>) = langs.map { TrackLanguage(it.first, it.second) }

    // ----- LanguageMatcher -----

    @Test fun matchesExactCode() {
        val t = tracks(1 to "eng", 2 to "jpn", 3 to "spa")
        assertEquals(2, LanguageMatcher.matchIndex(t, "jpn"))
    }

    @Test fun matchesAcrossIsoVariants() {
        // Preference stored as 639-2/T "deu"; track reported as 639-2/B "ger" -> still matches.
        assertEquals(5, LanguageMatcher.matchIndex(tracks(1 to "eng", 5 to "ger"), "deu"))
        // And 639-1 vs 639-2.
        assertEquals(7, LanguageMatcher.matchIndex(tracks(7 to "fra"), "fr"))
        assertEquals(9, LanguageMatcher.matchIndex(tracks(9 to "en"), "eng"))
    }

    @Test fun isCaseInsensitiveAndTrims() {
        assertEquals(4, LanguageMatcher.matchIndex(tracks(4 to " ENG "), "eng"))
    }

    @Test fun noPreferenceOrNoMatchReturnsNull() {
        val t = tracks(1 to "eng", 2 to "jpn")
        assertNull(LanguageMatcher.matchIndex(t, null))
        assertNull(LanguageMatcher.matchIndex(t, ""))
        assertNull(LanguageMatcher.matchIndex(t, "spa")) // not present
        assertNull(LanguageMatcher.matchIndex(emptyList(), "eng"))
    }

    @Test fun picksFirstMatchingTrack() {
        assertEquals(2, LanguageMatcher.matchIndex(tracks(1 to "eng", 2 to "jpn", 3 to "jpn"), "jpn"))
    }

    // ----- LanguagePreferenceResolver -----

    @Test fun audioPerShowMemoryWinsOverGlobal() {
        assertEquals("jpn", LanguagePreferenceResolver.resolveAudio(rememberedAudio = "jpn", globalAudio = "eng"))
    }

    @Test fun audioFallsBackToGlobalThenNone() {
        assertEquals("eng", LanguagePreferenceResolver.resolveAudio(rememberedAudio = null, globalAudio = "eng"))
        assertEquals("eng", LanguagePreferenceResolver.resolveAudio(rememberedAudio = "", globalAudio = "eng"))
        assertNull(LanguagePreferenceResolver.resolveAudio(rememberedAudio = null, globalAudio = ""))
        assertNull(LanguagePreferenceResolver.resolveAudio(rememberedAudio = null, globalAudio = null))
    }

    @Test fun subtitleOffMemoryOverridesGlobalPreference() {
        // A show the user watches without subtitles stays off even if the global default is English subs.
        assertNull(LanguagePreferenceResolver.resolveSubtitle(SubtitleMemory.Off, globalSubtitle = "eng"))
    }

    @Test fun subtitleLanguageMemoryWins() {
        assertEquals("eng", LanguagePreferenceResolver.resolveSubtitle(SubtitleMemory.Language("eng"), globalSubtitle = "spa"))
    }

    @Test fun subtitleNoneUsesGlobal() {
        assertEquals("spa", LanguagePreferenceResolver.resolveSubtitle(SubtitleMemory.None, globalSubtitle = "spa"))
        assertNull(LanguagePreferenceResolver.resolveSubtitle(SubtitleMemory.None, globalSubtitle = ""))
        assertNull(LanguagePreferenceResolver.resolveSubtitle(SubtitleMemory.None, globalSubtitle = null))
    }

    // ----- Languages -----

    @Test fun commonListHasStableCodesAndNames() {
        assertTrue(Languages.COMMON.any { it.code == "eng" && it.name == "English" })
        assertTrue(Languages.COMMON.any { it.code == "jpn" })
    }

    @Test fun nameForKnownAndUnknownCodes() {
        assertEquals("German", Languages.nameFor("ger")) // alias resolves to display name
        assertEquals("English", Languages.nameFor("en"))
        assertNull(Languages.nameFor(""))
        assertNull(Languages.nameFor(null))
        assertEquals("zzz", Languages.nameFor("zzz")) // unknown -> echoes the code
    }

    @Test fun aliasesIncludeSelfAndVariants() {
        val fr = Languages.aliases("fre")
        assertTrue("fra" in fr && "fre" in fr && "fr" in fr)
        assertTrue("xyz" in Languages.aliases("xyz")) // unknown code still includes itself
    }
}
