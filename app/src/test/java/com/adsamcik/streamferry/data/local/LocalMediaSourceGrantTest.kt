package com.adsamcik.streamferry.data.local

import android.content.ContentResolver
import android.content.Intent
import android.content.UriPermission
import android.net.Uri
import com.adsamcik.streamferry.logging.DiagnosticsLogger
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocalMediaSourceGrantTest {

    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var store: LocalSourceStore
    private lateinit var resolver: ContentResolver
    private lateinit var source: LocalMediaSource

    @Before fun setUp() {
        store = LocalSourceStore(context).also { it.clear() }
        resolver = mockk(relaxed = true)
        source = LocalMediaSource(
            context = context,
            store = store,
            logger = mockk<DiagnosticsLogger>(relaxed = true),
            hasAllMediaAccess = { false },
            hasSelectedMediaAccess = { false },
            resolver = resolver,
        )
    }

    @After fun tearDown() {
        store.clear()
    }

    @Test fun removeRootReleasesMatchingPersistedReadGrant() {
        val root = Uri.parse("content://provider/tree/root")
        store.addFolder(root.toString())
        every { resolver.persistedUriPermissions } returns listOf(permission(root, read = true))
        every {
            resolver.releasePersistableUriPermission(root, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } just Runs

        source.removeRoot(root.toString())

        assertFalse(root.toString() in store.folders())
        verify(exactly = 1) {
            resolver.releasePersistableUriPermission(root, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    @Test fun removeRootRemovesMatchingFolderAndFileEntries() {
        val root = Uri.parse("content://provider/document/root")
        store.addFolder(root.toString())
        store.addFile(root.toString())
        every { resolver.persistedUriPermissions } returns emptyList()

        source.removeRoot(root.toString())

        assertFalse(root.toString() in store.folders())
        assertFalse(root.toString() in store.files())
    }

    @Test fun removeRootDoesNotReleaseUnrelatedPersistedGrant() {
        val root = Uri.parse("content://provider/tree/root")
        val unrelated = Uri.parse("content://provider/tree/other")
        store.addFolder(root.toString())
        every { resolver.persistedUriPermissions } returns listOf(permission(unrelated, read = true))

        source.removeRoot(root.toString())

        verify(exactly = 0) { resolver.releasePersistableUriPermission(any(), any()) }
    }

    @Test fun removeUnknownSyntheticRootDoesNotInspectPersistedGrants() {
        source.removeRoot("local:all-videos")

        verify(exactly = 0) { resolver.persistedUriPermissions }
        verify(exactly = 0) { resolver.releasePersistableUriPermission(any(), any()) }
    }

    @Test fun clearPersistedAccessReleasesEveryGrantAndClearsRoots() {
        val readRoot = Uri.parse("content://provider/tree/read")
        val writeRoot = Uri.parse("content://provider/document/write")
        store.addFolder(readRoot.toString())
        store.addFile(writeRoot.toString())
        every { resolver.persistedUriPermissions } returns listOf(
            permission(readRoot, read = true),
            permission(writeRoot, read = true, write = true),
        )

        source.clearPersistedAccess()

        assertFalse(store.folders().isNotEmpty())
        assertFalse(store.files().isNotEmpty())
        verify {
            resolver.releasePersistableUriPermission(readRoot, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            resolver.releasePersistableUriPermission(
                writeRoot,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
    }

    @Test fun clearPersistedAccessStillClearsRootsWhenAReleaseFails() {
        val root = Uri.parse("content://provider/tree/root")
        store.addFolder(root.toString())
        every { resolver.persistedUriPermissions } returns listOf(permission(root, read = true))
        every {
            resolver.releasePersistableUriPermission(root, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } throws SecurityException("provider refused")

        assertFailsWith<IllegalStateException> { source.clearPersistedAccess() }

        assertFalse(store.folders().isNotEmpty())
    }

    private fun permission(uri: Uri, read: Boolean, write: Boolean = false): UriPermission =
        mockk {
            every { this@mockk.uri } returns uri
            every { isReadPermission } returns read
            every { isWritePermission } returns write
        }
}
