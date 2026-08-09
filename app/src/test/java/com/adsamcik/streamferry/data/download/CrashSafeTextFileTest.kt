package com.adsamcik.streamferry.data.download

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class CrashSafeTextFileTest {

    @Test
    fun `replacement keeps previous committed value as backup`() {
        val root = Files.createTempDirectory("crash-safe-text").toFile()
        try {
            val target = root.resolve("index.json")
            CrashSafeTextFile.write(target, "old")
            CrashSafeTextFile.write(target, "new")

            assertEquals("new", CrashSafeTextFile.readRecovering(target) { it })
            assertEquals("old", CrashSafeTextFile.backupFile(target).readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `reader recovers backup when current is missing or malformed`() {
        val root = Files.createTempDirectory("crash-safe-recovery").toFile()
        try {
            val target = root.resolve("queue.json")
            val backup = CrashSafeTextFile.backupFile(target)
            backup.writeText("41")

            assertEquals(41, CrashSafeTextFile.readRecovering(target) { it.toInt() })
            target.writeText("not a number")
            assertEquals(41, CrashSafeTextFile.readRecovering(target) { it.toInt() })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `delete removes current and recovery files`() {
        val root = Files.createTempDirectory("crash-safe-delete").toFile()
        try {
            val target = root.resolve("queue.json")
            CrashSafeTextFile.write(target, "one")
            CrashSafeTextFile.write(target, "two")

            CrashSafeTextFile.delete(target)

            assertFalse(target.exists())
            assertFalse(CrashSafeTextFile.backupFile(target).exists())
            assertNull(CrashSafeTextFile.readRecovering(target) { it })
        } finally {
            root.deleteRecursively()
        }
    }
}
