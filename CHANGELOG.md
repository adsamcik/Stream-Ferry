# Changelog

All notable changes to **Stream Ferry** are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project aims to follow [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

Stream Ferry streams Jellyfin video to a TV **through the phone**: the phone is the sole media
gateway and the TV only ever receives an ephemeral, phone-hosted proxy URL — it never sees the
Jellyfin server URL, token, or any real stream URL. Releases are R8-minified APKs published on GitHub;
there is **no telemetry** and nothing leaves the device unless you share a report.

## [Unreleased]

### Added
- Added a private playback-history shelf for up to 20 renderer-confirmed Jellyfin, downloaded, and
  local items, with exact left-off progress, resume/watch-again actions, and per-item or full clearing.
- Existing single-item Smart Resume data migrates into the history automatically without retaining
  stream URLs, tokens, or live playback sessions.

## [0.5.0] - 2026-08-09

### Added
- Added offline Jellyfin gallery browsing backed by account-scoped downloads and a durable library
  cache, so downloaded shows and episodes remain discoverable without a server connection.
- Added a resilient playback queue with playlist browsing, queue controls, and seamless autoplay of
  the next episode over the existing TV connection.
- Added durable Jellyfin played/unplayed and favorite controls, including queued mutations that are
  reconciled when connectivity returns.
- Added precise playback navigation with a validated **Jump to time** dialog, cumulative skip controls,
  and exact resume from persisted renderer checkpoints.

### Changed
- Improved physical-TV routing by grouping confidently matched Cast and DLNA endpoints, remembering
  successful routes, and synchronizing the TV's current volume before showing controls.
- Refined keyboard, focus, touch-target, and back-navigation behavior across setup, search, gallery,
  downloads, dialogs, and playback controls.
- Made download progress notifications open the Downloads screen directly and added explicit
  confirmation before deleting failed downloads or forgetting saved servers.
- Removed unused playback-policy implementations so the shipped behavior has one authoritative path.

### Fixed
- Made play, pause, seek, and timestamp resume reliable across Cast, DLNA, direct play, HLS, progressive
  server transcodes, local files, downloaded media, and on-device transcoding.
- Preserved paused state during progressive-transcode seeks, bounded DLNA resume retries, and retired
  failed renderer/proxy sessions before automatic transcode fallback.
- Fixed stale or cross-item resume checkpoints and synchronized Jellyfin playback progress and terminal
  state more reliably.
- Stabilized gallery loading, season scrolling, media-source selection, and offline/online transitions.
- Made download queue persistence and metadata replacement crash-safe, and pause downloads cleanly when
  Android stops the foreground service.
- Surfaced renderer-control, secure-storage, diagnostic-export, and media-access failures instead of
  silently ignoring them.

## [0.4.2] - 2026-08-02

### Fixed
- Capture Android `apksigner` verification output from both streams so the GitHub Release always
  includes a complete signing-certificate report.

## [0.4.1] - 2026-08-02

### Fixed
- Generate the GitHub Release signing-certificate report with Android's `apksigner`, verifying the
  APK's modern signature schemes and publishing the actual signer certificate fingerprints.

## [0.4.0] - 2026-08-02

### Added
- Added a unified physical-TV picker that conservatively combines confidently matched Cast and DLNA
  endpoints, remembers successful routes, and lets you keep endpoints separate when appropriate.
- Added bounded, redacted playback recovery: one transient retry, compatible stream fallbacks, and a
  single alternate-protocol handoff when the selected physical TV supports it.
- Added device-aware Smart Resume v2, which reconciles the latest confirmed position and can reuse an
  exact previously selected TV after an app restart.
- Added optional Night volume settings for gradual or one-time, reduction-only volume changes during
  playback, including overnight schedules and manual-override protection.
- Added TV-output overrides on Now Playing, giving you direct control of the selected output route.

### Changed
- Reworked playback around one stable Now Playing flow for connection, buffering, recovery, stream
  changes, and failures, with actionable Retry, Change TV, and Stop actions.
- Limited phone-side transcoding to eligible local Cast playback and improved its selection policy;
  Jellyfin server transcoding remains the online compatibility path.
- Refined the compact picker, Settings, Smart Resume, and Now Playing interactions and updated the
  Android/Gradle dependency set.

### Fixed
- Improved DLNA discovery and control reliability through stricter SSDP admission, safe description
  XML handling, endpoint validation/pinning, and clearer diagnostics.
- Recover active playback more reliably when a Cast receiver or TV restarts.
## [0.3.2] - 2026-07-27

### Added
- Added an appearance-theme selector and expressive feedback for server selection, Quick Connect
  and authentication, downloads, diagnostics, settings disclosures, and gallery navigation.

### Changed
- Extended the expressive Material 3 motion system through navigation, playback, errors, resume and
  copy feedback, while keeping reduced-motion behaviour appropriate for playback.
- Simplified playback state handling and the Settings implementation, avoiding unnecessary refresh
  allocations and obsolete UI code.

## [0.3.1] - 2026-07-26

### Changed
- Reworked the Compose UI around three durable destinations (Library, Downloads, and Settings),
  adaptive bottom/rail navigation, bounded large-screen content, stable expressive colour/shape/type
  tokens, and a persistent mini-player for active TV sessions.
- Back from Now Playing now returns to browsing without stopping the TV; stopping playback remains an
  explicit transport action. Downloads Back state is validated so repeated or restored navigation cannot
  point to an unavailable contextual screen.
- Added explicit sensitive Quick Connect copying, accessible seek/set-progress behavior, 48 dp gallery
  index targets, download deletion confirmation, and reduced decorative playback motion.
- Removed the unavailable online phone-transcoding and CPU/software live-transcoding paths. Online
  Jellyfin media now always uses direct play, remux, or Jellyfin server transcoding; the retained phone
  path is the experimental, local-file-only Cast hardware-transcode exception.

## [0.3.0] - 2026-07-25

### Added
- Public-release automation now publishes a stable-signed APK, checksum, and signer report to GitHub
  Releases.

### Changed
- Renamed the app from **Video Bridge** to **Stream Ferry** and changed its Android namespace and
  application ID from `com.videobridge` to `com.adsamcik.streamferry`.

## [0.2.32] - 2026-07-08

### Added
- **"What's playing" now shows source → output format.** The playback quality card gains a **Source** row
  (codec · container · resolution, e.g. `H.264 · MKV · 1080p`) and, whenever the stream is being
  transcoded, an **Output** row describing exactly what's sent to the TV — on-device transcodes show the
  negotiated codec, capped resolution and `on device` (e.g. `H.264 · 720p · on device`); server
  transcodes show the requested codec and `HLS`/`progressive · server`. The stream-mode label is now
  explicit (`Direct play` / `On-device transcode` / `Server transcode (HLS)`), and the source resolution
  and bitrate rows are populated for on-device local files too (previously blank).
- **Manual codec picker for on-device (local) transcodes.** When casting a local file that the phone is
  transcoding, the **Codec** picker now offers every codec the TV can decode *and* this phone can encode
  (H.264 / HEVC / VP9 / AV1, plus **Auto**). Choosing one re-encodes the file to that codec and reloads it
  on the TV **in place** — reusing the live connection, no reconnect.
- **Automatic format fallback for on-device transcodes.** If the TV rejects the chosen on-device format
  before playback starts, the phone now automatically re-encodes to the next codec the TV advertised
  (reloading in place) before giving up — so a picky TV lands on a format it accepts instead of showing an
  error.

### Changed
- **Seamless autoplay-next.** Advancing to the next episode now reuses the live TV connection instead of
  disconnecting and reconnecting (a Cast reconnect alone took ~6 s). At the end of an episode the engine
  holds the connection, proxy and foreground service open and loads the next episode in place — no
  reconnect. Falls back to a normal (re)connect only if there's no live session to reuse.
- **Quieter, faster DLNA over a VPN.** When the phone can't bind a socket to the Wi-Fi network (common
  under a full-tunnel VPN, which fails with `EPERM`), the DLNA controller now learns this once and skips
  the doomed Wi-Fi bind on every subsequent SSDP discovery, device-description fetch, and SOAP control
  call — using the working default route directly. This removes a flood of repeated `EPERM` log lines
  (and the wasted per-call latency), re-probing automatically when the network changes. SSDP replies are
  also de-duplicated per scan in the log.

