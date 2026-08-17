package com.adsamcik.streamferry.core.download

/**
 * Pure helpers for the optional offline-download feature (§5 download exception). They turn a Jellyfin
 * item id + container/MIME into a **safe** on-disk filename. Security: the filename is derived ONLY
 * from the (alphanumeric) item id — never from a user-controlled title — and is sanitised so it can
 * never escape the downloads directory (no path separators, `..`, NUL, etc.).
 */
object DownloadPaths {

    /** A filesystem-safe base name derived from [itemId] (letters/digits/-/_ only, bounded length). */
    fun safeBaseName(itemId: String): String =
        itemId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(64).ifEmpty { "item" }

    /** File extension for a media [container] string (Jellyfin container), or null if unknown. */
    fun extensionForContainer(container: String?): String? = when (container?.lowercase()?.substringBefore(',')) {
        "mp4", "m4v" -> "mp4"
        "mkv", "matroska" -> "mkv"
        "webm" -> "webm"
        "ts", "mpegts" -> "ts"
        "mov", "quicktime" -> "mov"
        "avi" -> "avi"
        "ogv", "ogg" -> "ogv"
        else -> null
    }

    /** File extension for a [mime] type, defaulting to "bin" when unknown. */
    fun extensionForMime(mime: String?): String = when {
        mime == null -> "bin"
        mime.contains("mp4", true) -> "mp4"
        mime.contains("matroska", true) -> "mkv"
        mime.contains("webm", true) -> "webm"
        mime.contains("mp2t", true) -> "ts"
        mime.contains("quicktime", true) -> "mov"
        mime.contains("x-msvideo", true) -> "avi"
        mime.contains("ogg", true) -> "ogv"
        else -> "bin"
    }

    /** Safe download filename `<itemId>.<ext>`, preferring the container, then the MIME. */
    fun fileName(itemId: String, container: String?, mime: String?): String =
        safeBaseName(itemId) + "." + (extensionForContainer(container) ?: extensionForMime(mime))
}
