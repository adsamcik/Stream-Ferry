package com.videobridge.core.dlna

import org.w3c.dom.Document
import org.w3c.dom.Element
import java.net.URI

/** One validated control endpoint attached to the selected MediaRenderer device node. */
data class RendererServiceEndpoint(
    val controlUrl: String,
    /** Canonical service URN retained for SOAPAction and body namespaces. */
    val serviceType: String,
)

/** Typed subset of an untrusted UPnP device description needed by the control point. */
data class RendererDescription(
    val udn: String?,
    val friendlyName: String,
    val modelName: String?,
    val modelNumber: String?,
    val firmware: String?,
    val avTransport: RendererServiceEndpoint,
    val renderingControl: RendererServiceEndpoint?,
)

/**
 * Selects services from one MediaRenderer device rather than searching every descendant in a
 * description. This keeps an embedded device's AVTransport from being accidentally attached to an
 * unrelated root device and supports compatible service versions above v1.
 */
object RendererDescriptionParser {

    private val DEVICE_TYPE = Regex(
        """^urn:schemas-upnp-org:device:MediaRenderer:(\d+)$""",
        RegexOption.IGNORE_CASE,
    )
    private val SERVICE_TYPE = Regex(
        """^urn:schemas-upnp-org:service:(AVTransport|RenderingControl):(\d+)$""",
        RegexOption.IGNORE_CASE,
    )

    fun parse(document: Document, descriptionLocation: String, ssdpUsn: String?): RendererDescription? {
        val root = document.documentElement ?: return null
        val base = validBase(directText(root, "URLBase")) ?: descriptionLocation
        val renderers = rendererDevices(root)
        val usnUdn = ssdpUsn?.substringBefore("::")?.trim()?.takeIf { it.startsWith("uuid:", ignoreCase = true) }
        val device = when {
            usnUdn != null -> renderers.firstOrNull { directText(it, "UDN")?.equals(usnUdn, ignoreCase = true) == true }
                ?: return null
            renderers.size == 1 -> renderers.single()
            else -> return null // do not borrow a service from an ambiguous embedded device tree
        }

        val avTransport = service(device, "AVTransport", base) ?: return null
        val renderingControl = service(device, "RenderingControl", base)
        val friendly = directText(device, "friendlyName").orEmpty().ifBlank { "DLNA Renderer" }
        return RendererDescription(
            udn = directText(device, "UDN"),
            friendlyName = friendly,
            modelName = directText(device, "modelName"),
            modelNumber = directText(device, "modelNumber"),
            firmware = directText(device, "modelDescription"),
            avTransport = avTransport,
            renderingControl = renderingControl,
        )
    }

    private fun rendererDevices(root: Element): List<Element> {
        val result = mutableListOf<Element>()
        fun visit(device: Element) {
            val version = DEVICE_TYPE.matchEntire(directText(device, "deviceType").orEmpty())
                ?.groupValues?.get(1)?.toIntOrNull()
            if (version != null && version >= 1) result += device
            directChildren(device, "deviceList").flatMap { directChildren(it, "device") }.forEach(::visit)
        }
        directChildren(root, "device").forEach(::visit)
        return result
    }

    private fun service(device: Element, requiredName: String, base: String): RendererServiceEndpoint? {
        val candidates = directChildren(device, "serviceList")
            .flatMap { directChildren(it, "service") }
            .mapNotNull { service ->
                val match = SERVICE_TYPE.matchEntire(directText(service, "serviceType").orEmpty()) ?: return@mapNotNull null
                val name = match.groupValues[1]
                val version = match.groupValues[2].toIntOrNull() ?: return@mapNotNull null
                if (!name.equals(requiredName, ignoreCase = true) || version < 1) return@mapNotNull null
                val control = directText(service, "controlURL") ?: return@mapNotNull null
                val resolved = resolve(base, control) ?: return@mapNotNull null
                RendererServiceEndpoint(
                    controlUrl = resolved,
                    serviceType = "urn:schemas-upnp-org:service:$requiredName:$version",
                ) to version
            }
        return candidates.maxByOrNull { it.second }?.first
    }

    private fun validBase(raw: String?): String? = raw?.let { candidate ->
        runCatching { URI(candidate) }.getOrNull()
            ?.takeIf { it.isAbsolute && SsdpParser.isAcceptableLocation(candidate) }
            ?.toString()
    }

    private fun resolve(base: String, ref: String): String? = runCatching {
        URI(base).resolve(ref).normalize().toString().takeIf(SsdpParser::isAcceptableLocation)
    }.getOrNull()

    private fun directText(parent: Element, name: String): String? =
        directChildren(parent, name).firstOrNull()?.textContent
            ?.replace(Regex("""\s+"""), " ")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.take(MAX_TEXT_LENGTH)

    private fun directChildren(parent: Element, name: String): List<Element> = buildList {
        val nodes = parent.childNodes
        for (index in 0 until nodes.length) {
            val child = nodes.item(index) as? Element ?: continue
            val local = child.localName ?: child.nodeName.substringAfter(':')
            if (local.equals(name, ignoreCase = true)) add(child)
        }
    }

    private const val MAX_TEXT_LENGTH = 256
}