### Fixed
- **On-device transcodes no longer error out cleanly on a DLNA TV.** A DLNA renderer can't play the
  phone-hosted HLS/CMAF the on-device transcoder produces, so the app no longer attempts it there (it was
  a silent hang / UPnP 701 "Transition not available"). Incompatible local files on a DLNA TV now surface a
  clear message pointing at a directly-playable file or Chromecast, instead of buffering forever. On-device
  transcoding remains fully supported on Chromecast.
- **No more muxer errors when stopping an on-device transcode.** Stopping (or switching away from) an
  on-device transcode now cancels the in-flight hardware encode and lets its muxer finish *before* the
  segment cache is deleted, eliminating the `MediaMuxer` failure (error 7001) that was logged on every
  teardown mid-encode.

## [0.2.31] - 2026-07-07

### Fixed
- **On-device transcode now plays on Chromecast.** The phone-hosted HLS for on-device transcodes now
  serves a proper **master playlist declaring `CODECS`** (and resolution), derived from the transcoded
  init segment. Cast's receiver needs this to start an fMP4/CMAF stream — without it it fetched every
  segment but buffered forever without ever playing.
- A local on-device transcode is now correctly flagged as an HLS transcode internally (fixes seek
  handling and stops a bogus "unrecoverable" classification).

