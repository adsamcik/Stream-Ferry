# Stream Ferry

Stream Jellyfin video to a TV **through your Android phone**. The phone is the intentional, sole media
gateway — **the TV never accesses Jellyfin directly**.

```
Jellyfin server ──(LAN / remote HTTPS / VPN / split-tunnel)──▶ Android phone (in-RAM bounded proxy)
                                                                 │   local Wi-Fi / LAN only
                                                                 ▼
                                         Cast receiver  /  DLNA renderer (TV)
```

The TV receives **only** an ephemeral, high-entropy, phone-hosted URL such as
`http://PHONE_LAN_IP:EPHEMERAL_PORT/session/<256-bit-id>/stream`. It never receives the Jellyfin URL,
token, Authorization header, playlist/segment/subtitle/poster URLs.

## Status

The Android app and its unit suite are verified with `assembleDebug`, `testDebugUnitTest`, and
`lintDebug` on a host with Android SDK 37 and the resolved Gradle dependencies. Test totals evolve with
the code, so release/change reports should record the exact command result instead of relying on a
fixed count. See [docs/BUILD.md](docs/BUILD.md) and
[docs/API37_MIGRATION.md](docs/API37_MIGRATION.md).

Implemented end-to-end:

- **Connect & validate** — manual server URL entry with `https`/LAN-`http` policy, reachability test,
  username/password **or Quick Connect** (device-code) login, encrypted token storage, session restore
  across launches.
- **Gallery** — browse video libraries and drill into shows → seasons → episodes / movies to pick what
  to play (bounded paging for large libraries).
- **Watch state (Jellyfin)** — the gallery shows each item's native Jellyfin watch state: a **watched**
  check, an **unwatched-episode count** on series/seasons, and an in-progress **progress bar**; a
  **Continue Watching** row surfaces resumable items. From an item's detail you can **mark it watched or
  unwatched** (a series/season cascades to its episodes), written back via Jellyfin's `UserData` API.
- **Playback history** — a private screen under **Settings → Playback history** keeps up to 20
  renderer-confirmed recent items from Jellyfin, downloads, and local files, including exactly where
  playback stopped. Resume, watch again, remove one entry, or clear the history without deleting media.
- **Physical-TV picker** — live Cast (MediaRouter) + DLNA (SSDP) discoveries are conservatively
  aggregated into one TV row only when stable evidence is confident. Tapping the TV starts playback;
  Cast versus DLNA is normally an internal choice.
- **Adaptive bitrate** — during playback the proxy measures real throughput over a rolling **≥ 30 s**
  window and, with hysteresis, steps quality down/up intelligently (also reacting to renderer
  rebuffering). See [docs/ADAPTIVE_BITRATE.md](docs/ADAPTIVE_BITRATE.md).
- **Autoplay next episode** — when a Jellyfin TV episode finishes, the next episode in the series
  (across season boundaries) starts automatically on the same TV. On by default; toggle in **Settings**.
- **Correct-format playback** — Cast first makes a broad direct-play attempt when original quality is
  enabled, then retries with the conservative H.264/AAC profile only after explicit decode or
  unsupported-source evidence. Qualified failures are remembered per renderer and media format; generic
  network/proxy failures are not. DLNA uses its own profile and progressive MPEG-TS fallback. Every
  receiver URL remains phone-proxied, so the Jellyfin token never reaches the TV. See
  [docs/STREAM_SELECTION.md](docs/STREAM_SELECTION.md).
- **Optional offline downloads + library cache** — download a title's original file to app-private
  storage and cast it to a TV later **without the server** (the proxy serves the local file); the
  library listing is cached so browsing works offline. Both are opt-in, app-private, and removed by
  "Delete all app data". See [docs/DOWNLOADS.md](docs/DOWNLOADS.md).
- **System playback controls** — a media-style notification, lock-screen controls, hardware
  media-button support, and phone volume keys all control the TV via a `MediaSession` (plus in-app
  play/pause/seek/volume on the Now Playing screen).
- **Durable Now Playing + bounded recovery** — connection, preparation, buffering, same-stream retry,
  compatible server fallback, and an eligible alternate endpoint of the same physical TV stay on one
  Now Playing screen. Attempt generations reject stale callbacks, Stop cancels recovery, and exhaustion
  leaves Retry / Change TV / Stop available. Jellyfin API and download retries remain bounded too. See
  [docs/PHYSICAL_TV_PLAYBACK.md](docs/PHYSICAL_TV_PLAYBACK.md) and
  [docs/RESILIENCE.md](docs/RESILIENCE.md).
