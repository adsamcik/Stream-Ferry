package com.adsamcik.streamferry.core

import com.adsamcik.streamferry.core.redaction.LogRedactor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LogRedactorTest {

    @Test fun redactsJellyfinUrlWithToken() {
        val s = "loading http://jelly.example.com:8096/Videos/123/stream.mp4?api_key=SECRETTOKEN123 now"
        val out = LogRedactor.redact(s)
        assertFalse(out.contains("SECRETTOKEN123"))
        assertFalse(out.contains("8096"))
        assertFalse(out.contains("jelly.example.com")) // host is masked too
    }

    @Test fun redactsAuthorizationHeader() {
        val out = LogRedactor.redact("Authorization: MediaBrowser Token=\"abc123\"")
        assertFalse(out.contains("abc123"))
        assertTrue(out.contains("REDACTED"))
    }

    @Test fun redactsProxySessionPath() {
        val out = LogRedactor.redactUrl("http://10.0.0.5:54213/session/abcdef0123456789/stream")
        assertEquals("http://<host>:<port>/session/<redacted>", out)
    }

    @Test fun redactsSensitiveQueryParams() {
        val out = LogRedactor.redactQuery("MediaSourceId=5&api_key=secret&PlaySessionId=xyz")
        assertTrue(out!!.contains("MediaSourceId=5"))
        assertFalse(out.contains("secret"))
        assertFalse(out.contains("xyz"))
    }

    @Test fun nullSafe() {
        assertEquals("", LogRedactor.redact(null))
        assertEquals(null, LogRedactor.redactQuery(null))
    }

    @Test fun masksToken() {
        assertFalse(LogRedactor.mask("supersecret").contains("supersecret"))
    }

    @Test fun normalizesControlCharactersAndBoundsUntrustedLogText() {
        val out = LogRedactor.redact("title\u0000\nwith\tcontrols")
        assertEquals("title with controls", out)

        val long = LogRedactor.redact("x".repeat(20_000))
        assertEquals(8 * 1024, long.toByteArray(Charsets.UTF_8).size)
    }
}