### Changed
- **Much more diagnostic telemetry for "the TV never played"** (all always-on, no tracing): the exact
  master playlist served to the TV, the fMP4 init's parsed codecs + resolution, and — when the startup
  watchdog fires — a snapshot of the renderer's state (Cast player state / idle reason / media content
  type, or the DLNA transport state) plus how many bytes were served.
- A stream that never starts now surfaces an **actionable** message (e.g. suggesting a different maximum
  resolution/format or a directly-playable file) instead of a generic "unrecoverable" error.

## [0.2.30] - 2026-07-06

### Added
- **Watch state (Jellyfin).** The gallery shows each item's native watch state — a watched check, an
  unwatched-episode count on series/seasons, and an in-progress progress bar. From an item's detail you
  can now **mark it watched or unwatched** (a series/season cascades to its episodes), written back via
  Jellyfin's `UserData` API.
- **Maximum resolution setting.** A new **Settings → Maximum resolution** picker (4K / 1080p / 720p /
  480p) replaces the old on/off "Allow 4K HDR" toggle. Taller sources are transcoded down to the cap;
  the 4K setting still passes HDR through to a capable TV.

### Fixed
- **On-device (local) transcoding now works for high-resolution sources.** The transcoder now caps the
  output to the negotiated resolution (e.g. downscaling a 4K source to 1080p) instead of handing the
  phone's hardware encoder the full source resolution, which failed silently.
- The playback **resolution readout** now uses the primary video stream and ignores embedded cover-art
  streams, so it no longer misreports (e.g. showing 720p for 1080p content).

### Changed
- **Much richer diagnostics logging** on the playback/transcode paths (all always-on events, no tracing
  needed): the local-playback decision, the chosen transcode codec/resolution and the phone's encoder
  capabilities, first-segment/empty-output outcomes, and stream load events — so a shared report can
  actually explain why local transcoding did or didn't work. The parsed source resolution is logged too.

## [0.2.29] - 2026-07-05