- **Smart Resume + optional night volume** — app-private playback-history records retain
  renderer-confirmed progress and stable previous-TV identity without persisting live sessions or proxy
  URLs. Night volume is off by default, reduction-only, sparse, and stops adjusting after a manual
  phone-side change.
- **On-device crash reports** — an uncaught-exception handler writes a **redacted** crash report
  (stack trace + app/device info, secrets stripped) to app-private storage; view, share or clear them
  from **Settings → Diagnostics**. Sharing includes only reports from the **latest build** (crashes from
  before an app update are kept but excluded, so a shared report is relevant to the running version). No
  telemetry — reports never leave the phone unless you share them.
- **Persistent diagnostics event log** — the redacted event log (playback/transcode decisions, TV
  communication when tracing is on) is periodically saved to app-private storage per session, so a report
  shared **after** the app is restarted still contains earlier playback events (not just the current
  launch). Like crashes, a shared report includes only the **latest build's** events, is excluded from
  backup, and is removed by "Delete all app data".

- Kotlin · Jetpack Compose · Material 3 · ViewModel/coroutines/Flow.
- `minSdk 34` (Android 14) · `targetSdk 37` / `compileSdk 37` (Android 17 — SDK verified present).
- Modes: phone-proxied **Cast** and **DLNA** (Samsung/LG and other renderers); when one confidently
  identified TV exposes both, endpoint preference and fallback are internal. No direct Jellyfin-to-TV
  path.

## Documentation

| Doc | |
| --- | --- |
| [ARCHITECTURE.md](docs/ARCHITECTURE.md) | Layering, packages, session lifecycle, verified core. |
| [THREAT_MODEL.md](docs/THREAT_MODEL.md) | Threats, vectors, mitigations, residual risks. |
| [PROXY_DESIGN.md](docs/PROXY_DESIGN.md) | HTTP/Range semantics, session security, FGS-type decision. |
| [MEMORY_BUFFER_POLICY.md](docs/MEMORY_BUFFER_POLICY.md) | RAM-only buffering, limits, pressure, seek. |
| [STREAM_SELECTION.md](docs/STREAM_SELECTION.md) | Choosing a TV-compatible Jellyfin stream. |
| [PHYSICAL_TV_PLAYBACK.md](docs/PHYSICAL_TV_PLAYBACK.md) | Physical-TV matching, bounded recovery, Smart Resume, night volume, and hardware checklist. |
| [ADAPTIVE_BITRATE.md](docs/ADAPTIVE_BITRATE.md) | Throughput-driven adaptive quality (≥ 30 s window). |
| [DOWNLOADS.md](docs/DOWNLOADS.md) | Optional offline downloads + library metadata cache. |
| [CAST.md](docs/CAST.md) · [DLNA.md](docs/DLNA.md) | Cast Sender SDK / manual UPnP integration. |
| [CASTING_RESEARCH.md](docs/CASTING_RESEARCH.md) | Condensed phone→TV casting research (Cast/DLNA/relay) behind the design. |
| [NETWORK_SECURITY.md](docs/NETWORK_SECURITY.md) | TLS policy, cleartext rules, logging/redaction. |
| [MANIFEST_PERMISSIONS.md](docs/MANIFEST_PERMISSIONS.md) | Permission inventory. |
| [VPN_LAN_DIAGNOSTICS.md](docs/VPN_LAN_DIAGNOSTICS.md) | VPN/split-tunnel/LAN diagnostics. |
| [DEPENDENCY_RISK.md](docs/DEPENDENCY_RISK.md) · [LICENSES.md](docs/LICENSES.md) | Supply chain + OSS/LGPL. |
| [COMPATIBILITY_MATRIX.md](docs/COMPATIBILITY_MATRIX.md) | Manual test plan + result template. |
| [BUILD.md](docs/BUILD.md) · [API37_MIGRATION.md](docs/API37_MIGRATION.md) | Build/test + SDK status. |
| [KNOWN_LIMITATIONS.md](docs/KNOWN_LIMITATIONS.md) · [NEXT_STEPS.md](docs/NEXT_STEPS.md) | Gaps + roadmap. |

## Build & test

See [docs/BUILD.md](docs/BUILD.md). On a host with Google Maven access:

```bash
./gradlew clean assembleDebug testDebugUnitTest lintDebug
```

The pure-JVM core subset can also be verified directly with `kotlinc` (procedure in docs/BUILD.md).

## Privacy

No telemetry, analytics, ads, cloud services, or tracking. The access token and the Jellyfin server
URL are stored encrypted (Android Keystore, AES-256-GCM); passwords are never stored. "Delete all app
data" removes profiles, tokens, metadata, playback history, target/compatibility data, diagnostics,
settings, and active buffers.
