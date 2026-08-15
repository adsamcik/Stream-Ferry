# Architecture

Stream Ferry streams Jellyfin video to a TV **exclusively** through an in-RAM proxy hosted on the
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
`com.adsamcik.streamferry.domain.Interfaces` and the `MainViewModel`.

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
| `data.resume` | `ResumeStore` for per-file local positions plus versioned `SmartResumeStore` for safe cross-restart reconstruction data and previous physical-TV identity. |
| `data.volume` | Small SharedPreferences-backed night-volume settings store; no background service or automation database. |
| `physical` | Pure conservative Cast/DLNA aggregation, stable association/unlink policy, endpoint selection, and exact resume identity matching. |
| `playback` | `PlaybackEngine` remains the orchestrator for source → proxy → renderer, adaptive bitrate, generation ownership, and bounded recovery. Pure `PlaybackRecoverySession`, existing `PlaybackRecovery` / `StartupWatchdog`, and `RendererCapabilityStore` hold policy outside the UI. Also `MediaSessionController` and `PlaybackServiceController`. |
| `playback.streamselection` | Chooses a TV-compatible Jellyfin stream server-side. Online media is never transcoded on the phone; the separate `data.transcode` path is local-file-only. |
| `playback.proxy` | `ProxyPlaybackService` foreground service (type `mediaPlayback`) hosting the MediaStyle controls notification. |
| `playback.buffer` | Memory buffer policy (pass-through + rolling prebuffer). |
| `playback.session` | `PlaybackSessionCoordinator` ties Jellyfin ↔ proxy ↔ Cast/DLNA sessions. |
| `playback.reporting` | `JellyfinPlaybackReporter` (start/progress/pause/seek/stop/cleanup). |
| `permissions` | Local-network / notification permission management. |
| `diagnostics`, `logging` | Redacting logger, network info, compatibility runner. |
| `core.*` | **Pure-JVM, dependency-free, unit-tested** matching, resume, volume, security, stream, and recovery policy (see below). |

## Adaptive application shell

The Compose shell separates durable destinations from contextual media work:

- **Library** and **Settings** are the top-level destinations; Downloads is a contextual route reached
  from Settings. Compact-height
  windows and windows at least 600 dp wide use a navigation rail; other compact windows use a bottom
  navigation bar. Content is width-bounded instead of stretching phone layouts across a tablet.
- Details, TV selection, Now Playing, Diagnostics, servers, and About remain contextual routes. Back
  from Now Playing returns to Library while the TV session continues; Stop is an explicit playback
  control. A persistent mini-player keeps the active target, state, progress, and play/pause action
  available while browsing.
- `MainViewModel` persists only stable route, source, search, and Downloads-origin values in
  `SavedStateHandle`. Contextual routes are validated against their required selection/session state and
  collapse to Library after process recreation instead of restoring a blank or looping screen.
- Lazy grids/lists own their bounded scroll viewport. Simple forms and details own one vertical scroll;
  the shell does not wrap lazy layouts in an unbounded parent scroller.

The visual system uses stable Material 3 components with an expressive 8/12/20/28/36 dp shape scale,
stronger display/title weights, complete teal light/dark fallback schemes, and Android's complete dynamic
scheme when enabled. Production does not depend on the alpha Material 3 Expressive API line. Motion is
reserved for state changes; playback no longer runs a perpetual decorative waveform animation.

Accessibility-critical custom surfaces expose platform-equivalent actions: Quick Connect has an explicit
sensitive clipboard button, the seek surface exposes progress and set-progress semantics, download state
is consolidated, and the gallery alphabet rail provides 48 dp touch width plus named section actions.

## Verified pure-JVM core (`com.adsamcik.streamferry.core`)

Security- and correctness-critical policy is kept in framework-free Kotlin so it can be unit tested
without an emulator. The complete unit suite runs with `./gradlew testDebugUnitTest`; the core-only
`kotlinc` procedure in [BUILD.md](BUILD.md) is a smaller fallback when Android dependencies are
unavailable. Exact test totals belong in the current verification report rather than this document.

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
- `core.hls.MediaPlaylistPlanner` — seekable VOD HLS playlist + seek→segment mapping for the limited
  local on-device path, so a compatible Cast receiver can request a position on demand.
- `core.transcode.*` — phone-side HLS/fMP4 target admission (hardware H.264 and opportunistic HEVC only)
  plus capability-gated per-source routing (DIRECT_PLAY / SERVER_TRANSCODE / CLIENT_TRANSCODE /
  UNSUPPORTED). Jellyfin server transcoding remains the online fallback; no remote client-transcoder
  input provider is claimed.
- `core.local.LocalMediaRules` — local-file video filtering, display titles, and container MIME.
- `core.resume.*` — resume-vs-restart-vs-finished decisions, bounded versioned playback history,
  local/server position reconciliation, and completion/stale-write protection.
- `core.volume.NightVolumePolicy` — local-time gradual/hard reduction decisions, sparse command
  scheduling, manual override, and DST/midnight handling.
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

1. User picks media, then one conservatively aggregated physical TV. Selecting it enters the durable
   Now Playing route and chooses an eligible Cast or DLNA endpoint internally. `PlaybackEngine` resolves
   a target-compatible source, preserving `PlaySessionId`. Smart Resume and playback-history actions use
   the newer safe local renderer-confirmed checkpoint and Jellyfin position, and auto-reuse a previous TV
   only by exact stable identity.
2. `PlaybackSessionCoordinator` creates a proxy session (256-bit id), starts the foreground service,
   binds the proxy to an ephemeral LAN port.
3. The Cast/DLNA controller loads **only** the phone proxy URL.
4. `JellyfinPlaybackReporter` reports start, then **progress every ~5 s** (position + paused state),
   immediate updates after pause/resume/seek, and stop — so Jellyfin's resume point, "Continue Watching",
   and played state stay in sync.
5. While playing, the `AdaptiveBitrateController` measures real proxy throughput over a rolling
   ≥ 30 s window (plus renderer rebuffer events) and, with hysteresis, may switch quality mid-stream
   by re-resolving PlaybackInfo at the current position and reloading the renderer with a new proxy
   session ([ADAPTIVE_BITRATE.md](ADAPTIVE_BITRATE.md)).
6. **Bounded recovery:** transient renderer/network failure admits one same-stream endpoint retry.
   Eligible startup incompatibility can advance through finite server compatibility/quality variants;
   after same-endpoint work, one alternate endpoint of the same confidently merged TV may be reserved.
   Every continuation resumes at renderer-confirmed progress. A user Stop invalidates the generation and
   cancels recovery; old controllers cannot act. Exhaustion retains Now Playing with Retry, Change TV,
   Stop, and redacted history. The CPU + Wi-Fi locks remain owned by `ProxyPlaybackService` while the
   session is active.
7. On stop/error/expiry/end: proxy session revoked, buffers cleared, Jellyfin transcode/HLS session
   stopped, foreground service stopped.
