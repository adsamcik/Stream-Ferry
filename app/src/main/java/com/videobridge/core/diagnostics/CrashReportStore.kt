package com.videobridge.core.diagnostics

import java.io.File

/**
 * Pure-JVM storage for crash reports: bounded, newest-first files under a directory. Framework-free
 * so the capture → file → read-back lifecycle is unit-testable without the Android toolchain. The
 * Android [com.videobridge.diagnostics.CrashReporter] points this at `filesDir/crashes`.
 */
class CrashReportStore(private val dir: File, private val maxFiles: Int = DEFAULT_MAX_FILES) {

    /** Persist [report] as `crash-<stamp>.txt`, then prune to the newest [maxFiles]. */
    fun write(report: String, stamp: String): File {
        dir.mkdirs()
        val file = File(dir, "$PREFIX$stamp.txt")
        file.writeText(report)
        prune()
        return file
    }

    /** Stored report files, newest first (lexicographic on the timestamped name). */
    fun reports(): List<File> =
        dir.listFiles { f -> f.isFile && f.name.startsWith(PREFIX) }
            ?.sortedByDescending { it.name } ?: emptyList()

    fun count(): Int = reports().size

    /** The most recent report's text, or null when there are none. */
    fun latest(): String? = reports().firstOrNull()?.let { runCatching { it.readText() }.getOrNull() }

    /** All reports concatenated (newest first) for sharing/export. */
    fun combined(): String = concat(reports())

    /** Reports recorded by the given app [versionCode] (for latest-build-only sharing), newest first. */
    fun reportsForVersion(versionCode: String): List<File> =
        reports().filter { f -> runCatching { versionCodeOf(f.readText()) }.getOrNull() == versionCode }

    /** Count of stored reports from [versionCode]. */
    fun countForVersion(versionCode: String): Int = reportsForVersion(versionCode).size

    /** Reports from [versionCode] concatenated (newest first) — only reports relevant to that build. */
    fun combinedForVersion(versionCode: String): String = concat(reportsForVersion(versionCode))

    /** The most recent report from [versionCode], or null when this build has none. */
    fun latestForVersion(versionCode: String): String? =
        reportsForVersion(versionCode).firstOrNull()?.let { runCatching { it.readText() }.getOrNull() }

    private fun concat(files: List<File>): String =
        files.joinToString("\n\n${"=".repeat(60)}\n\n") { runCatching { it.readText() }.getOrDefault("") }

    /** Delete every stored report. */
    fun clear() {
        reports().forEach { runCatching { it.delete() } }
    }

    private fun prune() {
        reports().drop(maxFiles).forEach { runCatching { it.delete() } }
    }

    companion object {
        private const val PREFIX = "crash-"
        private const val DEFAULT_MAX_FILES = 20
        // The versionCode a crash report recorded, from its "app version: <name> (<code>) ..." header line.
        private val VERSION_LINE = Regex("""app version:\s*\S+\s*\(([^)]*)\)""")

        /** Extract the app versionCode recorded in a crash [reportText], or null if absent/unparseable. */
        fun versionCodeOf(reportText: String): String? =
            VERSION_LINE.find(reportText)?.groupValues?.getOrNull(1)?.trim()?.ifEmpty { null }
    }
}
