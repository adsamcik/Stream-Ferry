package com.adsamcik.streamferry.core

import com.adsamcik.streamferry.core.dlna.RendererDescriptionParser
import com.adsamcik.streamferry.core.dlna.SecureXml
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class RendererDescriptionParserTest {

    @Test fun selectsMatchingRendererAndHighestUnambiguousServiceVersion() {
        val description = RendererDescriptionParser.parse(
            SecureXml.parse(
                """
                <root xmlns="urn:schemas-upnp-org:device-1-0">
                  <URLBase>http://living-room-tv.local:9197/</URLBase>
                  <device>
                    <deviceType>urn:schemas-upnp-org:device:MediaRenderer:2</deviceType>
                    <friendlyName>Living Room TV</friendlyName>
                    <manufacturer>Example</manufacturer>
                    <modelName>Model 2</modelName>
                    <UDN>uuid:renderer</UDN>
                    <serviceList>
                      <service>
                        <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
                        <controlURL>/avt/v1</controlURL>
                      </service>
                      <service>
                        <serviceType>urn:schemas-upnp-org:service:AVTransport:3</serviceType>
                        <controlURL>/avt/v3</controlURL>
                      </service>
                    </serviceList>
                  </device>
                </root>
                """.trimIndent(),
            ),
            "http://10.0.1.44:9197/description.xml",
            "uuid:renderer::urn:schemas-upnp-org:device:MediaRenderer:1",
        )

        assertNotNull(description)
        assertEquals("Living Room TV", description.friendlyName)
        assertEquals("urn:schemas-upnp-org:service:AVTransport:3", description.avTransport.serviceType)
        assertEquals("http://living-room-tv.local:9197/avt/v3", description.avTransport.controlUrl)
    }

    @Test fun acceptsMissingUdnOnlyForSingleUnambiguousRenderer() {
        val xml = """
            <root><device>
              <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
              <friendlyName>Renderer</friendlyName>
              <serviceList><service>
                <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
                <controlURL>/avt</controlURL>
              </service></serviceList>
            </device></root>
        """.trimIndent()

        assertNotNull(
            RendererDescriptionParser.parse(
                SecureXml.parse(xml),
                "http://10.0.1.44/description.xml",
                "uuid:advertised::urn:schemas-upnp-org:device:MediaRenderer:1",
            ),
        )
    }

    @Test fun rejectsMismatchedUdnAndAmbiguousHighestService() {
        val mismatch = rendererXml(
            udn = "uuid:different",
            services = service(1, "/avt"),
        )
        assertNull(
            RendererDescriptionParser.parse(
                SecureXml.parse(mismatch),
                "http://10.0.1.44/description.xml",
                "uuid:advertised::urn:schemas-upnp-org:device:MediaRenderer:1",
            ),
        )

        val ambiguous = rendererXml(
            udn = "uuid:advertised",
            services = service(2, "/first") + service(2, "/second"),
        )
        assertNull(
            RendererDescriptionParser.parse(
                SecureXml.parse(ambiguous),
                "http://10.0.1.44/description.xml",
                "uuid:advertised::urn:schemas-upnp-org:device:MediaRenderer:1",
            ),
        )
    }

    @Test fun rejectsExcessivelyDeepEmbeddedDeviceTree() {
        var nested = """
            <device>
              <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
              <friendlyName>Too Deep</friendlyName>
              <serviceList>${service(1, "/avt")}</serviceList>
            </device>
        """.trimIndent()
        repeat(34) {
            nested = "<device><deviceType>urn:schemas-upnp-org:device:Basic:1</deviceType>" +
                "<deviceList>$nested</deviceList></device>"
        }
        val xml = "<root>$nested</root>"

        assertNull(
            RendererDescriptionParser.parse(
                SecureXml.parse(xml),
                "http://10.0.1.44/description.xml",
                null,
            ),
        )
    }

    private fun rendererXml(udn: String, services: String): String = """
        <root><device>
          <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
          <friendlyName>Renderer</friendlyName>
          <UDN>$udn</UDN>
          <serviceList>$services</serviceList>
        </device></root>
    """.trimIndent()

    private fun service(version: Int, controlUrl: String): String = """
        <service>
          <serviceType>urn:schemas-upnp-org:service:AVTransport:$version</serviceType>
          <controlURL>$controlUrl</controlURL>
        </service>
    """.trimIndent()
}
