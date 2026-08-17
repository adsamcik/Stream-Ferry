package com.adsamcik.streamferry.core.dlna

import org.xml.sax.InputSource
import java.io.ByteArrayInputStream
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Hardened XML parsing for untrusted DLNA device-description / SOAP XML (§11, §17 XXE).
 *
 * Defences: DTDs disabled, external general/parameter entities disabled, XInclude off, secure
 * processing on, no external schema/DTD resolution, bounded input size. Use this factory for ALL
 * DLNA XML. Parsing must run off the main thread (caller's responsibility).
 */
object SecureXml {

    const val MAX_XML_BYTES = 512 * 1024 // reject oversized XML

    class XmlTooLargeException(message: String) : Exception(message)

    fun hardenedFactory(): DocumentBuilderFactory {
        val f = DocumentBuilderFactory.newInstance()
        // Each hardening step is BEST-EFFORT. Some platforms' XML parsers — notably Android's — do not
        // support every feature and throw ParserConfigurationException from setFeature(); letting that
        // propagate aborts the whole parse, which breaks ALL DLNA device-description / SOAP XML and thus
        // DLNA discovery + control entirely. XXE stays mitigated regardless: parse() installs an
        // EntityResolver that returns empty content for any external reference (so external entities can
        // never be resolved), input is size-bounded (MAX_XML_BYTES), and FEATURE_SECURE_PROCESSING (when
        // supported) caps entity expansion. On the JVM all features are supported, so behaviour is
        // unchanged (and the XXE unit test still throws on a DOCTYPE).
        setFeatureQuietly(f, "http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeatureQuietly(f, "http://xml.org/sax/features/external-general-entities", false)
        setFeatureQuietly(f, "http://xml.org/sax/features/external-parameter-entities", false)
        setFeatureQuietly(f, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        setFeatureQuietly(f, javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true)
        setAttributeQuietly(f, "http://javax.xml.XMLConstants/property/accessExternalDTD", "")
        setAttributeQuietly(f, "http://javax.xml.XMLConstants/property/accessExternalSchema", "")
        runCatching { f.isXIncludeAware = false }
        runCatching { f.isExpandEntityReferences = false }
        f.isNamespaceAware = true
        f.isValidating = false
        return f
    }

    private fun setFeatureQuietly(factory: DocumentBuilderFactory, name: String, value: Boolean) {
        runCatching { factory.setFeature(name, value) }
    }

    private fun setAttributeQuietly(factory: DocumentBuilderFactory, name: String, value: Any) {
        runCatching { factory.setAttribute(name, value) }
    }

    /**
     * Parse untrusted XML safely into a Document. Throws [XmlTooLargeException] if oversized.
     * Never resolves external references (entity resolver returns empty content).
     */
    fun parse(xml: String): org.w3c.dom.Document {
        // Do not rely on a parser-specific "disallow-doctype" feature: Android parser support varies.
        if (xml.contains("<!DOCTYPE", ignoreCase = true)) {
            throw SecurityException("DOCTYPE is not allowed in renderer XML")
        }
        if (xml.toByteArray(Charsets.UTF_8).size > MAX_XML_BYTES) {
            throw XmlTooLargeException("XML exceeds ${MAX_XML_BYTES} bytes")
        }
        return newBuilder().parse(InputSource(StringReader(xml)))
    }

    /**
     * Parse raw XML bytes so the XML declaration or BOM controls character decoding. Device
     * descriptions are not guaranteed to be UTF-8, and the raw byte limit remains the network bound.
     */
    fun parse(xml: ByteArray): org.w3c.dom.Document {
        if (xml.size > MAX_XML_BYTES) {
            throw XmlTooLargeException("XML exceeds $MAX_XML_BYTES bytes")
        }
        if (containsDoctype(xml)) {
            throw SecurityException("DOCTYPE is not allowed in renderer XML")
        }
        return newBuilder().parse(ByteArrayInputStream(xml))
    }

    private fun newBuilder() = hardenedFactory().newDocumentBuilder().apply {
        setEntityResolver { _, _ -> InputSource(StringReader("")) }
    }

    /**
     * XML markup keywords are ASCII in UPnP encodings. Ignoring NUL bytes catches both UTF-16 byte
     * orders as well as ASCII-compatible encodings before trusting the declared document encoding.
     */
    private fun containsDoctype(xml: ByteArray): Boolean {
        var matched = 0
        for (byte in xml) {
            val value = byte.toInt() and 0xff
            if (value == 0) continue
            val actual = if (value in 'a'.code..'z'.code) value - ('a'.code - 'A'.code) else value
            val expected = DOCTYPE_MARKER[matched].code
            matched = when {
                actual == expected -> matched + 1
                actual == DOCTYPE_MARKER[0].code -> 1
                else -> 0
            }
            if (matched == DOCTYPE_MARKER.length) return true
        }
        return false
    }

    private const val DOCTYPE_MARKER = "<!DOCTYPE"
}
