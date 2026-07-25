# Next-Step Recommendations

Ordered follow-ups to take the MVP scaffold to a fully verified product.

1. **Build on a connected machine.** Restore Google Maven access, run the full
   `clean / assembleDebug / testDebugUnitTest / lintDebug / dependencies` set, fix any
   resolution/version drift, and pin the re-verified Compose BOM / Cast versions
   ([API37_MIGRATION.md](API37_MIGRATION.md)).
2. **Generate dependency verification metadata** (`--write-verification-metadata sha256`) and add CI
   for Gradle Wrapper validation, forbidden-repo checks, dynamic-version checks, and secret scanning.
3. **Stand up a Jellyfin test server** and validate the live path: auth, PlaybackInfo, media sources,
   `PlaySessionId` preservation, progress/stop reporting, and transcode/HLS cleanup.
4. **Execute the device gate** (load → first frame → 30 s → seek → pause/resume → stop) on
   Chromecast/Google TV (Cast) and Samsung/LG (DLNA); fill in [COMPATIBILITY_MATRIX.md](COMPATIBILITY_MATRIX.md).
5. **Harden the live streaming loop:** wire the rolling prebuffer + memory-pressure decisions into the
   pass-through loop; validate Range/seek behavior over real sockets; add instrumented proxy tests.
   *(Partly done: upstream reconnect-and-resume on spotty links — `core.resilience.ResilientStreamPolicy`
   wired into `LocalProxyServer` — plus WireGuard-safe bind selection (`core.net.LanInterfaceSelector`)
   and whole-library paging (`core.resilience.LibraryPagingPolicy`) are implemented and pure-JVM
   unit-tested. See [RESILIENCE.md](RESILIENCE.md). Still TODO here: rolling-prebuffer/memory-pressure
   integration and on-device socket/seek validation.)*
6. **Validate HLS end-to-end** against a transcoding server. *(Implemented: Cast first attempts broad
   direct play and falls back to H.264/AAC only after qualified decode evidence; DLNA uses a separate
   profile and progressive MPEG-TS fallback. The proxy rewrites HLS playlists + proxies segments so the
   token never reaches the TV
   (`core.hls.HlsSegmentRegistry` + `HlsRewriter`, unit-tested). Still TODO: validate playlist
   refresh / segment streaming / seek on real Cast + DLNA hardware against a transcoding server, and
   per-renderer capability probing via DLNA `GetProtocolInfo`.)*
7. **Decide the Jellyfin SDK vs HTTP-API question** definitively and, if keeping the LGPL SDK, finalize
   relinking/keep-rule compliance ([LICENSES.md](LICENSES.md)).
8. **Flesh out the Compose screens:** *(largely done — manual server connect + validate, login,
   library/show/movie gallery with drill-down, live Cast/DLNA device picker, and a playback screen with
   bound play/pause/seek/stop plus a live adaptive-quality readout are implemented, with deferred
   runtime-permission requests and loading/empty/error states.)* Still TODO: audio/subtitle pickers,
   volume control, and Compose UI + accessibility tests.
9. **Adaptive bitrate** is implemented: the pure-JVM `core.adaptive.AdaptiveBitrateController` (≥ 30 s
   throughput window, hysteresis, rebuffer-aware) is unit-tested and wired into `PlaybackEngine`, which
   switches quality mid-stream by re-resolving PlaybackInfo at the current position
   ([ADAPTIVE_BITRATE.md](ADAPTIVE_BITRATE.md)). Still TODO: validate the mid-stream switch on real
   Cast/Google TV + DLNA hardware and tune the ladder/thresholds from field data.
10. **Add the compatibility runner UI + redacted diagnostics export** and the full VPN/LAN test
    automation ([VPN_LAN_DIAGNOSTICS.md](VPN_LAN_DIAGNOSTICS.md)).
11. **Validate the local hardware-transcode exception:** add explicit Media3 encoder/output-contract
    checks, thermal admission, and Cast device coverage before treating it as broadly reliable.
12. **Release hardening pass:** verify R8 keep rules against the real dependency set, confirm no debug
    trust anchors/endpoints ship, and complete the OSS license report.
13. **Optional:** SBOM generation via a trusted plugin; per-server custom-CA UI; **dual-stack / IPv6
    TV-facing proxy** bind +
    advertise (bracketed IPv6 URLs end-to-end), validated on a real IPv6-only LAN — today the proxy is
    IPv4-only ([KNOWN_LIMITATIONS.md](KNOWN_LIMITATIONS.md) → Networking constraints).