### Added
- **Preferred languages + per-show memory.** Choose a preferred audio and subtitle language in Settings;
  playback auto-selects the matching track when a show has it. The last language you pick for a show is
  remembered and overrides the global preference for that show, so the next episode plays the same way.
- **Continue Watching.** A resume row at the top of the Jellyfin library surfaces partially-watched
  items with a progress bar; tap to open and resume.

### Changed
- **Diagnostics event log now persists across app restarts.** A shared report includes the current
  build's earlier playback/transcode/TV-communication events (not just the current launch), so reports
  made after casting are actually useful. Still redacted, current-build-only, and cleared by
  "Delete all app data".

### Fixed
- **Audio track switching now works.** Selecting a non-default audio track forces a server transcode so
  the chosen track is actually delivered — previously, on direct play, the TV kept playing its own
  default track.

## [0.2.28] - 2026-07-05

### Added
- **Auto-skip intro/outro/recap.** Consumes Jellyfin's Media Segments (10.10+, Intro Skipper plugin) to
  automatically skip intro/recap/outro segments, with a manual "Skip" button too. Toggle in Settings.
- **Autoplay next episode.** When a Jellyfin episode ends, the next one (across season boundaries)
  starts automatically on the same TV.

### Fixed
- **Screen-off casting stalls.** Added an opt-in "allow unrestricted background playback" setting so
  casting keeps working with the screen off on phones (notably Samsung) that otherwise suspend the app.
- If a server transcode keeps rebuffering at the lowest quality, the app can now automatically switch to
  transcoding on the phone.

## [0.2.27] - 2026-07-05

### Added
- **Manual quality and codec controls.** Pick a specific streaming quality (bitrate), and manually
  choose the transcode video codec (H.264/HEVC/VP9/AV1).
- **On-device transcoding for online sources (experimental).** As a last resort — after direct play and
  a server transcode — the phone can transcode a Jellyfin source itself (hardware, or opt-in CPU),
  negotiating the best codec/quality the TV supports.
- The quality card now shows the video **resolution and image bitrate**.

### Changed
- **4K HDR passthrough** (HEVC direct-play for both Cast and DLNA) with a "Reset learned TV
  capabilities" action.
- Tighter phone↔TV sync during casting.
- Crash-report sharing now includes only reports from the **latest build**.
- UI responsiveness: no main-thread I/O, codec probing moved off the main thread, scoped recomposition.

### Fixed
- Seeking a transcoded stream from the phone (and DLNA transcode seek) now works instead of restarting
  from the beginning or snapping back.
- Automatic retry of a transient Cast connect failure and of a transient HLS segment fetch.

## [0.2.26] - 2026-07-04

### Added
- **Audio track + subtitle selection during playback** (enable/disable and language).
- Richer playback-failure telemetry in shared diagnostics reports.

### Fixed
- Cast transcode of HEVC / 4K / 10-bit sources (Cast error 104) and faster DLNA controls.
- Local HEVC transcode now plays correctly (fixed the cross-segment fMP4 timeline).
- DLNA transition-race (UPnP 701) retry and de-duplication of terminal failures.

## [0.2.25] - 2026-07-04

### Fixed
- A local file that wouldn't start on the TV (0-segment transcode).
- DLNA transport control while on a VPN.

## [0.2.24] - 2026-07-04

### Fixed
- Jellyfin seek/resume no longer plays from the start on the TV.

## [0.2.23] - 2026-07-04

### Added
- Foreground-service start-path and foreground-latency telemetry (to chase the FGS start crash).

## [0.2.22] - 2026-07-04

### Fixed
- Eliminated the "ForegroundServiceDidNotStartInTime" crash by using `startService()` when already
  foreground.

## [0.2.21] - 2026-07-04

### Fixed
- Another recurring foreground-service start crash.

### Changed
- Extensive diagnostics telemetry; modernized APIs and dropped dead dependencies.

## [0.2.20] - 2026-07-04

### Fixed
- More reliable Cast/DLNA playback recovery and a foreground-service start-crash fix.

