# Architecture

Video Bridge streams Jellyfin video to a TV **exclusively** through an in-RAM proxy hosted on the
Android phone. The TV never talks to Jellyfin.

```
Jellyfin server ──(LAN / remote HTTPS / VPN / split-tunnel)──▶ Android phone
                                                                  │  in-RAM bounded proxy
                                                                  ▼
                          http://PHONE_LAN_IP:EPHEMERAL_PORT/session/<256-bit-id>/stream
                                                                  │  (local Wi-Fi / LAN only)
                                                                  ▼
                                              Cast receiver  /  DLNA renderer (TV)
```

The phone is the intentional, sole media gateway. The TV receives only ephemeral, high-entropy,
phone-hosted URLs. No Jellyfin URL, token, Authorization header, playlist URL, segment URL, subtitle
URL, or poster URL ever reaches the TV.

In-app poster art (library grid + media detail) is fetched by the **phone itself** from Jellyfin via a
memory-cache-only Coil image loader: the access token is sent as an HTTP **header** (added only for the
configured Jellyfin host), never embedded in the image URL — so it can't land in a log, a cache key, or
anywhere it could leak — and posters are rendered on the phone only, never forwarded to the TV. The
on-device playback controls' **seek-preview scrubber** uses the same path: dragging shows a thumbnail of
the scrubbed position from Jellyfin **chapter ("section") images** (`Items/{id}/Images/Chapter/{index}`),
again header-authed, host-scoped, and phone-UI only — chapter images and their URLs are never given to
the TV.

## Layering

The UI layer never directly calls Jellyfin, Cast, DLNA, token storage, proxy sockets, URL
construction, or playback reporting. It depends only on the domain interfaces in
`com.videobridge.domain.Interfaces` and the `MainViewModel`.

