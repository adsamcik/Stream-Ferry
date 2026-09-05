package com.adsamcik.streamferry.diagnostics

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import com.adsamcik.streamferry.core.redaction.LogRedactor
import java.time.Instant

/** Android keeps OS-kill evidence even when no uncaught-exception handler can run. */
object ProcessExitDiagnostics {
    fun read(context: Context): String = try {
        val manager = context.getSystemService(ActivityManager::class.java)
        val exits = manager?.getHistoricalProcessExitReasons(context.packageName, 0, 10)
            ?.filter { it.processName == context.packageName }.orEmpty()
        // Android does not attach our build ID to these records. Keep the timestamp and explicitly
        // label the history, rather than incorrectly attributing a pre-update exit to the current build.
        exits.joinToString("\n", transform = ::format).ifBlank { "(no Android process exit records)" }
    } catch (error: Exception) {
        "(Android exit history unavailable: ${error.javaClass.simpleName})"
    }

    internal fun format(info: ApplicationExitInfo): String {
        val reason = when (info.reason) {
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
            ApplicationExitInfo.REASON_CRASH -> "JAVA_CRASH"
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "NATIVE_CRASH"
            ApplicationExitInfo.REASON_ANR -> "ANR"
            ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
            ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
            ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
            ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
            ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
            else -> "OTHER"
        }
        return LogRedactor.redact(
            "${Instant.ofEpochMilli(info.timestamp)} pid=${info.pid} reason=$reason(${info.reason}) " +
                "status=${info.status} importance=${info.importance} pssKb=${info.pss} rssKb=${info.rss} " +
                "description=${info.description?.replace('\n', ' ')?.take(512) ?: "none"}",
        )
    }
}
