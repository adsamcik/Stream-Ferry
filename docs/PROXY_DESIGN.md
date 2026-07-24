# Local Proxy Design

The local proxy is the security- and correctness-critical centerpiece. It reads from the Jellyfin
upstream and forwards bytes to the TV over the LAN, buffering **only in RAM**, only during active
playback.

## HTTP semantics (`core.http` + `data.proxy.LocalProxyServer`)

- Methods: `GET`, and `HEAD` where useful.
- **Byte Range requests** (RFC 9110 single range), parsed by `core.http.HttpRange`.
- Status selection by `core.http.HttpResponsePlan`:
  - `200 OK` for full-content GET when no range,
  - `206 Partial Content` for a satisfiable range,
  - `416 Range Not Satisfiable` for an unsatisfiable range (with `Content-Range: bytes */<size>`).
- Headers: `Content-Type`, `Content-Length` when known, `Content-Range`, `Accept-Ranges: bytes`.
- Seek is supported via range mapping onto the upstream.
- **Direct-play seekability (do not regress).** For the renderer to byte-range **seek** (and to *resume*
  at a saved position), the proxy must advertise the entity's `Content-Length` + a concrete
  `Content-Range` — without them a media element treats the stream as unbounded/non-seekable and a seek
  silently **restarts from 0**. So a direct-play session carries Jellyfin's `MediaSource` size
  (`UpstreamSource.totalLength` → `ProxySession.totalLength`), which `LocalProxyServer.serve` uses as the
  known total.
- **Seek strategy by stream type (`PlaybackEngine.seekTo`).** Three cases:
  1. **Direct play** — byte-range seekable; the renderer seeks the stream itself (`target.seekTo`).
  2. **HLS transcode** (Cast) — Jellyfin's VOD HLS playlist covers the **full timeline** (0..end)
     regardless of `startTimeTicks`, so it is seekable *like* direct play: the renderer seeks within the
     existing playlist (`target.seekTo`), which is exactly what the TV's own controls do. Re-resolving it
     server-side would reload a 0..end stream and play from the start ("restart from 0") — **do not**.
  3. **Progressive transcode** (non-HLS, DLNA) — a genuine live stream that begins at the requested
     position, with no meaningful byte length (`totalLength == null`) and not seekable in place; it is
     seeked **server-side** by re-resolving at the target position (`reloadStream`, Jellyfin honours
     `startTimeTicks`). Only this case re-resolves. See also [ADAPTIVE_BITRATE.md](ADAPTIVE_BITRATE.md).
- **Resume without a load-then-seek race.** The renderer must begin *at* the resume position: Cast sets
  it in the load request (`setCurrentTime`, load awaited); DLNA issues a `REL_TIME` `Seek` once the
  transport is actually `PLAYING` (see `DlnaTargetController`). A separate seek issued right after `load`
  races the (asynchronous) load and is dropped — the renderer then autoplays from 0.
- Seeking works even for a non-fast-start MP4 (`moov` atom at the end): the player range-fetches the
  trailing index first, costing one extra startup round-trip, so a `faststart` MP4 (or HLS) starts
  quicker. Startup/seek behaviour per `moov` placement is a recorded test variable
  ([COMPATIBILITY_MATRIX.md](COMPATIBILITY_MATRIX.md)).
- Handles client disconnect, target disconnect, and Jellyfin upstream disconnect; timeouts,
  cancellation, backpressure, and socket cleanup.
- **Upstream reconnect-and-resume on spotty links:** a mid-transfer upstream stall/reset does not end
  playback. The proxy validates the upstream **before** writing response headers (so an initial
  failure is a clean `502`), then streams; on an upstream read failure it reconnects with
  `Range: bytes=<nextByte>-<end>` and keeps feeding the **same open** TV connection, so the renderer
  never sees the dip. A downstream (TV) failure aborts immediately. All retry/backoff/offset
  decisions come from the pure-JVM `core.resilience.ResilientStreamPolicy` (bounded backoff, budget
  resets after sustained progress). See [RESILIENCE.md](RESILIENCE.md).
- Bounded memory buffering only (see [MEMORY_BUFFER_POLICY.md](MEMORY_BUFFER_POLICY.md)).

## What the proxy must NOT do

- Download the whole file before playback; write media to disk; expose arbitrary files; expose
  arbitrary upstream URLs; act as an open proxy; continue after playback; expose Jellyfin tokens/URLs;
  or log full URLs.

## Security model (`core.session.SessionRegistry`)

- Binds **only** during an active session; uses an **ephemeral port** where possible.
- **256-bit high-entropy session IDs**; unguessable per-session URLs of the form
  `http://PHONE_LAN_IP:PORT/session/<id>/stream`. The presented id is matched with a **constant-time
  comparison**, so a guess cannot learn from response timing how many leading characters were correct.
