package com.adsamcik.streamferry.core

import com.adsamcik.streamferry.core.http.BoundedBody
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BoundedBodyTest {

    @Test fun readsBodyUnderCap() {
        val data = "hello world".toByteArray()
        val out = BoundedBody.readAtMost(ByteArrayInputStream(data), maxBytes = 1024)
        assertTrue(out != null && out.contentEquals(data))
    }

    @Test fun allowsExactlyMaxBytes() {
        val data = ByteArray(100) { it.toByte() }
        val out = BoundedBody.readAtMost(ByteArrayInputStream(data), maxBytes = 100)
        assertEquals(100, out?.size)
    }

    @Test fun rejectsOverCap() {
        val data = ByteArray(101)
        assertNull(BoundedBody.readAtMost(ByteArrayInputStream(data), maxBytes = 100))
    }

    @Test fun emptyStreamYieldsEmptyArray() {
        val out = BoundedBody.readAtMost(ByteArrayInputStream(ByteArray(0)), maxBytes = 10)
        assertEquals(0, out?.size)
    }
}
