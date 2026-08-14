package com.adsamcik.streamferry.core

import com.adsamcik.streamferry.core.dlna.DidlLite
import com.adsamcik.streamferry.core.dlna.SecureXml
import com.adsamcik.streamferry.core.dlna.SsdpCandidateRejection
import com.adsamcik.streamferry.core.dlna.SsdpParser
import com.adsamcik.streamferry.core.hls.HlsRewriter
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CorePolicyTest {

    // --- HLS rewriter ---
    @Test fun rewritesSegmentAndKeyUris() {
        val r = HlsRewriter("http://10.0.0.5:5000/session/ID")
        val playlist = """
            #EXTM3U
            #EXT-X-KEY:METHOD=AES-128,URI="https://jelly/key?api_key=secret"
            #EXTINF:6.0,
            https://jelly/segment0.ts?api_key=secret
        """.trimIndent()
        val out = r.rewrite(playlist) { "ENC" } // opaque encoder
        assertFalse(out.contains("jelly"))
        assertFalse(out.contains("secret"))
        assertTrue(out.contains("http://10.0.0.5:5000/session/ID/stream?seg=ENC"))
        assertTrue(out.contains("#EXTINF:6.0,"))
    }

    @Test fun rejectsPlaylistWhenProxyUrlsExceedOutputBudget() {
        val rewriter = HlsRewriter(
            proxyBase = "http://10.0.0.5:5000/session/ID",
            maxOutputChars = 128,
        )
        val playlist = buildString {
            appendLine("#EXTM3U")
            repeat(4) { appendLine("a") }
        }

        assertFailsWith<IllegalArgumentException> {
            rewriter.rewrite(playlist) { "opaque-token" }
        }
    }

    @Test fun appliesOutputBudgetWhileRewritingTagAttributes() {
        val rewriter = HlsRewriter(
            proxyBase = "http://10.0.0.5:5000/session/ID",
            maxOutputChars = 128,
        )
        val playlist = "#EXT-X-MEDIA:URI=\"a\",URI=\"b\",URI=\"c\""

        assertFailsWith<IllegalArgumentException> {
            rewriter.rewrite(playlist) { "opaque-token" }
        }
    }

    @Test fun hlsUriPreflightUsesTheSamePlainAndAttributeReferencesAsRewrite() {
        val rewriter = HlsRewriter("http://10.0.0.5:5000/session/ID")
        val playlist = """
            #EXTM3U
            #EXT-X-KEY:METHOD=AES-128,URI="key"
            segment
            segment
        """.trimIndent()

        assertEquals(listOf("key", "segment"), rewriter.uriReferences(playlist, maxDistinct = 2))
        assertFailsWith<IllegalArgumentException> {
            rewriter.uriReferences("a\nb\nc\n", maxDistinct = 2)
        }
    }

    // --- DIDL-Lite ---
    @Test fun didlEscapesAndUsesProxyUrlOnly() {
        val xml = DidlLite.build(
            proxyUrl = "http://10.0.0.5:5000/session/ID/stream",
            title = "Movie & <Friends>",
            mimeType = "video/mp4",
            durationSecs = 3661,
        )
        assertTrue(xml.contains("Movie &amp; &lt;Friends&gt;"))
        assertTrue(xml.contains("http://10.0.0.5:5000/session/ID/stream"))
        assertTrue(xml.contains("1:01:01"))
        assertTrue(xml.contains("DLNA.ORG_OP=00"))
        assertFalse(xml.contains("<Friends>"))
    }

    @Test fun didlAdvertisesByteSeekOnlyForRangeCapableOutput() {
        val xml = DidlLite.build(
            proxyUrl = "http://10.0.0.5:5000/session/ID/stream",
            title = "Direct play",
            mimeType = "video/mp4",
            byteSeekable = true,
        )
        assertTrue(xml.contains("DLNA.ORG_OP=01"))
    }

    // --- SSDP parsing ---
    @Test fun parsesRendererResponse() {
        val raw = "HTTP/1.1 200 OK\r\nLOCATION: http://192.168.1.5:7676/desc.xml\r\n" +
            "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\nUSN: uuid:abc::MediaRenderer\r\n\r\n"
        val msg = SsdpParser.parse(raw)
        assertNotNull(msg)
        assertEquals("http://192.168.1.5:7676/desc.xml", msg.location)
        assertTrue(msg.isSuccessfulSearchResponse())
        assertEquals(null, msg.candidateRejection())
        assertTrue(msg.isMediaRenderer())
        assertTrue(SsdpParser.isAcceptableLocation(msg.location))
    }

    @Test fun acceptsSuccessfulSsdpStatusLineWithOrWithoutReasonPhrase() {
        assertTrue(SsdpParser.isSuccessfulSearchResponse("HTTP/1.1 200 OK"))
        assertTrue(SsdpParser.isSuccessfulSearchResponse("HTTP/1.0  200"))
        assertTrue(SsdpParser.isSuccessfulSearchResponse("http/1.1\t200\tOK"))
    }

    @Test fun rejectsNonSuccessfulOrMalformedSsdpStartLine() {
        assertFalse(SsdpParser.isSuccessfulSearchResponse("HTTP/1.1 404 Not Found"))
        assertFalse(SsdpParser.isSuccessfulSearchResponse("HTTP/2 200 OK"))
        assertFalse(SsdpParser.isSuccessfulSearchResponse("NOTIFY * HTTP/1.1"))
        assertFalse(SsdpParser.isSuccessfulSearchResponse("HTTP/1.1 200 OK\r\nLOCATION: http://10.0.0.2/"))
    }

    @Test fun reportsWhyRendererResponseCannotBecomeCandidate() {
        val wrongStatus = SsdpParser.parse(
            "HTTP/1.1 404 Not Found\r\nST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n" +
                "USN: uuid:abc::urn:schemas-upnp-org:device:MediaRenderer:1\r\n" +
                "LOCATION: http://10.0.0.2/desc.xml\r\n\r\n",
        )
        assertNotNull(wrongStatus)
        assertEquals(SsdpCandidateRejection.STATUS, wrongStatus.candidateRejection())

        val unsafe = wrongStatus.copy(
            startLine = "HTTP/1.1 200 OK",
            headers = wrongStatus.headers + ("LOCATION" to "http://8.8.8.8/desc.xml"),
        )
        assertEquals(SsdpCandidateRejection.UNSAFE_LOCATION, unsafe.candidateRejection())
    }

    @Test fun rejectsNonHttpLocation() {
        assertFalse(SsdpParser.isAcceptableLocation("file:///etc/passwd"))
        assertFalse(SsdpParser.isAcceptableLocation(null))
    }

    @Test fun rejectsAmbiguousOrNonRoutableHttpLocation() {
        assertFalse(SsdpParser.isAcceptableLocation("http://user@10.0.0.2/desc.xml"))
        assertFalse(SsdpParser.isAcceptableLocation("http://10.0.0.2/desc.xml#other"))
        assertFalse(SsdpParser.isAcceptableLocation("http://10.0.0.2:0/desc.xml"))
        assertFalse(SsdpParser.isAcceptableLocation("http://localhost/desc.xml"))
    }

    @Test fun rejectsRemoteLocationToPreventSsrf() {
        // A hostile LAN device must not lure the control point into fetching a remote description.
        assertFalse(SsdpParser.isAcceptableLocation("http://evil.example.com/desc.xml"))
        assertFalse(SsdpParser.isAcceptableLocation("http://8.8.8.8/desc.xml"))
        // Legitimate LAN renderers are still accepted.
        assertTrue(SsdpParser.isAcceptableLocation("http://192.168.1.5:7676/desc.xml"))
        assertTrue(SsdpParser.isAcceptableLocation("http://10.0.0.9:2870/dmr.xml"))
        assertTrue(SsdpParser.isAcceptableLocation("http://living-room-tv/desc.xml"))
        assertTrue(SsdpParser.isAcceptableLocation("http://living-room-tv.local/desc.xml"))
    }

    @Test fun ssdpBoundsOversized() {
        assertEquals(null, SsdpParser.parse("X".repeat(SsdpParser.MAX_MESSAGE_LEN + 1)))
    }

    // --- Secure XML / XXE ---
    @Test fun secureXmlBlocksExternalEntity() {
        val malicious = """<?xml version="1.0"?>
            <!DOCTYPE foo [ <!ENTITY xxe SYSTEM "file:///etc/passwd"> ]>
            <root>&xxe;</root>""".trimIndent()
        // disallow-doctype-decl => parsing throws rather than resolving the entity.
        assertFailsWith<Exception> { SecureXml.parse(malicious) }
    }

    @Test fun secureXmlParsesNormalDoc() {
        val doc = SecureXml.parse("<root><a>hi</a></root>")
        assertEquals("root", doc.documentElement.nodeName)
    }

    @Test fun secureXmlBytesHonorDeclaredDocumentEncoding() {
        val xml = """<?xml version="1.0" encoding="ISO-8859-1"?><root><name>café</name></root>"""
            .toByteArray(StandardCharsets.ISO_8859_1)
        val doc = SecureXml.parse(xml)

        assertEquals("café", doc.getElementsByTagName("name").item(0).textContent)
    }

    @Test fun secureXmlBytesBlockUtf16Doctype() {
        val malicious = """<?xml version="1.0" encoding="UTF-16"?><!DOCTYPE root><root/>"""
            .toByteArray(StandardCharsets.UTF_16LE)

        assertFailsWith<SecurityException> { SecureXml.parse(malicious) }
    }

    @Test fun secureXmlRejectsOversized() {
        val big = "<root>" + "a".repeat(SecureXml.MAX_XML_BYTES) + "</root>"
        assertFailsWith<SecureXml.XmlTooLargeException> { SecureXml.parse(big) }
        assertFailsWith<SecureXml.XmlTooLargeException> {
            SecureXml.parse(ByteArray(SecureXml.MAX_XML_BYTES + 1))
        }
    }
}