## [0.2.19] - 2026-06-29

### Added
- **Multi-server support** with server identity pinning and a Servers screen.

## [0.2.18] - 2026-06-29

### Changed
- Renamed the app and package to **Video Bridge** (`com.videobridge`).

## [0.2.17] - 2026-06-28

### Added
- **Works without Jellyfin** — browse and cast on-device local videos (local-only mode).

## [0.2.16] - 2026-06-28

### Changed
- Enabled on-device local transcoding by default; encrypt the server URL at rest and drop the alpha
  security-crypto dependency.

### Fixed
- A ForegroundService start crash.

### Security
- Hardened the proxy/session against malicious actors: bounded slow-trickle upstreams and SSDP floods,
  tighter redaction, and session garbage collection.

## [0.2.15] - 2026-06-27

### Added
- **Resume where you left off** plus reliable reconnect for local sessions.

## [0.2.14] - 2026-06-27

### Fixed
- DLNA discovery XML parsing (secure parser); default to local direct-play; hide the download action for
  local files.

## [0.2.13] - 2026-06-27

### Added
- **Multi-source gallery** with local-file casting and on-device hardware transcoding.

## [0.2.12] - 2026-06-23

### Added
- Seek with a preview thumbnail; Compose previews.

### Fixed
- Reliable casting with the screen off, plus auto-reconnect and resume.

## [0.2.11] - 2026-06-22

### Added
- Poster/thumbnail art and a Material 3 Expressive redesign.

### Fixed
- DLNA/Cast playback fixes and a direct-play fallback.

## [0.2.10] - 2026-06-22

### Added
- Server error details surfaced in the UI; honest LAN-permission status; resilient, auto-recovering
  downloads.

## [0.2.9] - 2026-06-22

### Changed
- Redesigned Diagnostics: a browsable, telemetry-rich, one-tap issue report.

## [0.2.8] - 2026-06-22

### Fixed
- DLNA/Cast now work over a VPN (bind the TV-facing HTTP server to Wi-Fi and keep Cast routes alive).

## [0.2.7] - 2026-06-22

### Added
- Library search, A–Z fast-scroll, diagnostics export with opt-in TV tracing, background/transcoded
  downloads, and VPN-safe DLNA discovery.

## [0.2.6] - 2026-06-21

### Fixed
- Foreground-service timeout crash and Cast unavailability after a crash; added proxy CORS.

## [0.2.5] - 2026-06-21

### Added
- Debug-only immediate crash screen with a copyable stack trace; prompt to export crash logs on next
  launch (works before login).

## [0.2.4] - 2026-06-21

### Added
- Save crash logs to a file via the system file picker (SAF) — no PC needed.

### Changed
- Reuse a stable signing key across cloud builds.

## [0.2.3] - 2026-06-21

### Added
- On-device crash handler that writes redacted crash reports to a file.

## [0.2.2] - 2026-06-20

### Fixed
- Treat overlay-mesh VPNs and reserved local domains as LAN for the http-connection approval.

## [0.2.1] - 2026-06-20

### Fixed
- Startup crashes and LAN-`http` connectivity (found via emulator end-to-end testing).
- The release APK is debug-signed so it installs directly; more resilient CI/release.

## [0.2.0] - 2026-06-20

### Added
- First feature-complete build: connect/validate, gallery browsing, Cast + DLNA device discovery,
  adaptive-bitrate playback through the phone proxy, offline downloads, system playback controls, and a
  full Jetpack Compose UI. Publishes a release APK on `v*` tags.

## [0.1.0] - 2026-06-20

### Added
- Initial project: the pure-JVM proxy/security core (HTTP range, sessions, HLS, adaptive bitrate, URL
  policy, redaction), the Android app skeleton and Compose UI, CI + release pipelines, and
  documentation.

