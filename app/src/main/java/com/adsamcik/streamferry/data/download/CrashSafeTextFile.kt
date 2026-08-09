package com.adsamcik.streamferry.data.download

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Small crash-safe text-file primitive for download metadata JSON. */
internal object CrashSafeTextFile {

    /**
     * Write and fsync a sibling temp file, retain the previous committed file as a backup, then atomically
     * install the new file. A process death at any point leaves current, backup, or both readable.
     */
    fun write(target: File, text: String) {
        val parent = target.parentFile ?: throw IOException("Metadata file has no parent directory")
        if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Couldn't create metadata directory")
        val temp = tempFile(target)
        val backup = backupFile(target)
        try {
            FileOutputStream(temp, false).use { output ->
                output.write(text.toByteArray(StandardCharsets.UTF_8))
                output.fd.sync()
            }
            if (target.isFile) moveReplace(target, backup)
            try {
                moveReplace(temp, target)
            } catch (failure: Throwable) {
                // Best-effort in-process recovery. After process death, readRecovering() performs the same
                // logical recovery by accepting the backup when current is absent.
                if (!target.exists() && backup.isFile) runCatching { moveReplace(backup, target) }
                throw failure
            }
        } finally {
            runCatching { temp.delete() }
        }
    }

    /** Decode current first, then the previous committed backup if current is absent or malformed. */
    fun <T> readRecovering(target: File, decode: (String) -> T): T? {
        for (candidate in listOf(target, backupFile(target))) {
            if (!candidate.isFile) continue
            runCatching { decode(candidate.readText(StandardCharsets.UTF_8)) }.getOrNull()?.let { return it }
        }
        return null
    }

    fun delete(target: File) {
        runCatching { target.delete() }
        runCatching { backupFile(target).delete() }
        runCatching { tempFile(target).delete() }
    }

    internal fun backupFile(target: File): File = File(target.parentFile, target.name + BACKUP_SUFFIX)
    private fun tempFile(target: File): File = File(target.parentFile, target.name + TEMP_SUFFIX)

    private fun moveReplace(source: File, target: File) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            // Both files are siblings. Filesystems without ATOMIC_MOVE still get replace-by-rename rather
            // than an in-place truncate, preserving the important old-or-new property where supported.
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private const val TEMP_SUFFIX = ".tmp"
    private const val BACKUP_SUFFIX = ".bak"
}
