package com.videobridge.core.diagnostics

import java.io.File

/**
 * Pure-JVM persistence for the redacted diagnostics EVENT log, so a shared report survives app restarts
 * (the in-memory ring in [com.videobridge.logging.RedactingLogger] is wiped on process death — which is
 * why a report made after playback, in a fresh launch, showed none of the playback/transcode/TV events).
 *
 * One file per app session (`events-<stamp>.txt`), tagged with the build's versionCode in a header line
 * so a shared report can keep only the CURRENT build's events, bounded to the newest [maxSessions].
 * Framework-free so the write → read-back → build-filter lifecycle is unit-testable without the Android
 * toolchain; the Android glue ([com.videobridge.diagnostics.DiagnosticsEventLog]) points this at
 * `filesDir/events`. Everything written here is ALREADY redacted by the logger.
 */
class EventLogStore(private val dir: File, private val maxSessions: Int = DEFAULT_MAX_SESSIONS) {

    /**
     * Overwrite this session's file (identified by [stamp]) with [lines] — the current bounded ring
     * snapshot — tagging it with [versionCode] so [linesForVersion] can keep only the current build.
     * Prunes to the newest [maxSessions] afterwards. Overwriting (not appending) keeps each session file
     * bounded by the ring size and makes the write idempotent.
     */
    fun writeSession(versionCode: String, stamp: String, lines: List<String>): File {
        dir.mkdirs()
        val file = File(dir, "$PREFIX$stamp.txt")
        file.writeText((listOf(HEADER_PREFIX + versionCode) + lines).joinToString("\n"))
        prune()
        return file
    }

    /** Session files, oldest first (lexicographic on the sortable timestamped name). */
    fun sessions(): List<File> =
        dir.listFiles { f -> f.isFile && f.name.startsWith(PREFIX) }?.sortedBy { it.name } ?: emptyList()

    /** Session files recorded by [versionCode], oldest first. */
    fun sessionsForVersion(versionCode: String): List<File> =
        sessions().filter { runCatching { versionCodeOf(it.readText()) }.getOrNull() == versionCode }

    /**
     * Event lines from the current build's sessions, oldest first, capped to the newest [maxLines] (older
     * lines dropped) so a shared report stays bounded even across many sessions.
     */
    fun linesForVersion(versionCode: String, maxLines: Int): List<String> {
        val all = sessionsForVersion(versionCode).flatMap { f ->
            runCatching { bodyLines(f.readText()) }.getOrDefault(emptyList())
        }
        return if (all.size <= maxLines) all else all.subList(all.size - maxLines, all.size)
    }

    /** Total number of persisted event lines for [versionCode]. */
    fun countForVersion(versionCode: String): Int =
        sessionsForVersion(versionCode).sumOf { f -> runCatching { bodyLines(f.readText()).size }.getOrDefault(0) }

    /** Delete every stored session log. */
    fun clear() {
        sessions().forEach { runCatching { it.delete() } }
    }

    /** Keep only the newest [maxSessions] session files. */
    private fun prune() {
        val all = sessions()
        if (all.size > maxSessions) {
            all.subList(0, all.size - maxSessions).forEach { runCatching { it.delete() } }
        }
    }

    companion object {
        const val PREFIX = "events-"
        private const val HEADER_PREFIX = "# build="
        private const val DEFAULT_MAX_SESSIONS = 8

        /** The versionCode recorded in a session file's header line, or null if absent/unparseable. */
        fun versionCodeOf(text: String): String? =
            text.lineSequence().firstOrNull()
                ?.takeIf { it.startsWith(HEADER_PREFIX) }
                ?.removePrefix(HEADER_PREFIX)?.trim()?.ifEmpty { null }

        /** Event lines (everything after the header line). */
        private fun bodyLines(text: String): List<String> =
            text.lineSequence().drop(1).filter { it.isNotEmpty() }.toList()
    }
}
