# Known Limitations

This is an MVP scaffold. The following are explicitly documented per the spec's requirement that any
unmet item be stated with reasons.

## Build / environment

- **Android assembly not run in the sandbox.** `dl.google.com` / `maven.google.com` are blocked here,
  so AGP/AndroidX/Compose/Cast cannot be resolved and `assembleDebug`/`lintDebug`/instrumented tests
  cannot run. The pure-JVM core is fully compiled and **128 unit tests pass**. See
  [BUILD.md](BUILD.md) and [API37_MIGRATION.md](API37_MIGRATION.md).
- **`gradle/verification-metadata.xml` not generated here** (requires resolving Google Maven). It must
  be produced with `./gradlew --write-verification-metadata sha256 help` at the first online build.
- **Compose BOM 2026.06.00 and Cast 22.3.0 are web-search-only versions**; re-verify and pin at the
  online build (flagged in the version catalog and [DEPENDENCY_RISK.md](DEPENDENCY_RISK.md)).

## Hardware-dependent acceptance criteria

- **Cast 30-second playback** and **DLNA 30-second playback** acceptance require physical
  Chromecast/Google TV and Samsung/LG hardware, which are unavailable in the sandbox. The code paths,
  controllers, and the [compatibility matrix](COMPATIBILITY_MATRIX.md) are in place; the live gate
  must be executed on real devices.
- No Jellyfin test server was available in-sandbox, so live auth/playback-info/media-source/reporting
  flows are implemented against the documented HTTP API but not executed end-to-end here.

## Functional scope (intentional MVP boundaries)

- **HLS memory-only proxying** is implemented at the core level (`HlsRewriter`) but the full
  live-transcode HLS path (playlist refresh, segment streaming, CORS for Web Receiver) must be
  validated against a transcoding Jellyfin server; the verified primary path is file/range proxying.
- **Rolling in-memory prebuffer** (Phase 9) policy + decisions are implemented and unit-tested; wiring
  it into the live streaming loop beyond pass-through is a follow-up.
- **Jellyfin SDK vs HTTP API:** the playback path uses the documented Jellyfin **HTTP API** (OkHttp)
  to avoid inventing/guessing SDK signatures; the SDK is retained in the catalog for models/future and
  carries LGPL-3.0 obligations ([LICENSES.md](LICENSES.md)).
- **Compose screens are an MVP scaffold:** all 11 screens exist with correct separation (Cast-first /
  DLNA-second, redacted diagnostics, delete-all-data), but rich interactions (live target lists, full
  audio/subtitle pickers, seek/volume controls bound to a live session) are stubbed pending the live
  playback path.
- Audio/video track switching, embedded/ASS/SSA/PGS subtitles, and codec probing are treated as
  unverified / test-required, not guaranteed.
- Out of scope by design (§1): direct Jellyfin-to-TV, screen mirroring, phone transcoding, full-file
  predownload, disk cache, offline downloads, music/photos/live-TV/SyncPlay, native TV apps, Media3
  Cast.
- **DRM / protected content is out of scope and not relayable.** An encrypted/DRM-protected stream
  cannot be proxied (or screen-captured) without the rights system's keys, so it is a deliberate
  non-goal rather than a regression. Jellyfin personal media is generally DRM-free.

## Networking constraints

- **IPv4-only TV-facing proxy.** The proxy binds to and advertises an IPv4 LAN address
  (`core.net.LanInterfaceSelector` selects an IPv4 candidate; the upstream `core.net.ServerUrlValidator`
  classifies IPv6 hosts, but the downstream bind does not). On an **IPv6-only LAN segment with no
  IPv4**, the TV cannot reach the phone. Accepted limitation: Cast (mDNS) and DLNA
  (SSDP `239.255.255.250`) discovery are IPv4-centric in practice, so IPv6-only home setups are rare.
  Full dual-stack bind/advertise (bracketed IPv6 URLs end-to-end) is deferred to a connected-machine
  change with on-device validation ([NEXT_STEPS.md](NEXT_STEPS.md)).

## Security caveats (by design)

- Phone→TV uses plaintext HTTP on the LAN (accepted residual risk; mitigated by session-limited,
  high-entropy URLs — [NETWORK_SECURITY.md](NETWORK_SECURITY.md)).
- Orphaned Jellyfin transcodes after abrupt process death are handled best-effort only.