[Unreleased]: https://github.com/adamnova/video-bridge/compare/v0.5.0...HEAD
[0.5.0]: https://github.com/adamnova/video-bridge/compare/v0.4.2...v0.5.0
[0.4.2]: https://github.com/adamnova/video-bridge/compare/v0.4.1...v0.4.2
[0.4.1]: https://github.com/adamnova/video-bridge/compare/v0.4.0...v0.4.1
[0.4.0]: https://github.com/adamnova/video-bridge/compare/v0.3.2...v0.4.0
[0.3.2]: https://github.com/adamnova/video-bridge/compare/v0.3.1...v0.3.2
[0.3.1]: https://github.com/adamnova/video-bridge/compare/v0.3.0...v0.3.1
[0.3.0]: https://github.com/adamnova/video-bridge/compare/v0.2.32...v0.3.0
[0.2.32]: https://github.com/adamnova/video-bridge/releases/tag/v0.2.32
[0.2.31]: https://github.com/adamnova/video-bridge/releases/tag/v0.2.31
[0.2.30]: https://github.com/adamnova/video-bridge/releases/tag/v0.2.30
[0.2.29]: https://github.com/adamnova/video-bridge/releases/tag/v0.2.29
[0.2.28]: https://github.com/adamnova/video-bridge/releases/tag/v0.2.28
[0.2.27]: https://github.com/adamnova/video-bridge/releases/tag/v0.2.27
[0.2.26]: https://github.com/adamnova/video-bridge/releases/tag/v0.2.26
[0.2.25]: https://github.com/adamnova/video-bridge/releases/tag/v0.2.25
[0.2.24]: https://github.com/adamnova/video-bridge/releases/tag/v0.2.24
[0.2.23]: https://github.com/adamnova/video-bridge/releases/tag/v0.2.23
[0.2.22]: https://github.com/adamnova/video-bridge/releases/tag/v0.2.22
[0.2.21]: https://github.com/adamnova/video-bridge/releases/tag/v0.2.21
[0.2.20]: https://github.com/adamnova/video-bridge/releases/tag/v0.2.20
[0.2.19]: https://github.com/adamnova/video-bridge/releases/tag/v0.2.19
[0.2.18]: https://github.com/adamnova/video-bridge/releases/tag/v0.2.18
[0.2.17]: https://github.com/adamnova/video-bridge/releases/tag/v0.2.17
[0.2.16]: https://github.com/adamnova/video-bridge/releases/tag/v0.2.16
[0.2.15]: https://github.com/adamnova/video-bridge/releases/tag/v0.2.15
[0.2.14]: https://github.com/adamnova/video-bridge/releases/tag/v0.2.14
[0.2.13]: https://github.com/adamnova/video-bridge/releases/tag/v0.2.13
[0.2.12]: https://github.com/adamnova/video-bridge/releases/tag/v0.2.12
[0.2.11]: https://github.com/adamnova/video-bridge/releases/tag/v0.2.11
[0.2.10]: https://github.com/adamnova/video-bridge/releases/tag/v0.2.10
[0.2.9]: https://github.com/adamnova/video-bridge/releases/tag/v0.2.9
[0.2.8]: https://github.com/adamnova/video-bridge/releases/tag/v0.2.8
[0.2.7]: https://github.com/adamnova/video-bridge/releases/tag/v0.2.7
[0.2.6]: https://github.com/adamnova/video-bridge/releases/tag/v0.2.6
[0.2.5]: https://github.com/adamnova/video-bridge/releases/tag/v0.2.5
[0.2.4]: https://github.com/adamnova/video-bridge/releases/tag/v0.2.4
[0.2.3]: https://github.com/adamnova/video-bridge/releases/tag/v0.2.3
[0.2.2]: https://github.com/adamnova/video-bridge/releases/tag/v0.2.2
[0.2.1]: https://github.com/adamnova/video-bridge/releases/tag/v0.2.1
[0.2.0]: https://github.com/adamnova/video-bridge/releases/tag/v0.2.0
[0.1.0]: https://github.com/adamnova/video-bridge/releases/tag/0.1.0