| Package | Responsibility |
| --- | --- |
| `ui`, `ui.screens`, `ui.theme`, `ui.state` | Compose Material 3 screens, immutable UI state, single `MainViewModel`. |
| `domain` | Core interfaces + domain models, including the `MediaSource` abstraction (multi-source gallery: Jellyfin + on-device local) with per-source transcode capabilities. No Android/framework types in signatures where avoidable. |
| `data.jellyfin` | `JellyfinClient` (auth, libraries/items/search, playback-info, media-source, reporting) + `JellyfinAuthRepository` / `JellyfinMediaLibraryRepository` over the **documented Jellyfin HTTP API** (OkHttp). |
| `data.cast` | Direct Google Cast Sender SDK + AndroidX MediaRouter discovery (`CastOptionsProvider`, `CastTargetController`). |
| `data.dlna` | SSDP discovery, device-description parsing, AVTransport control + position/state polling (manual UPnP). |
| `data.proxy` | `LocalProxyServer` (ServerSocket + bounded RAM streaming, throughput meter) and request parsing. Also serves user-picked on-device files (offline downloads + SAF/MediaStore `content://`) from a seekable fd with full byte-range/seek — the TV still only ever receives the proxy URL. |
| `data.storage`, `data.security` | Direct Android Keystore (AES-256-GCM) encryption for both the token store and the server profile (base URL/server/user id); no third-party crypto library. |
| `data.cache` | Optional app-private cache of library **metadata** (`LibraryCache` + `CachingMediaLibraryRepository`) for offline/fast browsing. |
| `data.download` | Optional offline downloads (`DownloadStore` + `MediaDownloader`): saves the original file to app-private storage. |
| `data.local` | On-device video source: user-elective SAF folder/file grants (`LocalSourceStore`) + optional MediaStore, enumerated and exposed as a `MediaSource` (`LocalMediaSource`). |
| `data.resume` | Local "continue where you left off" store (`ResumeStore`) for on-device files, which have no server-side resume point. |
| `playback` | `PlaybackEngine` orchestrates select → proxy → renderer and runs adaptive bitrate; failure **recovery** is factored into pure, tested units — `PlaybackRecovery` (`decideRecovery`), `StartupWatchdog` (silent-failure/early-bail heuristics) and `RendererCapabilityStore` (learns per-renderer transcode needs). Also `MediaSessionController` (system playback controls) and `PlaybackServiceController`. |
| `playback.streamselection` | Chooses a TV-compatible Jellyfin stream server-side (the phone doesn't transcode Jellyfin). On-device transcoding for local files lives in `data.transcode`. |
| `playback.proxy` | `ProxyPlaybackService` foreground service (type `mediaPlayback`) hosting the MediaStyle controls notification. |
| `playback.buffer` | Memory buffer policy (pass-through + rolling prebuffer). |
| `playback.session` | `PlaybackSessionCoordinator` ties Jellyfin ↔ proxy ↔ Cast/DLNA sessions. |
| `playback.reporting` | `JellyfinPlaybackReporter` (start/progress/pause/seek/stop/cleanup). |
| `permissions` | Local-network / notification permission management. |
| `diagnostics`, `logging` | Redacting logger, network info, compatibility runner. |
| `core.*` | **Pure-JVM, dependency-free, unit-tested** building blocks (see below). |

## Verified pure-JVM core (`com.videobridge.core`)

All security- and correctness-critical logic lives in framework-free Kotlin so it can be unit tested
without an emulator. This is the only part runnable in the build sandbox and is covered by 215 passing
tests (`./gradlew testDebugUnitTest`, or the `kotlinc` procedure in [BUILD.md](BUILD.md)).

- `core.http.HttpRange` — RFC 9110 single byte-range parsing.
- `core.http.HttpResponsePlan` — 200 / 206 / 416 selection + `Content-Range` / `Content-Length` /
  `Accept-Ranges`.
- `core.session.SessionRegistry` — 256-bit session IDs, constant-time id match, sliding idle expiry
  bounded by an absolute ceiling, rejection of path traversal, open proxy, non-session and sub-path
  requests.
- `core.redaction.LogRedactor` — strips tokens, URLs, auth query params.
- `core.buffer.MemoryBufferPolicy` — bounded buffer sizes + memory-pressure / seek-window logic.
- `core.stream.StreamSelectionService` — DIRECT_PLAY / REMUX / AUDIO_TRANSCODE / HLS decision.
- `core.adaptive.AdaptiveBitrateController` / `BitrateLadder` — throughput-driven, hysteresis-guarded
  quality adaptation over a ≥ 30 s window (see [ADAPTIVE_BITRATE.md](ADAPTIVE_BITRATE.md)).
- `core.net.ServerUrlValidator` — normalises a user-entered server URL and enforces the
  https / LAN-http policy (remote http blocked; LAN http requires explicit approval).
- `core.download.DownloadPaths` — safe, traversal-proof filenames for the optional offline-download feature.
- `core.hls.HlsRewriter` — rewrites HLS playlist URIs to proxy URLs.
- `core.hls.HlsSegmentRegistry` — bounded, opaque (non-reversible) map of HLS segment/key/sub URLs so
  the rewritten playlist never exposes a Jellyfin URL or token to the TV.
- `core.hls.MediaPlaylistPlanner` — seekable VOD HLS playlist + seek→segment mapping for on-device
  (client-side) transcoding, giving the TV full seek over a live transcode.
- `core.transcode.*` — on-device transcode target negotiation (4K-negotiated HEVC→H.264 ladder,
  HEVC-never-MPEG-TS) + per-source routing (DIRECT_PLAY / SERVER_TRANSCODE / CLIENT_TRANSCODE).
- `core.local.LocalMediaRules` — local-file video filtering, display titles, and container MIME.
- `core.resume.ResumePolicy` — resume-vs-restart-vs-finished decision from a saved position + duration.
- `core.dlna.SecureXml` — XXE-hardened XML parsing.
- `core.dlna.DidlLite`, `core.dlna.SsdpParser` — DIDL-Lite metadata + bounded SSDP parsing.
- `core.resilience.ResilientStreamPolicy`, `RetryBudget` / `Backoff`, `UpstreamRetry` — spotty-link
  upstream reconnect-and-resume (bounded backoff, byte-offset resume) for the streaming proxy.
- `core.resilience.LibraryPagingPolicy` — bounded `StartIndex`/`Limit` walk for reliable
  whole-library loads over slow links.
- `core.net.LanInterfaceSelector` — WireGuard/VPN-safe choice of the TV-reachable LAN bind address
  (IPv4 only; IPv6-only LANs are unsupported — see [KNOWN_LIMITATIONS.md](KNOWN_LIMITATIONS.md)).

  See [RESILIENCE.md](RESILIENCE.md) for the resilience/WireGuard/paging design.

## Concurrency

Structured concurrency throughout: `viewModelScope` for UI, a dedicated scope for the proxy/service,
coroutine cancellation preserved end-to-end, no blocking work on the main thread, no global mutable
state. Manual DI via `AppContainer` (no DI framework — §14).

## Session lifecycle

1. User picks media + target → `PlaybackEngine` asks Jellyfin (PlaybackInfo) for a target-compatible
   source at the chosen quality, preserving `PlaySessionId`. Playback **resumes** from the item's
   Jellyfin resume point when one exists (the gallery shows a "Resume" hint).
2. `PlaybackSessionCoordinator` creates a proxy session (256-bit id), starts the foreground service,
   binds the proxy to an ephemeral LAN port.
3. The Cast/DLNA controller loads **only** the phone proxy URL.
4. `JellyfinPlaybackReporter` reports start, then **progress every ~5 s** (position + paused state) and
   stop — so Jellyfin's resume point, "Continue Watching", and played state stay in sync.
5. While playing, the `AdaptiveBitrateController` measures real proxy throughput over a rolling
   ≥ 30 s window (plus renderer rebuffer events) and, with hysteresis, may switch quality mid-stream
   by re-resolving PlaybackInfo at the current position and reloading the renderer with a new proxy
   session ([ADAPTIVE_BITRATE.md](ADAPTIVE_BITRATE.md)).
6. **Auto-reconnect:** if the renderer connection drops unexpectedly (network blip, Cast session lost),
   the app retries the same item on the same TV with bounded backoff (capped per attempt), **resuming
   at the last reported position**, surfaced as a "reconnecting" overlay; it gives up with a clear
   message if the drop is permanent (e.g. the TV was switched off). This covers **both** Jellyfin and
   on-device local sessions. A deliberate user stop is never auto-reconnected. The CPU + Wi-Fi locks are
   held for the whole session (`ProxyPlaybackService`) so playback survives the screen turning off for
   hours.
7. On stop/error/expiry/end: proxy session revoked, buffers cleared, Jellyfin transcode/HLS session
   stopped, foreground service stopped.
