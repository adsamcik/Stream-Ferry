package com.adsamcik.streamferry.data.local

import android.content.ContentUris
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.adsamcik.streamferry.core.local.LocalMediaRules
import com.adsamcik.streamferry.core.transcode.SourceCapabilities
import com.adsamcik.streamferry.domain.MediaItem
import com.adsamcik.streamferry.domain.MediaSource
import com.adsamcik.streamferry.domain.MediaSourceIds
import com.adsamcik.streamferry.source.api.SourceInstanceId
import com.adsamcik.streamferry.source.api.DiagnosticSink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * On-device videos exposed as a browsable [MediaSource].
 *
 * Access is user-elective:
 *  - **SAF (default, zero permissions):** the user picks a folder (a persisted tree grant) and/or
 *    individual files; we enumerate videos under granted folders and list granted files.
 *  - **Optional MediaStore:** when the user grants `READ_MEDIA_VIDEO`, a synthetic "All device videos"
 *    folder lists every video via MediaStore.
 *
 * A local item's [MediaItem.id] is its `content://` URI. The TV never receives that URI — the proxy
 * opens it on the phone and serves only the phone proxy URL (security invariant preserved). Local files
 * have no server, so [capabilities] disables server transcode (the phone transcodes on-device instead).
 */
class LocalMediaSource(
    context: Context,
    private val store: LocalSourceStore,
    private val logger: DiagnosticSink,
    private val hasAllMediaAccess: () -> Boolean,
    private val hasSelectedMediaAccess: () -> Boolean,
    private val resolver: ContentResolver = context.applicationContext.contentResolver,
) : MediaSource {

    private val appContext = context.applicationContext

    override val id: String = MediaSourceIds.LOCAL
    override val displayName: String = "On this device"
    override val capabilities: SourceCapabilities =
        SourceCapabilities(
            canServerTranscode = false,
            isSeekable = true,
            isReopenable = true,
            canStreamToClientTranscoder = true,
        )

    /** True when the user has granted any local access (folders, files, or the media permission). */
    fun hasAnyAccess(): Boolean =
        hasMediaAccess() || store.folders().isNotEmpty() || store.files().isNotEmpty()

    /** Persist a folder (tree) grant from the SAF `OpenDocumentTree` picker. */
    fun addFolder(treeUri: Uri) {
        persist(treeUri)
        store.addFolder(treeUri.toString())
        logger.event("local", "Added a local folder")
    }

    /** Persist individual file grants from the SAF `OpenDocument` picker. */
    fun addFiles(uris: List<Uri>) {
        uris.forEach { persist(it); store.addFile(it.toString()) }
        logger.event("local", "Added ${uris.size} local file(s)")
    }

    fun removeRoot(rootId: String) {
        val tracked = rootId in store.folders() || rootId in store.files()
        store.removeFolder(rootId)
        store.removeFile(rootId)
        if (!tracked) return

        runCatching {
            val rootUri = Uri.parse(rootId)
            resolver.persistedUriPermissions
                .filter { it.uri == rootUri }
                .forEach { permission ->
                    var flags = 0
                    if (permission.isReadPermission) flags = flags or Intent.FLAG_GRANT_READ_URI_PERMISSION
                    if (permission.isWritePermission) flags = flags or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    if (flags != 0) resolver.releasePersistableUriPermission(rootUri, flags)
                }
        }.onFailure {
            logger.warn("local", "Could not release a removed local-media permission")
        }
    }

    /** Relinquish every SAF capability held by the app and clear the corresponding local roots. */
    fun clearPersistedAccess() {
        var incomplete = false
        val permissions = runCatching { resolver.persistedUriPermissions }
            .getOrElse {
                incomplete = true
                emptyList()
            }
        permissions.forEach { permission ->
            runCatching {
                var flags = 0
                if (permission.isReadPermission) flags = flags or Intent.FLAG_GRANT_READ_URI_PERMISSION
                if (permission.isWritePermission) flags = flags or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                if (flags != 0) resolver.releasePersistableUriPermission(permission.uri, flags)
            }.onFailure { incomplete = true }
        }
        runCatching { store.clear() }.onFailure { incomplete = true }
        if (incomplete) {
            logger.warn("local", "Could not fully clear local-media permissions")
            throw IllegalStateException("Could not fully clear local-media permissions")
        }
    }

    private fun persist(uri: Uri) {
        runCatching { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    override suspend fun roots(): Result<List<MediaItem>> = runCatching {
        withContext(Dispatchers.IO) {
            val out = ArrayList<MediaItem>()
            if (hasMediaAccess()) out.add(folderItem(MEDIASTORE_ALL, mediaStoreRootTitle()))
            for (folder in store.folders()) {
                val docUri = treeDocumentUri(Uri.parse(folder))
                out.add(folderItem(docUri.toString(), displayName(docUri) ?: lastSegmentName(Uri.parse(folder))))
            }
            for (file in store.files()) videoItemForUri(Uri.parse(file))?.let { out.add(it) }
            out
        }
    }

    override suspend fun children(parentId: String): Result<List<MediaItem>> = runCatching {
        withContext(Dispatchers.IO) {
            if (parentId == MEDIASTORE_ALL) mediaStoreVideos() else enumerateTreeChildren(Uri.parse(parentId))
        }
    }

    override suspend fun item(itemId: String): Result<MediaItem> = runCatching {
        withContext(Dispatchers.IO) {
            if (itemId == MEDIASTORE_ALL) return@withContext folderItem(MEDIASTORE_ALL, mediaStoreRootTitle())
            val uri = Uri.parse(itemId)
            val mime = resolver.getType(uri)
            if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                folderItem(itemId, displayName(uri) ?: lastSegmentName(uri))
            } else {
                videoItemForUri(uri) ?: videoItem(itemId, displayName(uri) ?: lastSegmentName(uri), null)
            }
        }
    }

    override suspend fun search(query: String): Result<List<MediaItem>> = runCatching {
        withContext(Dispatchers.IO) {
            val needle = query.trim().lowercase()
            if (needle.isEmpty()) return@withContext emptyList<MediaItem>()
            val all = ArrayList<MediaItem>()
            if (hasMediaAccess()) all.addAll(mediaStoreVideos())
            for (folder in store.folders()) {
                runCatching { all.addAll(enumerateRecursive(treeDocumentUri(Uri.parse(folder)), depth = 3)) }
            }
            for (file in store.files()) videoItemForUri(Uri.parse(file))?.let { all.add(it) }
            all.filter { !it.isFolder && it.title.lowercase().contains(needle) }.distinctBy { it.id }
        }
    }

    // ----- SAF tree enumeration -----

    private fun treeDocumentUri(treeUri: Uri): Uri {
        val docId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }
            .getOrElse { DocumentsContract.getDocumentId(treeUri) }
        return DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
    }

    private fun enumerateTreeChildren(treeDocUri: Uri): List<MediaItem> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeDocUri, DocumentsContract.getDocumentId(treeDocUri),
        )
        val out = ArrayList<MediaItem>()
        resolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            ),
            null, null, null,
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameCol = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeCol = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            while (c.moveToNext()) {
                val childDocId = c.getString(idCol) ?: continue
                val name = c.getString(nameCol) ?: continue
                val mime = c.getString(mimeCol)
                val childUri = DocumentsContract.buildDocumentUriUsingTree(treeDocUri, childDocId)
                when {
                    mime == DocumentsContract.Document.MIME_TYPE_DIR -> out.add(folderItem(childUri.toString(), name))
                    LocalMediaRules.isVideoFile(name, mime) -> out.add(videoItem(childUri.toString(), name, null))
                }
            }
        }
        return out.sortedWith(compareByDescending<MediaItem> { it.isFolder }.thenBy { it.title.lowercase() })
    }

    private fun enumerateRecursive(treeDocUri: Uri, depth: Int): List<MediaItem> {
        if (depth < 0) return emptyList()
        val out = ArrayList<MediaItem>()
        for (child in enumerateTreeChildren(treeDocUri)) {
            if (child.isFolder) out.addAll(enumerateRecursive(Uri.parse(child.id), depth - 1)) else out.add(child)
        }
        return out
    }

    private fun videoItemForUri(uri: Uri): MediaItem? {
        val name = displayName(uri) ?: return null
        val mime = resolver.getType(uri)
        if (!LocalMediaRules.isVideoFile(name, mime)) return null
        return videoItem(uri.toString(), name, null)
    }

    // ----- MediaStore -----

    private fun hasMediaAccess(): Boolean = hasAllMediaAccess() || hasSelectedMediaAccess()

    private fun mediaStoreRootTitle(): String =
        if (hasAllMediaAccess()) "All device videos" else "Selected device videos"

    private fun mediaStoreVideos(): List<MediaItem> {
        val out = ArrayList<MediaItem>()
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
        )
        resolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection, null, null,
            "${MediaStore.Video.Media.DATE_ADDED} DESC",
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val durCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            while (c.moveToNext()) {
                val mediaId = c.getLong(idCol)
                val name = c.getString(nameCol) ?: "Video $mediaId"
                val durationSeconds = if (c.isNull(durCol)) null else c.getLong(durCol) / 1000
                val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, mediaId)
                out.add(videoItem(uri.toString(), name, durationSeconds))
            }
        }
        return out
    }

    // ----- helpers -----

    private fun displayName(uri: Uri): String? =
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst() && !it.isNull(0)) it.getString(0) else null
            }
        }.getOrNull()

    private fun lastSegmentName(uri: Uri): String =
        uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':')?.ifBlank { null } ?: "Folder"

    /** The MIME type to advertise to the TV for a local item (best-effort; defaults to a generic video). */
    fun mimeTypeFor(itemId: String): String =
        runCatching { resolver.getType(Uri.parse(itemId)) }.getOrNull()
            ?: LocalMediaRules.mimeForName(itemId)
            ?: "video/mp4"

    private fun folderItem(id: String, name: String) = MediaItem(
        id = id, title = name, year = null, runtimeSeconds = null, overview = null,
        resumePositionSeconds = null, isFolder = true, type = "Folder", sourceId = MediaSourceIds.LOCAL,
        sourceInstanceId = LOCAL_INSTANCE_ID,
    )

    private fun videoItem(id: String, fileName: String, runtimeSeconds: Long?) = MediaItem(
        id = id, title = LocalMediaRules.displayTitle(fileName), year = null,
        runtimeSeconds = runtimeSeconds, overview = null, resumePositionSeconds = null,
        isFolder = false, type = "Video", sourceId = MediaSourceIds.LOCAL,
        sourceInstanceId = LOCAL_INSTANCE_ID,
    )

    private companion object {
        const val MEDIASTORE_ALL = "streamferry://local/all-videos"
        val LOCAL_INSTANCE_ID = SourceInstanceId(LocalSourceBackend.PROVIDER_ID, LocalSourceBackend.DEFAULT_INSTANCE)
    }
}
