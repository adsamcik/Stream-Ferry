package com.adsamcik.streamferry.core.language

/**
 * Pure language-preference logic (no Android, no SDK): the curated language list for the pickers,
 * ISO-code alias normalization, matching a preferred language to an available track, and resolving the
 * per-show memory against the global preference. Unit-tested.
 *
 * Track languages come from Jellyfin as ISO 639 codes, but servers/files are inconsistent about which
 * variant they use (639-2/T "deu", 639-2/B "ger", or 639-1 "de"), so matching normalizes across all
 * known equivalents for a language.
 */

/** A selectable language for the preferred-language pickers: a stored [code] plus a display [name]. */
data class Language(val code: String, val name: String)

/** Per-show subtitle memory: nothing remembered, explicitly OFF, or a specific language. */
sealed interface SubtitleMemory {
    data object None : SubtitleMemory
    data object Off : SubtitleMemory
    data class Language(val code: String) : SubtitleMemory
}

/** A track projected to just what language matching needs (keeps this module free of domain types). */
data class TrackLanguage(val index: Int, val language: String?)

object Languages {
    // Display name + all equivalent ISO codes (639-2/T, 639-2/B, 639-1), primary first. The primary code
    // is what the picker stores; matching accepts any equivalent a server might report.
    private data class Entry(val name: String, val codes: List<String>)

    private val ENTRIES = listOf(
        Entry("English", listOf("eng", "en")),
        Entry("Japanese", listOf("jpn", "ja")),
        Entry("Spanish", listOf("spa", "es")),
        Entry("French", listOf("fra", "fre", "fr")),
        Entry("German", listOf("deu", "ger", "de")),
        Entry("Italian", listOf("ita", "it")),
        Entry("Portuguese", listOf("por", "pt")),
        Entry("Russian", listOf("rus", "ru")),
        Entry("Korean", listOf("kor", "ko")),
        Entry("Chinese", listOf("zho", "chi", "zh")),
        Entry("Hindi", listOf("hin", "hi")),
        Entry("Arabic", listOf("ara", "ar")),
        Entry("Dutch", listOf("nld", "dut", "nl")),
        Entry("Polish", listOf("pol", "pl")),
        Entry("Turkish", listOf("tur", "tr")),
        Entry("Swedish", listOf("swe", "sv")),
        Entry("Norwegian", listOf("nor", "no")),
        Entry("Danish", listOf("dan", "da")),
        Entry("Finnish", listOf("fin", "fi")),
        Entry("Czech", listOf("ces", "cze", "cs")),
        Entry("Greek", listOf("ell", "gre", "el")),
        Entry("Hebrew", listOf("heb", "he")),
        Entry("Thai", listOf("tha", "th")),
        Entry("Vietnamese", listOf("vie", "vi")),
        Entry("Ukrainian", listOf("ukr", "uk")),
        Entry("Hungarian", listOf("hun", "hu")),
        Entry("Romanian", listOf("ron", "rum", "ro")),
        Entry("Indonesian", listOf("ind", "id")),
    )

    /** Curated common languages for the preferred-language pickers (primary ISO code + display name). */
    val COMMON: List<Language> = ENTRIES.map { Language(it.codes.first(), it.name) }

    /** All equivalent codes (lowercased) for [code], so a preference matches any ISO variant a server uses. */
    fun aliases(code: String): Set<String> {
        val c = code.trim().lowercase()
        return (ENTRIES.firstOrNull { c in it.codes }?.codes?.map { it.lowercase() }?.toSet() ?: emptySet()) + c
    }

    /** Display name for a stored [code], or the code itself when unknown; null for a blank/absent code. */
    fun nameFor(code: String?): String? {
        val c = code?.trim()?.lowercase()?.ifEmpty { null } ?: return null
        return ENTRIES.firstOrNull { c in it.codes }?.name ?: code
    }
}

object LanguageMatcher {
    /**
     * Index of the first track whose language matches [preferredLanguage] (across ISO variants), or null
     * when there's no preference or no matching track.
     */
    fun matchIndex(tracks: List<TrackLanguage>, preferredLanguage: String?): Int? {
        val wanted = wantedCodes(preferredLanguage) ?: return null
        return tracks.firstOrNull { matches(it.language, wanted) }?.index
    }

    private fun wantedCodes(preferredLanguage: String?): Set<String>? {
        val pref = preferredLanguage?.trim()?.lowercase()?.ifEmpty { null } ?: return null
        return Languages.aliases(pref)
    }

    private fun matches(trackLanguage: String?, wanted: Set<String>): Boolean {
        val lang = trackLanguage?.trim()?.lowercase()?.ifEmpty { null } ?: return false
        return lang in wanted
    }
}

object LanguagePreferenceResolver {
    /** Final desired audio language: the per-show memory wins over the global preference. */
    fun resolveAudio(rememberedAudio: String?, globalAudio: String?): String? =
        rememberedAudio?.trim()?.ifEmpty { null } ?: globalAudio?.trim()?.ifEmpty { null }

    /**
     * Final desired subtitle language, or null for "no subtitles". A per-show memory (a language, or an
     * explicit OFF) wins over the global preference; with no memory the global preference applies.
     */
    fun resolveSubtitle(remembered: SubtitleMemory, globalSubtitle: String?): String? =
        when (remembered) {
            is SubtitleMemory.Off -> null
            is SubtitleMemory.Language -> remembered.code.trim().ifEmpty { null }
            SubtitleMemory.None -> globalSubtitle?.trim()?.ifEmpty { null }
        }
}
