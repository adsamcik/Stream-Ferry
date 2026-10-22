package com.adsamcik.streamferry.data.download

import com.adsamcik.streamferry.domain.UserSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DownloadSessionRestorationTest {
    @Test
    fun `sticky restart waits for verified active account without an activity`() = runTest {
        val ready = CompletableDeferred<Result<UserSession>>()
        var current: UserSession? = null
        val restored = async { restoreDownloadOwner({ ready.await() }, { current }) }
        runCurrent()
        assertTrue(!restored.isCompleted)
        current = UserSession("user", "server")
        ready.complete(Result.success(UserSession("user", "server")))
        assertEquals(DownloadOwner("server", "user"), restored.await())
    }

    @Test
    fun `failed verification or changed active account never resumes another owner`() = runTest {
        assertNull(restoreDownloadOwner({ Result.failure(IllegalStateException("offline")) }, { null }))
        val previous = UserSession("old-user", "old-server")
        val current = UserSession("new-user", "new-server")
        assertNull(restoreDownloadOwner({ Result.success(previous) }, { current }))
    }

    @Test
    fun `stop during restoration prevents a late successful verification from resuming downloads`() = runTest {
        val ready = CompletableDeferred<Result<UserSession>>()
        val current = UserSession("user", "server")
        val restored = async { restoreDownloadOwner({ ready.await() }, { current }) }
        runCurrent()
        restored.cancel()
        ready.complete(Result.success(current))
        restored.join()
        assertTrue(restored.isCancelled)
    }
}
