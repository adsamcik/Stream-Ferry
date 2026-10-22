package com.adsamcik.streamferry.data.cast

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CastMediaOwnershipTest {
    @Test fun cachedAndRetiredOwnMediaCannotReplaceNewlyLoadedMedia() {
        val ownership = CastMediaOwnership()
        val old = ownership.beginLoad("old", null, null)
        assertTrue(ownership.observe("old", old, interrupted = false))
        val next = ownership.beginLoad("new", "old", old)
        assertFalse(ownership.observe("old", old, interrupted = true))
        assertFalse(ownership.isLost)
        assertTrue(ownership.observe("new", next, interrupted = false))
        assertFalse(ownership.observe(null, null, interrupted = false))
        assertFalse(ownership.isLost)
        assertFalse(ownership.observe("old", old, interrupted = false))
        assertFalse(ownership.isLost)
        assertTrue(ownership.observe("new", next, interrupted = false))
    }

    @Test fun externalMediaOrAnInterruptedOwnedLoadRetiresOwnership() {
        for (replacement in listOf("different-url", "same-url", "interrupted")) {
            val ownership = CastMediaOwnership()
            val ours = ownership.beginLoad("ours", "foreign", "foreign-token")
            assertTrue(ownership.observe("ours", ours, interrupted = false))
            assertFalse(ownership.observe(
                if (replacement == "different-url") "other" else "ours",
                if (replacement == "interrupted") ours else "foreign-token",
                interrupted = replacement == "interrupted",
            ))
            assertTrue(ownership.isLost)
            assertFalse(ownership.observe("ours", ours, interrupted = false))
        }
    }

    @Test fun sameUrlOwnReloadIgnoresRetiredTokenAndRequiresExpectedNewToken() {
        val ownership = CastMediaOwnership()
        val old = ownership.beginLoad("same", null, null)
        assertTrue(ownership.observe("same", old, interrupted = false))
        val next = ownership.beginLoad("same", "same", old)
        assertFalse(ownership.observe("same", old, interrupted = true))
        assertFalse(ownership.isLost)
        assertFalse(ownership.observe("same", "foreign-token", interrupted = false))
        assertFalse(ownership.isLost)
        assertTrue(ownership.observe("same", next, interrupted = false))
        assertFalse(ownership.observe("same", old, interrupted = false))
        assertFalse(ownership.isLost)
        assertTrue(ownership.observe("same", next, interrupted = false))
    }

    @Test fun receiverStrippingOptionalCustomDataStillAcceptsExpectedUrlButNotForeignPresentToken() {
        val ownership = CastMediaOwnership()
        ownership.beginLoad("ours", "old", null)
        assertFalse(ownership.observe("foreign", null, interrupted = false))
        assertTrue(ownership.observe("ours", null, interrupted = false))
        assertFalse(ownership.observe("ours", "foreign-token", interrupted = false))
        assertTrue(ownership.isLost)
    }

    @Test fun strippedRetiredInterruptionCannotCancelSameUrlReloadBeforeAcceptance() {
        val ownership = CastMediaOwnership()
        ownership.beginLoad("same", "same", null)
        assertFalse(ownership.observe("same", null, interrupted = true))
        assertFalse(ownership.isLost)
        assertTrue(ownership.observe("same", null, interrupted = false))
        assertFalse(ownership.observe("same", null, interrupted = true))
        assertTrue(ownership.isLost)
    }

    @Test fun initiallyForeignMediaReturningAfterOurLoadAlwaysRetiresOwnership() {
        for (foreignToken in listOf(null, "foreign-token")) {
            val ownership = CastMediaOwnership()
            val ours = ownership.beginLoad("ours", "foreign", foreignToken)
            assertFalse(ownership.observe("foreign", foreignToken, interrupted = false))
            assertFalse(ownership.isLost)
            assertTrue(ownership.observe("ours", ours, interrupted = false))
            assertFalse(ownership.observe("foreign", foreignToken, interrupted = false))
            assertTrue(ownership.isLost)
        }
    }

    @Test fun rapidOwnReloadsRetainAcceptedAndUnacceptedOwnIdentitiesRegardlessOfCachedSnapshot() {
        for (cachePending in listOf(false, true)) {
            val ownership = CastMediaOwnership()
            val a = ownership.beginLoad("a", null, null)
            assertTrue(ownership.observe("a", a, interrupted = false))
            val b = ownership.beginLoad("b", "a", a)
            val c = ownership.beginLoad("c", if (cachePending) "b" else "a", if (cachePending) b else a)
            assertTrue(ownership.observe("c", c, interrupted = false))
            assertFalse(ownership.observe("a", a, interrupted = true))
            assertFalse(ownership.isLost)
            assertFalse(ownership.observe("b", b, interrupted = false))
            assertFalse(ownership.isLost)
            assertTrue(ownership.observe("c", c, interrupted = false))
        }
    }

    @Test fun manyPendingLoadsRetainOldestOwnershipAndCurrentTokenStillRequiresMatchingUrl() {
        val ownership = CastMediaOwnership()
        val first = ownership.beginLoad("first", null, null)
        assertTrue(ownership.observe("first", first, interrupted = false))
        var latest = first
        repeat(100) { latest = ownership.beginLoad("latest", "first", first) }
        assertTrue(ownership.observe("latest", latest, interrupted = false))
        assertFalse(ownership.observe("first", first, interrupted = true))
        assertFalse(ownership.isLost)
        assertFalse(ownership.observe("foreign-url", latest, interrupted = false))
        assertTrue(ownership.isLost)
    }

    @Test fun resetChangesNamespaceAndRejectsPriorLifecycleTokenAfterNewAcceptance() {
        val ownership = CastMediaOwnership()
        val old = ownership.beginLoad("same", null, null)
        assertTrue(ownership.observe("same", old, interrupted = false))
        ownership.reset()
        val next = ownership.beginLoad("same", "same", old)
        assertNotEquals(old, next)
        assertTrue(ownership.observe("same", next, interrupted = false))
        assertFalse(ownership.observe("same", old, interrupted = false))
        assertTrue(ownership.isLost)
    }
}
