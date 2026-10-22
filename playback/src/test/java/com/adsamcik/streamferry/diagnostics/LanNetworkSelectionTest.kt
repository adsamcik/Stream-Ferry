package com.adsamcik.streamferry.diagnostics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LanNetworkSelectionTest {
    @Test fun newSessionsPreferWifiButExistingReceiverNetworkSurvivesAnotherLan() {
        assertEquals("wifi", chooseLanNetwork(null, listOf("wifi"), listOf("ethernet")))
        assertEquals("ethernet", chooseLanNetwork("ethernet", listOf("wifi"), listOf("ethernet")))
        assertEquals("wifi", chooseLanNetwork("wifi", listOf("wifi"), listOf("ethernet")))
    }

    @Test fun losingTheRetainedNetworkAllowsFreshSelectionAndNoNetworkIsNull() {
        assertEquals("wifi", chooseLanNetwork("lost", listOf("wifi"), listOf("ethernet")))
        assertEquals("ethernet", chooseLanNetwork("wifi", emptyList(), listOf("ethernet")))
        assertNull(chooseLanNetwork("lost", emptyList<String>(), emptyList()))
    }
}
