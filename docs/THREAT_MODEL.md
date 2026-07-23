# Threat Model

Scope: an Android phone acting as the sole gateway between a Jellyfin server and a TV (Cast receiver
or DLNA renderer) on a local network. Assets to protect: Jellyfin server URL, access token,
Authorization header, upstream stream/playlist/segment/subtitle/poster URLs, `PlaySessionId`, user
credentials, user-selected local file `content://` URIs / SAF grants, and host device
memory/availability.

In-app poster art is fetched by the phone from Jellyfin with the token sent as an HTTP **header** (added
only for the configured Jellyfin host, never embedded in the URL → never in a cache key or log), cached
in memory only, and rendered on the phone UI only — a poster URL is never given to the TV. The playback
controls' seek-preview scrubber fetches Jellyfin **chapter ("section") images** the same way (token in a
header, host-scoped, phone-UI only); a chapter image or its URL is never given to the TV.

Trust boundaries:
- **Phone ↔ Jellyfin**: authenticated, TLS for remote (HTTP allowed only for explicit user-approved
  LAN servers). Treated as semi-trusted (server content/metadata is untrusted input).
- **Phone ↔ TV (LAN)**: plaintext HTTP, unauthenticated network. TV and any other LAN device are
  **untrusted**.
- **Phone process internals**: trusted, but subject to process death / memory pressure.

| # | Threat | Vector | Mitigation |
| --- | --- | --- | --- |
| 1 | Malicious LAN device / DLNA renderer / Cast target | Probes phone proxy, guesses session URLs | Ephemeral port, 256-bit session id, constant-time id match, `SessionRegistry` rejects unknown/expired/non-session/sub-path/path-traversal requests; proxy bound only during active playback. |
| 2 | Malicious Jellyfin URL / metadata / subtitles | Server returns hostile strings, oversized payloads | Treat all server data as untrusted; bounded buffers; redaction; no eval of metadata; DIDL escaping. |
| 3 | Token leakage via logs | Tokens/URLs in logcat or exports | `LogRedactor` strips tokens, auth headers/query params, `PlaySessionId`, and reduces URLs to scheme + **masked host**; release logging redacted; exports opt-in + redacted. |
| 4 | Token leakage via proxy URL | Token embedded in TV-visible URL | Proxy URLs contain only `session/<id>/stream`; upstream URL+token stored server-side keyed by session, never sent to TV. |
| 5 | Token leakage via Cast customData / DLNA metadata | Receiver/renderer sees credentials | Cast `MediaInfo` and DLNA DIDL-Lite carry only the phone proxy URL + safe metadata; no Jellyfin URL/token. |
| 6 | Local-network permission abuse / fingerprinting | Over-broad scanning | Request `ACCESS_LOCAL_NETWORK` only when needed; discovery cancellable/time-bounded; multicast lock only during discovery; SSDP discovery bounds total + per-source device-description fetches (`SsdpDiscoveryLimiter`). |
| 7 | HTTP MITM on LAN | Plaintext phone→TV stream sniffed/altered | Accepted residual risk (many TVs cannot trust phone TLS); mitigated by high-entropy session URLs, session-limited binding, no secrets in stream. Documented in [NETWORK_SECURITY.md](NETWORK_SECURITY.md). |
| 7b | Cross-session / replay access | Attacker reuses an old URL | Sessions use a sliding idle expiry (default 4h) bounded by an absolute ceiling (default 24h) and are revoked on stop; `SessionRegistry` rejects expired ids. |
| 8 | Remote HTTPS proxy misconfig | Accidentally exposing Jellyfin to WAN | Proxy binds to LAN address only, never 0.0.0.0 public; remote HTTP upstream blocked. |
| 9 | Self-signed / custom TLS | Trust-all weakening | Per-server, explicit, user-approved CA only; never trust-all; debug trust anchors excluded from release. |
| 10 | Untrusted DLNA XML / XXE | Hostile device-description / SOAP XML | `SecureXml`: DTDs + external entities disabled, secure processing on, bounded size, off-main-thread, no external fetch. |
| 11 | Dependency compromise | Supply-chain attack | google()+mavenCentral() only, no JitPack/jcenter/mavenLocal/SNAPSHOT/dynamic versions, Gradle dependency verification, wrapper validation. See [DEPENDENCY_RISK.md](DEPENDENCY_RISK.md). |
| 12 | Backup / debug leakage | Tokens in cloud backup or debug build | `dataExtractionRules` excludes secrets; no plaintext token backup; debug-only network config not shipped. |
| 13 | Process death during transcode | Orphaned Jellyfin transcode | Best-effort cleanup on stop/error; on restart, report stop/cleanup for recoverable sessions; never invent progress. |
| 14 | Abandoned sessions | Server keeps transcoding after TV stops | Reporter sends stop/completed/error; transcode/HLS session deleted on stop/error/disconnect. |
| 15 | Open proxy / path traversal | `/session/<id>/../../etc/passwd` | `SessionRegistry` resolves only known session ids to a single upstream; no arbitrary file/URL access; non-session paths Forbidden. |
| 16 | Memory exhaustion / connection-flood DoS | High-bitrate 4K/HDR or slow TV; a hostile peer opening many connections or an upstream returning a huge playlist/SOAP body | Hard buffer caps; shrink/stop prebuffer before OOM; degrade to pass-through; bounded reads of upstream HLS playlists (`core.http.BoundedBody`) and DLNA SOAP responses; `core.net.ConnectionLimiter` caps concurrent connections globally and per client IP; see [MEMORY_BUFFER_POLICY.md](MEMORY_BUFFER_POLICY.md). |
| 17 | VPN route leakage | LAN traffic captured by VPN / "block connections without VPN" | Diagnostics detect VPN, verify phone↔Jellyfin and TV↔phone reachability; actionable failure messages. See [VPN_LAN_DIAGNOSTICS.md](VPN_LAN_DIAGNOSTICS.md). |
| 18 | Guest Wi-Fi isolation / VLAN | TV cannot reach phone | Diagnostics test target fetch of a tiny resource from phone; clear error. |
| 19 | Foreground-service abuse | Server kept open without playback | FGS started only for active playback (type `mediaPlayback`), stopped on stop/error/cancel/expiry; never auto-start on boot. |
| 20 | Local-file URI leakage / arbitrary file read | Picked `content://` exposed to TV, or proxy coerced to open an arbitrary file | On-device source uses user-elective SAF/MediaStore grants; the `content://` URI is **fixed on the session** and opened on the phone via `ContentResolver` — only the proxy URL reaches the TV, and the proxy never opens a request-supplied path (same `SessionRegistry` guard as threat 15). On-device transcoding (when added) re-encodes locally and likewise serves only the proxy URL. |

Residual/accepted risks: plaintext phone→TV HTTP on LAN (threat 7); reliance on OEM honoring Android
local-network and foreground-service rules; orphaned transcodes after abrupt process death are
best-effort only.
