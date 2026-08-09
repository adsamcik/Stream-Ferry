package com.adsamcik.streamferry.diagnostics

import java.io.IOException
import java.io.OutputStream

/** Checked, platform-independent report writing used by the document-picker callbacks. */
internal object ReportExport {

    fun writeUtf8(text: String, openOutput: () -> OutputStream?): Result<Unit> = runCatching {
        val output = openOutput() ?: throw IOException("The selected destination could not be opened.")
        output.use {
            it.write(text.toByteArray(Charsets.UTF_8))
            it.flush()
        }
    }
}
