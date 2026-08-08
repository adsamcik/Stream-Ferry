package com.adsamcik.streamferry.data.download

import com.adsamcik.streamferry.core.download.DownloadPaths
import java.security.MessageDigest

/**
 * Stable identity for one offline copy. A Jellyfin item id is only unique within one server/user
 * account, so every persisted/indexed/downloaded operation must use this pair rather than item id alone.
 */
data class DownloadIdentity(
    val owner: DownloadOwner?,
    val itemId: String,
) {
    /** File stem. Ownerless legacy downloads retain their historical filename for migration-free reads. */
    val fileStem: String
        get() = owner?.let { digest(canonical()).take(FILE_STEM_LENGTH) } ?: DownloadPaths.safeBaseName(itemId)

    fun fileName(container: String?, mimeType: String?): String =
        "$fileStem.${DownloadPaths.extensionForContainer(container) ?: DownloadPaths.extensionForMime(mimeType)}"

    /** Stable, account-scoped local ResumeStore key; it contains no raw server, user, or item id. */
    val resumeKey: String get() = "dl:$fileStem"

    private fun canonical(): String = buildString {
        append("v1")
        listOf(owner?.serverId.orEmpty(), owner?.userId.orEmpty(), itemId).forEach { value ->
            append(value.length).append(':').append(value)
        }
    }

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private companion object {
        const val FILE_STEM_LENGTH = 64
    }
}

internal val DownloadEntry.identity: DownloadIdentity
    get() = DownloadIdentity(owner = owner, itemId = itemId)

internal val PendingDownload.identity: DownloadIdentity
    get() = DownloadIdentity(owner = owner, itemId = itemId)
