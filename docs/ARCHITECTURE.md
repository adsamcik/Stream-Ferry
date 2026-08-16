# Architecture

Stream Ferry streams remote and on-device media to a TV **exclusively** through an in-RAM proxy hosted
on the Android phone. The TV never talks to a media source. Jellyfin and local media are the current
source implementations; another provider is added as an isolated `:source:*` module.

```
Source server / local file ──(LAN / remote HTTPS / VPN / device storage)──▶ Android phone
                                                                  │  in-RAM bounded proxy
                                                                  ▼
                          http://PHONE_LAN_IP:EPHEMERAL_PORT/session/<256-bit-id>/stream
                                                                  │  (local Wi-Fi / LAN only)
                                                                  ▼
                                              Cast receiver  /  DLNA renderer (TV)
```

The phone is the intentional, sole media gateway. The TV receives only ephemeral, high-entropy,
phone-hosted URLs. No source URL, token, Authorization header, playlist URL, segment URL, subtitle
URL, or poster URL ever reaches the TV.

In-app artwork is represented by an opaque `ArtworkRef`. A Coil fetcher resolves that reference through
the active source's `ArtworkProvider`; only the source implementation can construct the private request,
attach credentials, and validate the origin. Coil, Compose, cache keys, and the TV never receive a
source URL or credential. Chapter seek-preview images use the same source-owned path.

## Module boundaries

Only `:app` depends on concrete source implementations. UI and playback depend on provider-neutral
contracts, and concrete sources do not depend on one another.

```text
                         :app
                  composition / Android shell
                    /        |         \
                  :ui    :playback    :source:jellyfin
                    \        |         :source:local
                     \       |              |
                        :source:api <--------+
                              |
                            :core
```

| Module | Responsibility |
| --- | --- |
| `:app` | Android application shell and the only concrete-source registration/composition root; Android stores and lifecycle wiring remain here. |
| `:core` | Pure-JVM policy and models: HTTP/HLS, recovery, ABR, stream decisions, security, resume, physical-TV aggregation, and other framework-free logic. |
| `:source:api` | Namespaced identities (`SourceInstanceId`, `MediaRef`), normalized catalogue/artwork/state models, capability contracts, `ProviderPlaybackSession`, and credential-isolating `StreamLease`. It contains no provider name or protocol-specific model. |
| `:source:jellyfin` | Jellyfin discovery, authentication, catalogue mapping, artwork, watch state, playback negotiation/reporting, download preparation, credentials, private upstream locators, and transcode cleanup. |
| `:source:local` | SAF/MediaStore catalogue and local playback implementation. It has no Jellyfin dependency. |
| `:playback` | `PlaybackEngine`, recovery, ABR, Cast/DLNA controllers, MediaSession, foreground playback service, and phone gateway. It depends on `:source:api`, never a concrete source. |
| `:ui` | Compose screens, immutable UI state, and UI-facing controller contracts. It depends on `:source:api`, never a concrete source. |

`checkSourceBoundaries` enforces the graph during every module `check`: it rejects concrete source
imports/dependencies in `:ui` and `:playback`, cross-source imports/dependencies, provider names in
`:source:api`, and provider-named declarations in `:core`.

## Source and playback contracts

Each configured source exposes a `SourceBackend` bundle containing only the capabilities it supports:
catalogue, artwork, playback, user state, downloads, and setup. Durable identities are always
`SourceInstanceId` + native id; cache, resume, playback, artwork, and download requests therefore cannot
collide across providers, servers, or accounts.

`PlaybackEngine` resolves a `PlaybackProvider` through `SourceRegistry` and receives a
`ProviderPlaybackSession`. The provider session owns preparation, quality/track replanning, lifecycle
reporting, media segments, and cleanup. Its `StreamLease` opens bytes without revealing a URL or header.
The gateway owns Range handling, bounded buffers, retry, and opaque HLS rewriting; the source owns
private locations, credentials, and origin/redirect validation.

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
- `core.resume.*` — resume-vs-restart-vs-finished decisions, rolling 90-day versioned playback
  history, local/server position reconciliation, and completion/stale-write protection.
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

1. User picks a namespaced `MediaRef`, then one conservatively aggregated physical TV. Selecting it
   enters the durable Now Playing route and chooses an eligible Cast or DLNA endpoint internally.
   `PlaybackEngine` asks the owning source to prepare a target-compatible provider session. Smart Resume
   and playback-history actions use a renderer-confirmed checkpoint plus source user state, and auto-reuse
   a previous TV only by exact stable identity.
2. `DefaultPlaybackSessionCoordinator` creates a proxy session (256-bit id), attaches the source-owned
   `StreamLease`, starts the foreground service, and binds the proxy to an ephemeral LAN port.
3. The Cast/DLNA controller loads **only** the phone proxy URL.
4. `ProviderPlaybackSession` receives start, progress, pause, seek, and stop events. A remote source maps
   them to its native reporting API; local media is not forced to implement a fake server lifecycle.
5. While playing, the `AdaptiveBitrateController` measures real proxy throughput over a rolling
   ≥ 30 s window (plus renderer rebuffer events) and, with hysteresis, may switch quality mid-stream
   by asking the provider session to replan at the current position and reloading the renderer with a
   new proxy session ([ADAPTIVE_BITRATE.md](ADAPTIVE_BITRATE.md)).
6. **Bounded recovery:** transient renderer/network failure admits one same-stream endpoint retry.
   Eligible startup incompatibility can advance through finite server compatibility/quality variants;
   after same-endpoint work, one alternate endpoint of the same confidently merged TV may be reserved.
   Every continuation resumes at renderer-confirmed progress. A user Stop invalidates the generation and
   cancels recovery; old controllers cannot act. Exhaustion retains Now Playing with Retry, Change TV,
   Stop, and redacted history. The CPU + Wi-Fi locks remain owned by `ProxyPlaybackService` while the
   session is active.
7. On stop/error/expiry/end: proxy session revoked, buffers cleared, provider session closed (including
   source-native transcode cleanup where applicable), and foreground service stopped.
