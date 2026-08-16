package com.adsamcik.streamferry.diagnostics

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ReportExportTest {

    @Test
    fun writesUtf8AndFlushesTheDestination() {
        val output = RecordingOutputStream()

        val result = ReportExport.writeUtf8("Příliš žluťoučký") { output }

        assertTrue(result.isSuccess)
        assertContentEquals("Příliš žluťoučký".toByteArray(Charsets.UTF_8), output.bytes.toByteArray())
        assertEquals(1, output.flushCount)
    }

    @Test
    fun missingDocumentStreamIsAFailure() {
        val result = ReportExport.writeUtf8("report") { null }

        assertIs<IOException>(result.exceptionOrNull())
    }

    @Test
    fun writeFailureIsReturnedToTheUi() {
        val result = ReportExport.writeUtf8("report") {
            object : OutputStream() {
                override fun write(b: Int) = throw IOException("destination full")
            }
        }

        assertEquals("destination full", result.exceptionOrNull()?.message)
    }

    private class RecordingOutputStream : OutputStream() {
        val bytes = ByteArrayOutputStream()
        var flushCount = 0

        override fun write(b: Int) = bytes.write(b)

        override fun flush() {
            flushCount++
        }
    }
}