- **Sliding lifetime:** each active request renews the session's idle window (default 4h), bounded by
  an **absolute ceiling** (default 24h). An abandoned session dies one idle window after its last
  request, while a long continuous playback survives up to the ceiling (instead of being cut at a
  single fixed TTL).
- Rejects: unknown/expired sessions, arbitrary upstream URLs, path traversal (`..`), non-session
  paths, sub-paths beyond `stream`, and any request after stop — returning `Forbidden`/`NotFound`
  without leaking detail.
- **Bounded against flooding/oversized input:** an 8 KiB request cap + 30 s socket timeout per
  connection, a global and per-client-IP concurrent-connection cap (`core.net.ConnectionLimiter`),
  and a hard cap on reading an upstream HLS playlist into memory (`core.http.BoundedBody`).
- Never serves local files or debug endpoints in release.
- On stop: revoke the session and clear buffers.

The session→upstream mapping (upstream URL + Authorization) is held server-side, keyed by session id;
the TV only ever sees the opaque session URL.

## Foreground-service type decision record

**Chosen type: `mediaPlayback`** (with `FOREGROUND_SERVICE_MEDIA_PLAYBACK`).

Rationale: the service's sole purpose is to keep a **user-initiated, user-visible media stream**
playing on an external renderer (Cast/DLNA). Android's foreground-service-type guidance maps
continuous media playback — including casting/remote playback that must survive screen-off — to the
`mediaPlayback` type. Alternatives were rejected:

- `connectedDevice` — intended for companion-device interactions (Bluetooth/USB/peripheral
  management), not media delivery; using it would misrepresent the workload.
- `dataSync` — for finite upload/download/sync tasks; OS imposes time limits and it does not reflect
  active playback.
- `specialUse` — a last resort requiring explicit justification/allow-listing at review; unnecessary
  because a precise media type exists.

The service is started **only** for active playback, shows the persistent notification, and is stopped
on stop/error/cancel/session expiry. It never auto-starts on boot and never stays open without
playback. (If future Cast-only flows shift media decoding entirely off-device in a way that warrants a
different type, revisit and re-document here.)

### Start-foreground deadline ordering (do not regress)

`Context.startForegroundService()` obliges the service to call `Service.startForeground()` within a
few seconds (≈5 s) or the OS kills the process with `ForegroundServiceDidNotStartInTimeException`.
`onStartCommand` — where we call `startForeground()` — runs on the **main thread**, and so does
*everything* around a play: the **playback-screen recomposition** triggered by navigating to it, the
Cast/DLNA connect handshake, and the media load. So the deadline is really a race between `onStartCommand`
and all that other main-thread work.

**Primary defence — sidestep the deadline entirely when in the foreground.** The deadline applies *only*
to `startForegroundService()`. A **foreground** app (always true for a user-initiated play) may instead
call `startService()` and then `startForeground()` with **no timeout at all**. So `ProxyPlaybackService.start()`
calls `startService()` first and only falls back to `startForegroundService()` when the app is backgrounded
(e.g. a screen-off auto-reconnect), where `startService()` is disallowed. This makes the crash *impossible*
for the common initial-play case regardless of main-thread contention. The rules below still apply for the
background-reconnect fallback path (which does use `startForegroundService()`):

1. **Foreground the service while the UI is still on the (static) picker — before navigating to the
   playback screen — and block on a barrier until `startForeground()` has actually run**
   (`PlaybackEngine.play`/`playLocal` → `PlaybackServiceController.startAndAwaitForegrounded`, backed by
   `ProxyForegroundLatch`). The startup coroutine arms a request-token latch, asks Android to start the
   service (using `startService()` first and the typed `startForegroundService()` fallback when needed),
   then *suspends* until that exact request signals it foregrounded. Only then does it fire
   `onForegrounded` — the callback the ViewModel uses to switch the route to the playback screen — before
   beginning `connect()`. The earlier fix that foregrounded *after* `connect()` — and even the one that
   foregrounded before `connect()` but *after* the route had already changed — still lost the race,
   because the playback-screen recomposition (queued by the route change) sat ahead of `onStartCommand`
   on the main thread (observed on a Galaxy S24 / Android 16). **Do not navigate to the playback screen,
   start `connect()`, or do other heavy main-thread work before the foreground barrier resolves.** A
   rejected start or ~4 s confirmation timeout aborts startup before proxy-session creation or renderer
   load; it is never logged and ignored.
2. **`onStartCommand` foregrounds with the cheap fallback notification *first***, before touching the
   lazily-constructed `MediaSessionController` (which builds a `MediaSession` + full MediaStyle
   notification), then upgrades to the rich notification. The service signals `ProxyForegroundLatch` the
   instant `startForeground()` returns. Any unexpected delivery still foregrounds before stopping, so the
   contract is never left unmet.
