package com.videobridge.data.dlna

import com.videobridge.core.dlna.SsdpParser
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.net.URI

/**
 * Helpers to locate the AVTransport service control URL inside a parsed (already XXE-hardened)
 * UPnP device-description document. Pure DOM traversal; safe against missing nodes.
 */
object AVTransport {

    private const val AV_TRANSPORT_TYPE = "urn:schemas-upnp-org:service:AVTransport:1"

    /** Parsed subset of a UPnP `AVTransport:GetTransportInfo` response. */
    data class TransportInfo(val state: String?, val status: String?)

    // GetTransportInfo returns CurrentTransportState (STOPPED/PLAYING/…) and CurrentTransportStatus
    // (OK/ERROR_OCCURRED). The response is small and namespace-prefixed variably, so match by local name.
    private val TRANSPORT_STATE = Regex("<(?:[\\w-]+:)?CurrentTransportState>([^<]*)</", RegexOption.IGNORE_CASE)
    private val TRANSPORT_STATUS = Regex("<(?:[\\w-]+:)?CurrentTransportStatus>([^<]*)</", RegexOption.IGNORE_CASE)

    /**
     * Parse the transport state + status out of a raw `GetTransportInfo` SOAP response. A renderer that
     * fails to decode the media reports `CurrentTransportStatus=ERROR_OCCURRED` (the UPnP-standard way to
     * say "can't play that file"), which the caller uses to trigger a transcode fallback. Pure + testable.
     */
    fun parseTransportInfo(soapResponse: String): TransportInfo = TransportInfo(
        state = TRANSPORT_STATE.find(soapResponse)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() },
        status = TRANSPORT_STATUS.find(soapResponse)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() },
    )

    /**
     * @param doc parsed device description.
     * @param descriptionLocation the URL the description was fetched from, used to resolve a
     *   relative controlURL into an absolute URL.
     */
    fun findControlUrl(doc: Document, descriptionLocation: String): String? =
        findControlUrl(doc, descriptionLocation, AV_TRANSPORT_TYPE)

    /** Locate the control URL for an arbitrary UPnP [serviceType] (e.g. RenderingControl). */
    fun findControlUrl(doc: Document, descriptionLocation: String, serviceType: String): String? {
        val services = doc.getElementsByTagName("service")
        for (i in 0 until services.length) {
            val service = services.item(i) as? Element ?: continue
            val type = child(service, "serviceType")
            if (type != null && type.equals(serviceType, ignoreCase = true)) {
                val control = child(service, "controlURL") ?: return null
                return resolve(descriptionLocation, control)
            }
        }
        return null
    }

    private fun child(parent: Element, tag: String): String? {
        val nodes = parent.getElementsByTagName(tag)
        return nodes.item(0)?.textContent?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun resolve(base: String, ref: String): String? = runCatching {
        val resolved = URI(base).resolve(ref).toString()
        // The description is fetched from an SSDP-validated private host, but it could still advertise an
        // absolute controlURL pointing at a PUBLIC host (URI.resolve returns an absolute ref verbatim) —
        // SOAP control carries the phone proxy URL, so it must never leave the LAN. Only accept a control
        // URL that is itself a private/LAN http(s) endpoint.
        resolved.takeIf { SsdpParser.isAcceptableLocation(it) }
    }.getOrNull()
}
