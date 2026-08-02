# VPN / LAN Diagnostics

Intended route:

- **Phone → Jellyfin** through VPN or a direct route (LAN / remote HTTPS / split-tunnel).
- **TV → Phone** through local Wi-Fi / LAN only.
- The TV needs **neither** VPN nor Jellyfin access.

## Diagnostics surfaced (Diagnostics screen)

- VPN active (yes/no).
- Phone reaches Jellyfin (upstream reachability test).
- Phone LAN IP (redacted in privacy mode).
- Proxy bind address (redacted).
- Cast/DLNA target can fetch a **tiny test resource** from the phone.
- Cast/DLNA target can fetch **media** from the phone.
- VPN does not block LAN.
- Split tunnel permits Jellyfin upstream **and** LAN downstream.
- Guest-isolation status.

`diagnostics.NetworkInfoProvider` provides VPN/LAN state; the compatibility runner performs the
target-fetch tests.

## Proxy bind interface selection (WireGuard-safe)

`NetworkInfoProvider.lanIpv4()` does **not** simply pick the first site-local address. When a VPN
such as WireGuard is up to reach a remote Jellyfin, the tunnel interface (`wg0`/`tun0`) often also
carries a site-local (10.x/172.16-31.x) address; binding the TV-facing proxy there makes the phone
unreachable from the TV on the real Wi-Fi LAN. Interface enumeration is therefore delegated to the
pure-JVM, unit-tested `core.net.LanInterfaceSelector`, which:

- requires an up, non-loopback, **site-local** IPv4;
- **excludes** tunnel (`tun`/`tap`/`wg`/`ppp`/`ipsec`/`utun`/`wireguard`/…), cellular/WAN
  (`rmnet`/…), and point-to-point interfaces — these reach the server, not the TV;
- **prefers** Wi-Fi/Ethernet-named interfaces and the conventional `192.168/16` home range, with a
  deterministic tie-break so the address handed to the TV is stable;
- selects an **IPv4** address only — an IPv6-only LAN segment is unsupported (see
  [KNOWN_LIMITATIONS.md](KNOWN_LIMITATIONS.md) → Networking constraints).

Jellyfin traffic still egresses over the tunnel via normal OS routing. See
[RESILIENCE.md](RESILIENCE.md) §2.

## SSDP/DLNA discovery interface (VPN-safe)

The same VPN problem hits **discovery**: a raw `MulticastSocket` sends its SSDP `M-SEARCH` out the
system **default route**, which is the VPN/mesh tunnel when one is up — so a DLNA renderer (e.g. an LG
webOS TV) on the real Wi-Fi LAN never receives the probe and is silently undiscoverable, even though
the proxy binds correctly to Wi-Fi. `DlnaTargetController.ssdpSearch` therefore pins the outgoing
multicast to the LAN interface (`bindSsdpToLan`, derived from `lanIpv4()`), sends a few probe bursts
(UDP is lossy), and traces each responder when TV tracing is on. OEM stacks (Samsung Smart View) are
interface-aware and work over the same VPN, which is why their content-share can find a TV the app's
raw SSDP previously could not.

## DLNA control HTTP must bind to Wi-Fi too (VPN-safe), not just the multicast

Pinning the SSDP multicast is necessary but **not sufficient**: after the TV's SSDP reply is received,
discovery fetches the TV's **device-description URL over HTTP**, and playback issues **SOAP** control
requests — both via OkHttp, which uses the system **default network** (the VPN). So with a VPN up, the
multicast reply arrives but every follow-up HTTP/SOAP call to the TV's LAN IP is captured by the tunnel
and fails — discovery finishes with `0 renderer(s)` despite a visible responder (confirmed live via the
TV-tracing diagnostics export). `DlnaTargetController` therefore routes all TV-facing HTTP through a
client bound to the Wi-Fi `Network` (`NetworkInfoProvider.wifiNetwork()` → `OkHttpClient` with
`socketFactory = network.socketFactory` + an isolated `ConnectionPool`). The proxy's own legs are
unaffected (the TV reaches the phone's Wi-Fi IP inbound; the proxy reaches remote Jellyfin outbound over
the VPN). Samsung's native share binds everything to Wi-Fi, which is why it works over the same VPN.

## Cast route lifecycle (routes must stay discovered through connect)

`androidx.mediarouter` purges discovered routes the moment **no** `MediaRouter.Callback` is registered.
The Cast discovery previously removed its callback right after the scan, so the selected route vanished
before `connect()` could `selectRoute` it — surfacing as "That Cast device is no longer available"
(reproduced even with the VPN **off**, i.e. not a network issue). The app-level physical-TV picker asks
`CastTargetController` to retain one active callback while the picker is visible and through bounded
`connect()` recovery, then removes it when the picker closes, permission is lost, or connection
completes. This retains the chosen route without an active scan for the full playback session.

