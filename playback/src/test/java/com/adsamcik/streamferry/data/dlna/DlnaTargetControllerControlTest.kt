package com.adsamcik.streamferry.data.dlna

import com.adsamcik.streamferry.diagnostics.NetworkInfoProvider
import com.adsamcik.streamferry.source.api.DiagnosticSink
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

class DlnaTargetControllerControlTest {

    private fun controller() = DlnaTargetController(
        logger = mockk<DiagnosticSink>(relaxed = true),
        network = mockk<NetworkInfoProvider>(relaxed = true),
    )

    @Test
    fun `transport command fails when no renderer is connected`() = runTest {
        assertFailsWith<IllegalStateException> { controller().play() }
    }

    @Test
    fun `volume command fails when no renderer is connected`() = runTest {
        assertFailsWith<IllegalStateException> { controller().setVolume(0.5f) }
    }
}
