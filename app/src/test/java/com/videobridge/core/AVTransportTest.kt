package com.videobridge.core

import com.videobridge.core.dlna.SecureXml
import com.videobridge.data.dlna.AVTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AVTransportTest {

    private val deviceDesc = """
        <?xml version="1.0"?>
        <root xmlns="urn:schemas-upnp-org:device-1-0">
          <device>
            <friendlyName>Living Room TV</friendlyName>
            <serviceList>
              <service>
                <serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>
                <controlURL>/rc/control</controlURL>
              </service>
              <service>
                <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
                <controlURL>/avt/control</controlURL>
              </service>
            </serviceList>
          </device>
        </root>
    """.trimIndent()

    @Test fun resolvesAbsoluteControlUrlFromRelative() {
        val doc = SecureXml.parse(deviceDesc)
        val url = AVTransport.findControlUrl(doc, "http://192.168.1.50:7676/desc.xml")
        assertEquals("http://192.168.1.50:7676/avt/control", url)
    }

    @Test fun returnsNullWhenNoAvTransport() {
        val noAvt = "<root><device><serviceList></serviceList></device></root>"
        val doc = SecureXml.parse(noAvt)
        assertNull(AVTransport.findControlUrl(doc, "http://192.168.1.50:7676/desc.xml"))
    }

    @Test fun rejectsAbsoluteControlUrlToPublicHost() {
        // A malicious renderer at a private LOCATION can still advertise an absolute controlURL pointing
        // at a public host; SOAP carries the proxy URL, so such a control URL must be rejected (SSRF).
        val evil = """
            <root xmlns="urn:schemas-upnp-org:device-1-0"><device><serviceList><service>
              <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
              <controlURL>http://attacker.example.com/control</controlURL>
            </service></serviceList></device></root>
        """.trimIndent()
        val doc = SecureXml.parse(evil)
        assertNull(AVTransport.findControlUrl(doc, "http://192.168.1.50:7676/desc.xml"))
    }

    @Test fun parsesTransportStateAndStatus() {
        val soap = """
            <s:Envelope><s:Body><u:GetTransportInfoResponse>
              <CurrentTransportState>PLAYING</CurrentTransportState>
              <CurrentTransportStatus>OK</CurrentTransportStatus>
              <CurrentSpeed>1</CurrentSpeed>
            </u:GetTransportInfoResponse></s:Body></s:Envelope>
        """.trimIndent()
        val info = AVTransport.parseTransportInfo(soap)
        assertEquals("PLAYING", info.state)
        assertEquals("OK", info.status)
    }

    @Test fun detectsErrorOccurredStatus() {
        // A renderer that can't decode the media parks in STOPPED with an ERROR_OCCURRED status — the
        // signal the poll loop uses to trigger a transcode fallback.
        val soap = "<CurrentTransportState>STOPPED</CurrentTransportState>" +
            "<CurrentTransportStatus>ERROR_OCCURRED</CurrentTransportStatus>"
        val info = AVTransport.parseTransportInfo(soap)
        assertEquals("STOPPED", info.state)
        assertEquals("ERROR_OCCURRED", info.status)
    }

    @Test fun parsesNamespacePrefixedTags() {
        val soap = "<u:CurrentTransportState>TRANSITIONING</u:CurrentTransportState>" +
            "<u:CurrentTransportStatus>OK</u:CurrentTransportStatus>"
        val info = AVTransport.parseTransportInfo(soap)
        assertEquals("TRANSITIONING", info.state)
        assertEquals("OK", info.status)
    }

    @Test fun missingFieldsParseToNull() {
        val info = AVTransport.parseTransportInfo("<Something>else</Something>")
        assertNull(info.state)
        assertNull(info.status)
    }
}