3. **Playback startup runs off the main thread** (`withContext(Dispatchers.Default)`), so the main thread
   is free to dispatch `onStartCommand` while the engine works.

**Telemetry (diagnosing any future occurrence).** `start()` records which path it took, and the service logs
how long the deadline-critical `startForeground()` took relative to the `start()` request
(`logForegroundLatency`). The normal fast foreground path is debug-logcat only; a background
(`startForegroundService()`) start or a foreground near-miss (≥ 2 s) is surfaced as a warning in the
**exported diagnostics**, so a recurrence is immediately attributable to a specific path and margin rather
than guessed at.

### Surviving screen-off / Doze (wake + Wi-Fi locks)

A `mediaPlayback` FGS keeps the **process** alive, but it does **not** by itself keep the CPU running
or stop Wi-Fi from entering power-save when the screen turns off. A normal media player gets that for
free from the audio subsystem; here the phone is a **server** — it plays no local audio, it only serves
bytes to the TV — so nothing implicitly holds the CPU/Wi-Fi awake. Without help, a screen-off device
enters Doze CPU-idle (freezing the proxy's socket threads) and Wi-Fi power-save (latency spikes that
stall the TV's pull), and casting stutters or stops.

So for the **duration of active casting only**, `ProxyPlaybackService` holds two locks, acquired right
after it commits to foreground playback and released on stop/destroy (idempotent, non-reference-counted):

- a **partial `PowerManager.WakeLock`** (`PARTIAL_WAKE_LOCK`) — keeps the CPU serving the proxy + the
  adaptive/monitor loops while the screen is off;
- a **`WifiManager.WifiLock`** (`WIFI_MODE_FULL_LOW_LATENCY`) — keeps the Wi-Fi radio associated and
  awake so it doesn't sleep/disassociate when the device idles with the screen off. (Note: on API 34+
  — our `minSdk` — the low-latency / no-power-save *tuning* only applies with the screen on, and there
  is no public API to force Wi-Fi out of power-save while the screen is off; the held lock still keeps
  the radio up, which is the part that matters for a stalled pull, and the wake lock covers the CPU.)

Both are released the instant playback stops (and in `onDestroy`), so they never outlive a cast. The
Wi-Fi lock needs no extra permission; the wake lock needs `WAKE_LOCK` (see `docs/MANIFEST_PERMISSIONS.md`).

### Battery-optimization exemption (screen-off reliability)

A foreground service + wake lock keep the CPU up, but aggressive OEM power management — notably Samsung
"deep sleep" / "Sleeping apps" — can still **suspend the app's network** when the screen is off and the
device idles, so the proxy stops feeding the TV and playback stalls on "buffering" until the display
wakes. There is no wake/Wi-Fi-lock that overrides this; the lever is a **battery-optimization exemption**
("unrestricted" / "Never sleeping app"). The app declares `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` and
surfaces an **"Allow background playback (screen-off)"** toggle in Settings that opens the system prompt
(`AndroidNetworkPermissionManager.isBatteryOptimizationExempt()` / `batteryOptimizationRequestIntent()`).
Playback works without it — it's a user-approved reliability opt-in — and the current state is reported in
the diagnostics (`background: unrestricted | battery-optimized`).

## Lifecycle / cleanup

On stop, error, target disconnect, upstream disconnect, network loss, or session expiry: cancel
upstream reads, close sockets, clear buffers, revoke the session, stop the Jellyfin transcode/HLS
session (see [reporting](#reporting-link)), and stop the foreground service. On app restart, active
Cast/proxy state is recovered where feasible; otherwise the app safely reports stop/cleanup if the
Jellyfin session is known. Progress is never invented.

<a id="reporting-link"></a>See `playback.reporting.JellyfinPlaybackReporter` for start/progress/
pause/seek/stop/error reporting and transcode cleanup.

## HLS proxying

If Jellyfin returns HLS, the phone proxies playlists and **rewrites every URL** (playlist, segment,
subtitle) to phone proxy session URLs (`core.hls.HlsRewriter`), proxies segments/subtitles through
session URLs, keeps only bounded segment data in RAM, preserves MIME types, provides CORS headers
where Cast/Web Receiver requires them, enforces session authorization on every playlist/segment
request, handles playlist refresh, and cleans up the Jellyfin HLS/transcode session on stop/error. No
playlist/segment/subtitle is written to disk. For the earliest MVP, the file/range proxy path is the
primary verified path; HLS rewriting is implemented in the core but must be validated against a live
transcoding server (see [KNOWN_LIMITATIONS.md](KNOWN_LIMITATIONS.md)).
