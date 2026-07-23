# DLNA / UPnP Integration

DLNA is the **second** backend, required especially for Samsung/LG TVs. Cast and DLNA are entirely
separate; the Google Cast API does **not** support DLNA.

## Roles

- Android app = **UPnP control point / Digital Media Controller (DMC)**.
- TV = **Digital Media Renderer (DMR)**.
- Phone proxy = media source.

Flow: the app sends the **phone proxy URL** via `SetAVTransportURI`; the TV fetches media from the
phone; the app controls playback via UPnP AVTransport. No Jellyfin URL or token is ever sent.

## Discovery & control (manual, no DLNA library)

To avoid abandoned/unmaintained DLNA libraries (§11 dependency rule), discovery and control are
implemented with platform APIs:

- **SSDP discovery** over multicast; respects the local-network permission.
- `CHANGE_WIFI_MULTICAST_STATE` declared; a `WifiManager.MulticastLock` is acquired **only during
  active discovery** and released reliably (try/finally).
- Discovery is cancellable with a timeout; handles multicast blocked, denied/revoked permission,
  disappearing TVs, and multiple renderers.
- Fetch device-description XML; verify `MediaRenderer`; inspect `ConnectionManager`, `AVTransport`,
  `RenderingControl`; call `GetProtocolInfo` but treat it **only as a hint**.

Components: `data.dlna.DlnaTargetController` (discovery + control), `data.dlna.AVTransport` (locate
the AVTransport control URL from the device description), `core.dlna.SsdpParser` (bounded SSDP
parsing), `core.dlna.SecureXml` (XXE-hardened parsing), `core.dlna.DidlLite` (metadata).

## VPN-resilient discovery (the recurring real-world failure)

When a VPN/mesh is up to reach a **remote** Jellyfin, the system **default network is the tunnel**,
while the TV is on the plain Wi-Fi LAN. SSDP can receive the TV's reply yet the follow-up
**device-description HTTP fetch** (and SOAP control) egress the tunnel and fail — so the renderer is
received but silently dropped ("0 renderer(s)"). Everything TV-facing is therefore pinned to the Wi-Fi
`Network`, not just the multicast interface (`setNetworkInterface`/IP_MULTICAST_IF alone is overridden
by a VPN):

- **SSDP multicast**: the socket is bound to the Wi-Fi `Network` (`Network.bindSocket`) *and* its
  `networkInterface` is set to the LAN NIC.
- **Device-description fetch** tries three LAN-reaching mechanisms in order of directness, first 2xx
  non-blank body wins: (1) `Network.openConnection` (HttpURLConnection pinned to the Wi-Fi `Network`),
  (2) OkHttp on a Wi-Fi `socketFactory` client, (3) OkHttp on the default route (for split-tunnel VPNs
  that route RFC1918 directly). SOAP control uses the same Wi-Fi-bound OkHttp client.
- **Diagnostics**: with opt-in TV tracing on, discovery logs the network context (`lanIp`, `vpnActive`,
  `wifiNetwork` available?), each SSDP reply, and — crucially — *why* a received renderer was dropped
  (description fetch failed on which mechanism / XML parse failed / no AVTransport service), so a
  failure is attributable from an exported report. See
  [VPN_LAN_DIAGNOSTICS.md](VPN_LAN_DIAGNOSTICS.md).

## Supported UPnP actions (where the renderer supports them)

`GetProtocolInfo`, `SetAVTransportURI`, `Play`, `Pause`, `Stop`, `Seek`, `GetTransportInfo`,
`GetPositionInfo`, `SetVolume`/`GetVolume`.

## Incompatible media: automatic transcode fallback

DLNA renderers vary wildly in codec/container support and there is no reliable way to know up front
(`GetProtocolInfo` is only a hint), so — exactly like Cast — the app **optimistically** hands the
renderer the original (the `DLNA_BASELINE` profile lets Jellyfin direct-play/remux common formats). If
the renderer can't decode it, the 4 s `GetTransportInfo` poll observes `CurrentTransportStatus`; a value
of `ERROR_OCCURRED` (the UPnP way to say "can't play that file") is raised as `PlaybackTargetEvent.Error`.
The engine then falls back **once** to a server transcode (progressive MPEG-TS H.264/AAC — the DLNA
transcoding profile) and reloads from the current position. This is the same `decideRecovery` path used
for Cast (see [STREAM_SELECTION.md](STREAM_SELECTION.md)); it is on by default and controlled by
Settings → "Prefer original quality". The transport state/status parsing lives in the pure, unit-tested
`data.dlna.AVTransport.parseTransportInfo`.

## Metadata

DIDL-Lite is provided when needed with the correct MIME type / `protocolInfo`, using **only** the
phone proxy URL. `friendlyName` / `model` / `manufacturer` are escaped before display.

## XML / SOAP security (§11, §17)

All DLNA XML is treated as untrusted: DTDs and external entities disabled, no external XML references
fetched, oversized XML rejected (`SecureXml.MAX_XML_BYTES` = 512 KiB), timeouts applied, malformed
XML handled, friendly/model/manufacturer strings escaped in the UI, full XML not logged by default,
and parsing performed off the main thread. `core.dlna.SecureXml` enforces the parser hardening and is
unit-tested (including a DOCTYPE-rejection test).

## Samsung/LG notes

- Do **not** assume Samsung/LG support Google Cast, that DLNA behavior is consistent, or that MKV /
  HEVC / DTS / TrueHD / subtitles / seeking / HTTPS-from-phone work.
- Prefer the local **HTTP** phone proxy for DLNA. Offer a compatibility mode that asks Jellyfin for
  remux/transcode/HLS.
- No Samsung Tizen, LG webOS, Fire TV, Android TV native apps, or vendor SDKs in the MVP.

See [COMPATIBILITY_MATRIX.md](COMPATIBILITY_MATRIX.md) for the required Samsung/LG manual test set.
