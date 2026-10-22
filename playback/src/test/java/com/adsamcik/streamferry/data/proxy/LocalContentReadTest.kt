package com.adsamcik.streamferry.data.proxy

import java.io.Closeable
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LocalContentReadTest {
    @Test fun `cancelling pending provider open also closes a descriptor returned late`() {
        val cancelled = AtomicInteger()
        val closed = AtomicInteger()
        val read = LocalContentRead { cancelled.incrementAndGet() }
        read.close()
        assertFailsWith<IOException> { read.register(Closeable { closed.incrementAndGet() }) }
        read.close()
        assertEquals(1, cancelled.get())
        assertEquals(1, closed.get())
    }

    @Test fun `teardown closes registered channel and unblocks an in progress read`() {
        val reading = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val read = LocalContentRead {}
        read.register(Closeable { closed.countDown() })
        val worker = thread {
            reading.countDown()
            closed.await(3, TimeUnit.SECONDS)
            finished.countDown()
        }
        try {
            assertTrue(reading.await(2, TimeUnit.SECONDS))
            read.close()
            assertTrue(finished.await(2, TimeUnit.SECONDS))
        } finally { read.close(); worker.join(3000) }
    }
}