## Exportable diagnostics + opt-in TV tracing

All problems — not only crashes — are captured by the redacting `DiagnosticsLogger` as **structured
entries** (`LogEntry`: timestamp, `LogLevel`, category, redacted message). The **Diagnostics screen**
(Settings → Diagnostics) is built for browsing and issue reporting:

- A prominent **"Report an issue"** card → **Share report** / **Save report…**: one comprehensive,
  fully-redacted plain-text report (app/device/Android header, a network section, a services section,
  a now-playing section while a session is active, the full event log, and the crash appendix).
  **Share report** writes that report to the app-private cache and shares it as a temporary
  `FileProvider` attachment rather than putting its text in an `Intent` extra; this preserves complete
  multi-megabyte reports without exceeding Android's activity-launch Binder limit.
  The report's **event log is read from app-private persistence** (`DiagnosticsEventLog` →
  `EventLogStore`, `filesDir/events`), **not** the in-memory ring, so a report generated in a fresh
  launch (e.g. after casting, once the process has been killed in the background) still contains the
  earlier playback/transcode/TV-communication events. The log is saved per session on a slow cadence
  (change-detected), on playback stop, when the app is stopped, and just before a report is built. Like
  crashes, only the **current build's** events are included, files are excluded from backup
  (`allowBackup=false`), bounded to the newest few sessions, and removed by "Delete all app data".
- **Snapshot cards**: App & device, Network (VPN active, Wi-Fi connected/available, redacted LAN IP +
  proxy address, Cast availability), Permissions (local-network, notifications), and Now-playing
  (target/protocol/mode/bitrate/throughput/position/buffering) while casting. The local-network row is
  honest about platform enforcement: it shows **"not enforced (Android X)"** (rather than a misleading
  "denied") on releases where the OS does not gate LAN access behind `ACCESS_LOCAL_NETWORK`, with a
  short caption explaining no prompt is shown and streaming still works (see
  [MANIFEST_PERMISSIONS.md](MANIFEST_PERMISSIONS.md)).
- A **browsable event log**: `All / Issues / Connections / Trace` filter chips + a search box, with
  colour-coded, timestamped rows that copy to the clipboard on tap. Connection telemetry events
  (`discovery`/`connect`/`session`/`playback`) are emitted always-on (not gated behind tracing).
  Upstream failures are logged with a parsed, redacted reason: when Jellyfin returns a 4xx/5xx, the
  client extracts a human-readable message from the error body (`ServerErrorReason`) so the log/UI can
  say *why* (e.g. "HTTP 500 (Library scan is already running)") instead of a bare status code. The
  reason is always run through `LogRedactor` and length-capped, so a server that echoes a URL/token in
  its error body cannot leak one.

A persisted, **off-by-default** "Detailed TV communication tracing" toggle (`DiagnosticsPreferences`)
flips `DiagnosticsLogger.traceEnabled`. When on, the Cast and DLNA controllers record their
request/response traffic via `logger.trace(...)` — SSDP responders, DLNA SOAP request/response bodies,
Cast connect/load/status/error — to help trace why a TV won't play. Every entry (events, traces, the
report header, and the proxy/LAN addresses) is passed through `LogRedactor` (URLs reduced to host-only,
session ids/tokens masked), and the TV never carries Jellyfin secrets anyway, so nothing can leak the
security invariant. This is **local diagnostics**: nothing is transmitted; it only leaves the device
when the user explicitly shares/saves a report.

## Failure messages (actionable, no stack traces)

- "VPN may be blocking LAN traffic — the TV cannot reach this phone. Try enabling split-tunnel or
  allowing local network access in your VPN app."
- "Your VPN may be routing private IP ranges incorrectly."
- "Android's 'Block connections without VPN' can break TV-to-phone access. Disable it for this app or
  use a split-tunnel."
- "This network appears to isolate clients (guest Wi-Fi / different VLAN); the TV and phone can't see
  each other."
- "Local-network permission is required for the TV to reach this phone." (with re-request + settings
  deep-link)

## Local-network test matrix (must cover)

phone LAN IP; VPN active; split tunnel; proxy binding; Cast/DLNA target fetching a tiny test resource
from the phone; target fetching media from the phone; permission granted / denied / revoked; app
upgrade to targetSdk 37; multicast blocked; guest Wi-Fi isolation; different VLANs; VPN capturing all
traffic; Android "block connections without VPN"; and remote HTTPS Jellyfin.

When the local-network permission is denied/revoked, Jellyfin **browsing stays usable**; only target
discovery and proxy-to-TV are blocked, with a clear explanation and a path to re-grant.
