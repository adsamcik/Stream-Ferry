package com.adsamcik.streamferry.core.local

/**
 * Pure, framework-free rules for the local (on-device) media source: which files are playable videos,
 * how to derive a display title from a file name, and a best-effort container MIME by extension.
 *
 * The Android layer (SAF / MediaStore) feeds these with file names + MIME types; the logic stays here
 * so it is unit-testable without a device.
 */
object LocalMediaRules {

    /** Video container extensions we surface in the local gallery (lowercase, no leading dot). */
    val VIDEO_EXTENSIONS: Set<String> = setOf(
        "mp4", "m4v", "mkv", "webm", "mov", "avi", "3gp", "3g2", "ts", "m2ts", "mts",
        "mpeg", "mpg", "flv", "wmv", "ogv", "mxf",
    )

    private val MIME_BY_EXTENSION: Map<String, String> = mapOf(
        "mp4" to "video/mp4", "m4v" to "video/mp4", "mov" to "video/quicktime",
        "mkv" to "video/x-matroska", "webm" to "video/webm", "avi" to "video/x-msvideo",
        "3gp" to "video/3gpp", "3g2" to "video/3gpp2", "ts" to "video/mp2t",
        "m2ts" to "video/mp2t", "mts" to "video/mp2t", "mpeg" to "video/mpeg",
        "mpg" to "video/mpeg", "flv" to "video/x-flv", "wmv" to "video/x-ms-wmv",
        "ogv" to "video/ogg", "mxf" to "application/mxf",
    )

    /**
     * A file is a playable video if its MIME starts with "video/" OR its extension is a known video
     * container (compatibility is confirmed later by probing — never inferred from the extension for
     * codec decisions; this is only for *listing*).
     */
    fun isVideoFile(name: String, mimeType: String?): Boolean {
        if (mimeType != null && mimeType.startsWith("video/", ignoreCase = true)) return true
        return extensionOf(name) in VIDEO_EXTENSIONS
    }

    /** Best-effort container MIME for a file name (used as the proxy Content-Type); null if unknown. */
    fun mimeForName(name: String): String? = MIME_BY_EXTENSION[extensionOf(name)]

    /** Friendly title from a file name: drop the extension, turn separators into spaces, collapse runs. */
    fun displayTitle(fileName: String): String {
        val base = fileName.substringBeforeLast('.')
        val spaced = base.replace('_', ' ').replace('.', ' ').trim().replace(Regex("\\s+"), " ")
        return spaced.ifBlank { fileName }
    }

    /** Lowercased extension without the dot, or "" when there is none. */
    fun extensionOf(name: String): String = name.substringAfterLast('.', "").lowercase()
}
