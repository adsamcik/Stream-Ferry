package com.videobridge.data.jellyfin

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServerErrorReasonTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun extract(body: String?, contentType: String? = "application/json") =
        ServerErrorReason.extract(body, contentType, json)

    @Test
    fun extractsDetailFromProblemDetailsJson() {
        val body = """{"title":"An error occurred","detail":"Library scan is already running","status":500}"""
        assertEquals("Library scan is already running", extract(body))
    }

    @Test
    fun detailWinsOverTitleByPriority() {
        val body = """{"title":"Server Error","detail":"User is not allowed to access this item"}"""
        assertEquals("User is not allowed to access this item", extract(body))
    }

    @Test
    fun extractsDotNetExceptionMessage() {
        val body = """{"Message":"An error has occurred.","ExceptionMessage":"Object reference not set"}"""
        assertEquals("An error has occurred.", extract(body))
    }

    @Test
    fun fallsBackToPlainTextSnippet() {
        assertEquals("Internal Server Error", extract("  Internal Server Error  ", contentType = "text/plain"))
    }

    @Test
    fun parsesJsonByLeadingBraceEvenWithoutContentType() {
        assertEquals("nope", extract("""{"error":"nope"}""", contentType = null))
    }

    @Test
    fun blankAndNullBodiesReturnNull() {
        assertNull(extract(null))
        assertNull(extract("   "))
        assertNull(extract("{}"))
    }

    @Test
    fun malformedJsonFallsBackToRedactedText() {
        // Not valid JSON despite the content-type — must not throw, falls back to the text snippet.
        val reason = extract("""{"detail": "unterminated""", contentType = "application/json")
        assertTrue(reason != null && reason.contains("unterminated"))
    }

    @Test
    fun redactsTokensAndUrlsInTheReason() {
        val body = """{"detail":"failed to reach http://10.0.0.5:8096/Items?api_key=supersecret123"}"""
        val reason = extract(body)!!
        assertTrue(!reason.contains("supersecret123"), "token must be redacted: $reason")
        assertTrue(!reason.contains("8096"), "URL port/query must be redacted: $reason")
    }

    @Test
    fun collapsesWhitespaceAndCapsLength() {
        val longDetail = "x".repeat(400)
        val reason = extract("""{"detail":"line1\n\n   line2   $longDetail"}""")!!
        assertTrue(reason.length <= 240, "reason length ${reason.length} should be capped at 240")
        assertTrue(!reason.contains("\n"), "newlines should be collapsed")
        assertTrue(!reason.contains("   "), "runs of whitespace should be collapsed")
    }
}
