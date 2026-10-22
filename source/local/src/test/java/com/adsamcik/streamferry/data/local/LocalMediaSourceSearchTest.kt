package com.adsamcik.streamferry.data.local

import android.content.ContentResolver
import android.content.ContentValues
import android.content.UriPermission
import android.database.MatrixCursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.adsamcik.streamferry.source.api.DiagnosticSink
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class LocalMediaSourceSearchTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var store: LocalSourceStore
    private lateinit var resolver: ContentResolver
    private lateinit var source: LocalMediaSource
    private var now = 0L
    private var mediaAccess = false
    private var selectedMediaAccess = false
    private var grants = emptySet<Uri>()
    private val names = mutableMapOf<Uri, String>()
    private val reads = mutableMapOf<Uri, Int>()
    private val treeRows = mutableMapOf<Uri, List<Array<String>>>()
    private var mediaRows = listOf("First.Movie.mp4")
    private val mediaQueries = mutableListOf<Pair<String?, List<String>?>>()

    @Before fun setUp() {
        store = LocalSourceStore(context).also { it.clear() }
        resolver = mockk(relaxed = true)
        every { resolver.persistedUriPermissions } answers {
            grants.map { granted -> mockk<UriPermission> {
                every { uri } returns granted
                every { isReadPermission } returns true
                every { isWritePermission } returns false
            } }
        }
        every { resolver.getType(any()) } returns "video/mp4"
        every { resolver.query(any(), any(), any(), any(), any()) } answers {
            val uri = firstArg<Uri>()
            reads[uri] = (reads[uri] ?: 0) + 1
            when {
                uri == MediaStore.Video.Media.EXTERNAL_CONTENT_URI -> {
                    mediaQueries += thirdArg<String?>() to arg<Array<String>?>(3)?.toList()
                    SQLiteDatabase.create(null).use { db ->
                        db.execSQL("CREATE TABLE videos (_id INTEGER, _display_name TEXT, duration INTEGER)")
                        mediaRows.forEachIndexed { index, name ->
                            db.insert("videos", null, ContentValues().apply {
                                put("_id", index + 1L)
                                put("_display_name", name)
                                put("duration", 1_000L)
                            })
                        }
                        MatrixCursor(arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME,
                            MediaStore.Video.Media.DURATION)).apply {
                            db.rawQuery("SELECT * FROM videos WHERE ${thirdArg<String?>() ?: "1"}",
                                arg<Array<String>?>(3)).use { cursor ->
                                while (cursor.moveToNext()) addRow(arrayOf(cursor.getLong(0), cursor.getString(1), cursor.getLong(2)))
                            }
                        }
                    }
                }
                uri in treeRows -> MatrixCursor(arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE)).apply {
                    treeRows.getValue(uri).forEach { addRow(it) }
                }
                else -> MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply {
                    names[uri]?.let { addRow(arrayOf(it)) }
                }
            }
        }
        source = LocalMediaSource(context, store, mockk<DiagnosticSink>(relaxed = true),
            { mediaAccess }, { selectedMediaAccess }, resolver, { now })
    }

    @After fun tearDown() { store.clear() }

    @Test fun persistedFileMetadataIsReusedAcrossQueriesAndExpires() = runBlocking {
        val file = Uri.parse("content://provider/document/video")
        store.addFile(file.toString())
        grants = setOf(file)
        names[file] = "First.Movie.mp4"
        assertEquals("First Movie", source.search("first").getOrThrow().single().title)
        assertEquals("First Movie", source.search("movie").getOrThrow().single().title)
        assertEquals(1, reads[file])

        names[file] = "Replacement.Movie.mp4"
        now = 30_000L
        assertEquals("Replacement Movie", source.search("replacement").getOrThrow().single().title)
        assertEquals(2, reads[file])
    }

    @Test fun revokedGrantsAndRootChangesDiscardCachedMetadataWithoutHidingReadableSiblings() = runBlocking {
        val first = Uri.parse("content://provider/document/first")
        val sibling = Uri.parse("content://provider/document/sibling")
        store.addFile(first.toString())
        store.addFile(sibling.toString())
        grants = setOf(first, sibling)
        names[first] = "First.Movie.mp4"
        names[sibling] = "Sibling.Movie.mp4"
        assertEquals(2, source.search("movie").getOrThrow().size)

        grants = setOf(sibling)
        names.remove(first)
        assertEquals(listOf("Sibling Movie"), source.search("movie").getOrThrow().map { it.title })
        assertEquals(2, reads[sibling])

        grants = setOf(first, sibling)
        names[first] = "Restored.Movie.mp4"
        assertEquals("Restored Movie", source.search("restored").getOrThrow().single().title)
        source.removeRoot(first.toString())
        assertTrue(source.search("restored").getOrThrow().isEmpty())
        source.addFiles(listOf(first))
        names[first] = "Repicked.Movie.mp4"
        assertEquals("Repicked Movie", source.search("repicked").getOrThrow().single().title)
    }

    @Test fun transientGrantsAreNotCachedAndBrokenRootsDoNotHideOtherFiles() = runBlocking {
        val file = Uri.parse("content://provider/document/video")
        val broken = Uri.parse("content://provider/document/broken")
        store.addFile(file.toString())
        store.addFile(broken.toString())
        names[file] = "First.Movie.mp4"
        names[broken] = "Broken.Movie.mp4"
        every { resolver.getType(broken) } throws SecurityException("revoked")
        assertEquals("First Movie", source.search("movie").getOrThrow().single().title)
        names[file] = "Updated.Movie.mp4"
        assertEquals("Updated Movie", source.search("movie").getOrThrow().single().title)
        assertEquals(2, reads[file])
    }

    @Test fun grantedFolderEnumerationIsReusedButClearingAccessRemovesItsMetadata() = runBlocking {
        val tree = Uri.parse("content://provider/tree/root")
        val rootDoc = DocumentsContract.buildDocumentUriUsingTree(tree, "root")
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(rootDoc, "root")
        store.addFolder(tree.toString())
        grants = setOf(tree)
        treeRows[children] = listOf(arrayOf("video", "First.Movie.mp4", "video/mp4"))
        assertEquals("First Movie", source.search("first").getOrThrow().single().title)
        assertEquals("First Movie", source.search("movie").getOrThrow().single().title)
        assertEquals(1, reads[children])
        source.clearPersistedAccess()
        assertTrue(source.search("movie").getOrThrow().isEmpty())
    }

    @Test fun oversizedFolderMetadataIsNotRetained() = runBlocking {
        val tree = Uri.parse("content://provider/tree/large")
        val rootDoc = DocumentsContract.buildDocumentUriUsingTree(tree, "large")
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(rootDoc, "large")
        store.addFolder(tree.toString())
        grants = setOf(tree)
        treeRows[children] = (0..2_000).map { arrayOf("video$it", "Movie$it.mp4", "video/mp4") }
        assertEquals(2_001, source.search("movie").getOrThrow().size)
        treeRows[children] = listOf(arrayOf("updated", "Changed.Movie.mp4", "video/mp4"))
        assertEquals("Changed Movie", source.search("changed").getOrThrow().single().title)
        assertEquals(2, reads[children])
    }

    @Test fun mediaStoreUsesEscapedFilenameCandidatesAndLivePermissionScope() = runBlocking {
        selectedMediaAccess = true
        mediaRows = listOf("My.Movie.mp4", "My.Other.Movie.mp4")
        assertEquals("My Movie", source.search("my movie").getOrThrow().single().title)
        assertTrue(mediaQueries.single().first!!.contains("LIKE ? ESCAPE"))
        assertEquals(listOf("%my%movie%"), mediaQueries.single().second)
        mediaRows = listOf("50%.Movie.mp4", "500.Movie.mp4")
        assertEquals("50% Movie", source.search("50%").getOrThrow().single().title)
        assertEquals(listOf("%50\\%%"), mediaQueries.last().second)
        mediaRows = listOf("Back\\slash.Movie.mp4", "Backslash.Movie.mp4")
        assertEquals("Back\\slash Movie", source.search("back\\slash").getOrThrow().single().title)

        // Selected-media scope can change its visible rows without changing its permission boolean.
        mediaRows = listOf("New.Movie.mp4")
        assertEquals("New Movie", source.search("movie").getOrThrow().single().title)
        selectedMediaAccess = false
        val before = mediaQueries.size
        assertTrue(source.search("movie").getOrThrow().isEmpty())
        assertEquals(before, mediaQueries.size)
    }

    @Test fun unicodeSearchKeepsUnicodeFriendlyTitleMatching() = runBlocking {
        mediaAccess = true
        mediaRows = listOf("Été.Movie.mp4", "Kelvin.Movie.mp4")
        assertEquals("Été Movie", source.search("été").getOrThrow().single().title)
        assertNull(mediaQueries.last().first)
        assertNull(mediaQueries.last().second)
        assertEquals("Kelvin Movie", source.search("kelvin").getOrThrow().single().title)
        assertTrue(mediaQueries.last().first!!.contains("GLOB '*[^ -~]*'"))
    }
}
