# Compatibility Test Matrix

Real playback testing is **mandatory** — codec probing from the sender alone is not trusted. The
compatibility runner (`diagnostics`/`CompatibilityTestRunner`) records, for each attempt:

protocol; target; receiver app ID (if Cast); Jellyfin route type; media source; stream mode;
codecs / container / HDR / bitrate / subtitles / MIME; proxy status; target load; first frame; 30 s
play; seek; pause/resume; stop; Range behavior; buffer behavior; throughput; Cast errors / DLNA
faults; Jellyfin playback mode; `PlaySessionId` presence; cleanup status.

## Per-target playback gate

For every target+media combination: **load phone URL → first frame → 30 s play → seek → pause/resume
→ stop.** A target "passes" only if it completes this gate.

## Manual test set (§20)

Networking / routing:
- LAN Jellyfin over HTTP and HTTPS.
- Remote HTTPS Jellyfin; remote HTTP **blocked** (must fail closed).
- Jellyfin through VPN and split-tunnel.
- Local-network permission revoked mid-session.
- VPN route change; Wi-Fi IP change; guest isolation.
- IPv4 LAN (primary). On an IPv6-only LAN segment the IPv4-only proxy bind is expected to fail closed
  with a clear diagnostic (see [KNOWN_LIMITATIONS.md](KNOWN_LIMITATIONS.md) → Networking constraints);
  dual-stack networks should resolve to IPv4.

Targets:
- Chromecast / Google TV (Cast).
- Android TV (Cast).
- Samsung TV (DLNA).
- LG TV (DLNA).

Media (codec/container/subtitle):
- H.264/AAC MP4; H.264/AC3; HEVC/EAC3; MKV/DTS; MKV/TrueHD.
- MP4 `moov` placement: fast-start (moov at front) vs moov-at-end — compare startup time and the first
  seek (the range proxy serves both, but a trailing moov costs an extra startup round-trip).
- Subtitles: SRT; WebVTT; ASS/SSA; PGS; forced transcode; subtitle burn-in.
- 4K / HDR.

Lifecycle / resilience:
- Target disconnect; screen off; app background/killed; server restart; token revoked; foreground
  service killed; memory pressure.

## Samsung/LG focused tests

discovery; `GetProtocolInfo`; `SetAVTransportURI`; Play/Pause/Seek/Stop; H.264/AAC MP4; H.264/AC3;
HEVC if available; MKV if available; HLS if supported; external SRT / no subtitles / forced
transcode; phone-proxy reachability with VPN/split-tunnel; same-Wi-Fi and guest-isolation failure.

## Result template

| Field | Value |
| --- | --- |
| Date / app version | |
| Protocol (Cast/DLNA) | |
| Target (model) | |
| Receiver app ID (Cast) | |
| Jellyfin route (LAN-HTTP/LAN-HTTPS/remote-HTTPS/VPN/split) | |
| Media source / container | |
| Video codec/profile/level/HDR/bitrate | |
| Audio codec/channels | |
| Subtitle format / mode | |
| Stream mode (direct/remux/audio-transcode/HLS/burn-in) | |
| Proxy status | |
| Target load / first frame | |
| 30 s play | |
| Seek | |
| Pause / resume | |
| Stop | |
| Range behavior (200/206/416) | |
| Buffer behavior (pass-through/prebuffer/pressure) | |
| Throughput | |
| Cast error / DLNA fault | |
| Jellyfin playback mode | |
| PlaySessionId present | |
| Cleanup confirmed (transcode stopped, buffers cleared) | |
| Pass / Fail + notes | |

> No physical Cast/DLNA hardware is available in the build sandbox, so this matrix ships as the
> required manual test plan rather than executed results. DLNA/Cast 30-second acceptance is contingent
> on test hardware (documented in [KNOWN_LIMITATIONS.md](KNOWN_LIMITATIONS.md)).
