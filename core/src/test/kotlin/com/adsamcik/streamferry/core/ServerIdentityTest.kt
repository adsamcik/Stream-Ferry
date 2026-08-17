package com.adsamcik.streamferry.core

import com.adsamcik.streamferry.core.server.ServerIdentity
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServerIdentityTest {

    @Test fun firstConnectTrustsAndPins() {
        assertTrue(ServerIdentity.matches(pinnedId = null, fetchedId = "abc"))
        assertFalse(ServerIdentity.isMismatch(null, "abc"))
    }

    @Test fun samePinMatches() {
        assertTrue(ServerIdentity.matches("abc", "abc"))
        assertFalse(ServerIdentity.isMismatch("abc", "abc"))
    }

    @Test fun differentIdIsBlocked() {
        assertFalse(ServerIdentity.matches("abc", "xyz"))
        assertTrue(ServerIdentity.isMismatch("abc", "xyz"))
    }

    @Test fun missingFetchedIdAllowedNoTargetedAttack() {
        assertTrue(ServerIdentity.matches("abc", null))
        assertTrue(ServerIdentity.matches("abc", ""))
        assertFalse(ServerIdentity.isMismatch("abc", null))
    }
}
