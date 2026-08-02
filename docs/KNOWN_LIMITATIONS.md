# Known Limitations

These constraints distinguish implemented, unit-verified policy from behavior that still needs a
specific server, phone, network, or physical receiver. They are not broad compatibility promises.

## Build / environment

- **Android verification is environment-dependent.** `assembleDebug`, `testDebugUnitTest`, and
  `lintDebug` require Android SDK 37 plus resolved Google Maven dependencies. A host without those
  dependencies must report the gate as unavailable rather than treating the core-only fallback as a
  full Android build. See [BUILD.md](BUILD.md) and [API37_MIGRATION.md](API37_MIGRATION.md).
- **`gradle/verification-metadata.xml` is not present.** Generate and review it with
  `./gradlew --write-verification-metadata sha256 help` on a connected host before relying on
  dependency verification.
- Dependency versions remain pinned in the version catalog and should be re-resolved by CI before a
  release; a successful historical build is not a guarantee that an external repository is available.

## Hardware-dependent acceptance criteria

- **Cast 30-second playback** and **DLNA 30-second playback** acceptance require physical
  Chromecast/Google TV and Samsung/LG hardware, which are unavailable in the sandbox. The code paths,
  controllers, and the [compatibility matrix](COMPATIBILITY_MATRIX.md) are in place; the live gate
  must be executed on real devices.
- **On-device local transcoding is experimental, not production-certified.** Its active path is
  Cast-only HLS/fMP4 using a hardware H.264/AAC floor and opportunistic HEVC/AAC. Sustained encoder
  speed, thermal throttling, fMP4 timeline/seek behaviour, A/V continuity, and receiver behaviour under
  speculative requests still require physical validation. It must not be represented as reliable across
  OEM phones or Cast devices.
- The optional live Jellyfin integration test needs an explicitly configured server and skips itself
  when none is reachable. Ordinary unit/assemble/lint gates therefore do not prove live server behavior.

## Functional scope (intentional MVP boundaries)

- **HLS memory-only proxying** is implemented at the core level (`HlsRewriter`) but the full
  live-transcode HLS path (playlist refresh, segment streaming, CORS for Web Receiver) must be
  validated against a transcoding Jellyfin server; the verified primary path is file/range proxying.
- **The phone transcode output is deliberately narrow.** It publishes HLS with fMP4 fragments for
  compatible Cast receivers only. There is no phone DASH or MPEG-TS muxer, no AV1/VP9 or CPU encoder
  path, and no Main10/HDR output or conversion contract. H.264/AAC is the compatibility floor; HEVC/AAC
  is hardware- and receiver-gated. DLNA does not receive an on-device HLS/fMP4 stream.
- **Remote Jellyfin-to-phone transcoding is not implemented.** Source capability models now expose the
  necessary seekability, reopenability, streamability, and client/server-transcode gates, but Jellyfin
  has no authenticated origin-pinned client-transcoder input provider. Server transcoding remains the
  preferred online compatibility route; the phone transcodes only eligible local files for Cast.
- **Rolling in-memory prebuffer** (Phase 9) policy + decisions are implemented and unit-tested; wiring
  it into the live streaming loop beyond pass-through is a follow-up.
- **Jellyfin SDK vs HTTP API:** the playback path uses the documented Jellyfin **HTTP API** (OkHttp)
  to avoid inventing/guessing SDK signatures; the SDK is retained in the catalog for models/future and
  carries LGPL-3.0 obligations ([LICENSES.md](LICENSES.md)).
- **Physical-TV and playback UI require device validation.** The live physical-TV picker and bound
  Now Playing seek/volume/recovery controls are implemented, but receiver support and error semantics
  still vary by Cast/DLNA firmware. Audio/subtitle switching remains less complete than core transport
  controls.
- Audio/video track switching, embedded/ASS/SSA/PGS subtitles, and codec probing are treated as
  unverified / test-required, not guaranteed.
- Out of scope by design (§1): direct Jellyfin-to-TV, screen mirroring, broad/general-purpose phone
  transcoding, a general media disk cache, music/photos/live-TV/SyncPlay, native TV apps, and Media3
  Cast. The implemented app-private offline download and limited experimental local Cast HLS/fMP4
  paths are narrow exceptions, not general caching/transcoding systems.
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
- Authenticated Jellyfin media is intentionally same-origin only. Cross-origin PlaybackInfo/HLS resources
  and redirects are rejected instead of forwarding the Jellyfin Authorization header; external CDN or
  `.strm` sources therefore need a separately safe server-side route.
- Orphaned Jellyfin transcodes after abrupt process death are handled best-effort only.
