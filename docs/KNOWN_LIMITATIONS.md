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
- **On-device transcoding remains hardware-dependent.** Local, downloaded and provider inputs share
  continuous HLS/fMP4 output for Cast and MPEG-TS output for DLNA, using H.264/AAC or admitted HEVC/AAC.
  Sustained encoder speed, thermal throttling, HDR-to-SDR conversion, A/V continuity and receiver
  behavior still require physical validation. Build and unit-test results do not establish compatibility
  across OEM phones or TVs. See [ONDEVICE_TRANSCODE.md](ONDEVICE_TRANSCODE.md).
- The optional live Jellyfin integration test needs an explicitly configured server and skips itself
  when none is reachable. Ordinary unit/assemble/lint gates therefore do not prove live server behavior.

## Functional scope (intentional MVP boundaries)

- **HLS memory-only proxying** is implemented at the core level (`HlsRewriter`) but the full
  live-transcode HLS path (playlist refresh, segment streaming, CORS for Web Receiver) must be
  validated against a transcoding Jellyfin server; the verified primary path is file/range proxying.
- **Phone output formats remain limited.** Cast uses HLS/fMP4 and DLNA uses continuous MPEG-TS.
  H.264/AAC is the compatibility floor; HEVC/AAC is hardware- and receiver-gated. There is no phone
  DASH, AV1/VP9 or CPU encoder path, and HDR sources are converted to SDR rather than retained as HDR.
- **Provider input remains origin-pinned and phone-proxied.** Jellyfin server conversion remains
  available in Auto; On device reads an authenticated, opaque provider input through the phone gateway.
  An unavailable source or unsupported input still cannot be repaired by changing the TV protocol.
- **Rolling in-memory prebuffer** (Phase 9) policy + decisions are implemented and unit-tested; wiring
  it into the live streaming loop beyond pass-through is a follow-up.
- **Jellyfin SDK vs HTTP API:** the playback path uses the documented Jellyfin **HTTP API** (OkHttp)
  to avoid inventing/guessing SDK signatures; the SDK is retained in the catalog for models/future and
  carries LGPL-3.0 obligations ([LICENSES.md](LICENSES.md)).
- **Physical-TV and playback UI require device validation.** The live physical-TV picker and bound
  Now Playing seek/volume/recovery controls are implemented, but receiver support and error semantics
  still vary by Cast/DLNA firmware. Audio/subtitle selection is implemented, but actual decoding,
  subtitle rendering and track-switch behavior still need real-phone and receiver testing.
- Unsupported audio or subtitle formats are reported explicitly. Available track discovery does not
  guarantee that every phone can decode the selected track or every subtitle styling feature.
- Out of scope by design (§1): direct Jellyfin-to-TV, screen mirroring, broad/general-purpose phone
  transcoding, a general media disk cache, music/photos/live-TV/SyncPlay, native TV apps, and Media3
  Cast. App-private offline downloads and the configured streaming phone encoder are the supported
  exceptions; they do not provide a general media disk cache or arbitrary conversion service.
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
