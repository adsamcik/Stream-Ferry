# Resilience: spotty-link streaming, WireGuard binding, whole-library paging

This app's job is to relay Jellyfin media to a TV **through the phone** over a home LAN, while the
phone reaches Jellyfin over whatever path is available (LAN, remote HTTPS, or a VPN such as
WireGuard). Two realities make naïve relaying unreliable:

1. **The upstream link dips.** Average throughput can be fine while short stalls/resets truncate an
   in-flight transfer. A single upstream fetch with no recovery means one dip ends playback.
2. **A VPN adds a second interface.** When WireGuard/OpenVPN is up to reach a remote Jellyfin, the
   OS exposes a tunnel interface (`wg0`/`tun0`) that often *also* carries a site-local address. Bind
   the TV-facing proxy there and the TV — which is only on the real Wi-Fi LAN — can never reach it.

The recovery logic lives in the framework-free `com.adsamcik.streamferry.core` package so it is
deterministic and exhaustively unit-tested (runs in the sandbox without the Android toolchain; see
[BUILD.md](BUILD.md)). The Android layer (`data.proxy.LocalProxyServer`,
`diagnostics.NetworkInfoProvider`) is thin wiring around it.

**Security invariant (unchanged):** none of these types hold the Jellyfin URL, token, or upstream
URL, and they log only plain numbers (offsets, attempt counts, page indexes). The TV still only ever
sees the opaque `http://PHONE_LAN_IP:PORT/session/<id>/stream` URL.

---

## 1. Resilient streaming (upstream reconnect-and-resume)

### Components

| Type | Package | Responsibility |
| --- | --- | --- |
| `RetryBudget` | `core.resilience` | Bounded retry/backoff config (no unbounded loops). |
| `Backoff` | `core.resilience` | Deterministic capped exponential backoff with equal-jitter. |
| `ResilientStreamPolicy` | `core.resilience` | Per-request state machine: forwarded bytes, resume offset, retry-vs-give-up decision. |
| `UpstreamRetry` | `core.resilience` | Classifies upstream HTTP status (success / retryable / fatal). |
| `LocalProxyServer` | `data.proxy` | Performs the sockets/HTTP and the backoff sleep, driven by the policy. |

### Flow

```
serve():
  parse client Range -> absolute [rangeStart, rangeEndInclusive]
  open upstream (with initial Range)         # FETCH FIRST
  if not 200/206 -> 502 Bad Gateway          # validate BEFORE writing headers
  write response headers ONCE
  streamResilient():
    loop:
      copy body -> downstream through a fixed 64 KiB buffer
        record each forwarded byte in the policy
        never forward past rangeEndInclusive
        downstream write fails -> ABORT (DOWNSTREAM_GONE); TV is gone, stop pulling upstream
      upstream read fails / premature EOF inside the window ->
        policy.onRecoverableFailure(jitterRoll):
          GiveUp  -> end transfer
          Retry   -> sleep backoff, reopen upstream with Range: bytes=<nextOffset>-<end>, continue
```

The TV's downstream socket **stays open across an upstream dip**, so the renderer never observes the
failure — it just sees bytes arrive a little later. The reconnect resumes at the exact next byte via
an HTTP Range request, so no byte is duplicated or skipped.

### Key decisions

- **Fetch-first, then headers.** The first upstream response is validated before any status line is
  written downstream, so an initial failure becomes a clean `502` instead of a half-written `200`.
- **Budget resets on sustained progress.** After `DEFAULT_PROGRESS_RESET_BYTES` (4 MiB) of clean
  forwarding the consecutive-failure counter resets, so a long, mostly-healthy movie isn't capped by
  a single global retry budget — only a *burst* of failures with no progress gives up.
- **Backoff is bounded and de-correlated.** Delay = `base * multiplier^(attempt-1)`, capped at
  `maxDelayMillis` (8 s) *before* equal-jitter. Defaults: base 250 ms, ×2, max 8 s, 6 consecutive
  failures. A flapping link therefore can't busy-spin or grow unbounded.
- **End-bound honoured.** The copy never writes past the client's requested end; reaching it is a
  clean `COMPLETE`.
- **Unknown length (transcode/open-ended).** When the total length is unknown (`rangeEndInclusive`
  is null, e.g. a transcode), byte-offset resume isn't meaningful, so a clean EOF counts as complete
  rather than triggering a resume. HLS/transcode segment-level resilience is a separate concern at
  the playlist/segment layer (`core.hls`), out of scope for the byte-range resume here.
- **`200`-on-resume fallback.** Jellyfin static direct streams honour Range (`206`). If a resume
  request is answered with `200` (Range ignored), the proxy discards the already-delivered prefix
  (`policy.nextOffset` bytes) to keep the downstream byte stream contiguous.

### OkHttp tuning (`LocalProxyServer.defaultClient`)

- `readTimeout = 30 s` bounds how long a stalled upstream read may hang before throwing — that
  exception is exactly what triggers the resume path, so it's kept moderate (catch a real stall, not
  a momentary dip).
- **No `callTimeout`** (default 0): a single transfer can legitimately last a whole movie; an overall
  cap would kill long playbacks. Instead a **throughput watchdog** (`core.resilience.ThroughputWatchdog`)
  closes the gap a whole-call timeout would leave: if a compromised/dead upstream *trickles* just
  enough bytes to dodge the read-timeout but stays below a tiny floor (~13 kbps, far under any video
  rung) for ~60 s after an initial grace, the transfer ends instead of hanging until the session TTL.
  A paused TV (downstream backpressure delivers no bytes to record) never trips it.
- `retryOnConnectionFailure(true)` lets OkHttp retry connection-establishment hiccups, complementing
  the higher-level read-resume.

---

## 2. WireGuard-correct proxy bind

`core.net.LanInterfaceSelector` chooses which local interface the proxy binds to and advertises to
the TV. `diagnostics.NetworkInfoProvider.lanIpv4()` enumerates `NetworkInterface`s into plain
`Candidate` descriptors and delegates the decision to the selector (so the security/correctness
choice is unit-tested).

Selection rules (`selectBindAddress`):

1. **Require** up, non-loopback, non-blank, **site-local** IPv4 (a public address is never used for
   the TV-facing proxy).
2. **Exclude** tunnel interfaces (`tun`, `tap`, `wg`, `ppp`, `ipsec`, `utun`, `gre`, `nordlynx`,
   `wireguard`), WAN/cellular interfaces (`rmnet`, `ccmni`, `pdp_ip`, …), and point-to-point links —
   these reach the *server*, not the local TV.
3. **Prefer** (score) LAN-named interfaces (`wlan`/`eth`/`en`/…) `+100`, physical (non-virtual)
   `+10`, and the conventional home range `192.168/16` `+5` over `10/8` (often VPN/corporate).
4. **Deterministic tie-break** by name then address, because the chosen address is handed to the TV
   and must be stable across calls.

The net effect: with WireGuard up, the proxy binds to the Wi-Fi/Ethernet LAN address the TV shares,
while Jellyfin traffic still egresses over the tunnel via normal OS routing.

---

## 3. Whole-library paging

`core.resilience.LibraryPagingPolicy` walks the entire library in bounded pages so even a large
library over a slow link loads reliably (one giant request risks a timeout). The HTTP repository
drives it: fetch `firstPage()`, then call `nextPage(current, itemsInPage, totalRecordCount)` until it
returns null.

- `StartIndex`/`Limit` pagination; default page 200, hard ceiling 500 (clamped, so a
  misconfiguration can't request an unbounded page).
- Terminates on a **short page**, an **empty page**, or reaching Jellyfin's `TotalRecordCount`.
- **Absolute page-count ceiling** (default 1000 pages): a server that keeps returning full pages with
  a missing/incorrect `TotalRecordCount` cannot drive an unbounded fetch loop or accumulate unbounded
  items in memory.
- Combine with `RetryBudget` to recover a transient page-fetch failure with backoff instead of
  failing the whole browse.

See the decision note in `data.jellyfin.JellyfinApiContract` for where this plugs into the documented
`GET /Items?StartIndex=&Limit=` browse endpoint.

---

## 4. API-level resilience and session recovery

The streaming proxy (above) resiliently relays *media bytes*. The **control-plane** Jellyfin API calls
(connect, login, browse, playback-info, progress reports) are equally exposed to a spotty link, so they
are hardened at their single HTTP chokepoint, `JellyfinClient.exec()`:

- **Bounded retry on transient failures.** Every API call is retried with the same capped
  exponential backoff + equal-jitter (`RetryBudget` + `Backoff`, here 3 consecutive retries) when it
  hits a connection-level `IOException` (reset/timeout/DNS blip) or a retryable status
  (`UpstreamRetry.isRetryableStatus`: 408/425/429/500/502/503/504). A definitive `4xx` is **not**
  retried — retrying a bad request only wastes battery and hammers the server.
- **Session-expiry (`401`) recovery.** A revoked/expired token surfaces as
  `JellyfinHttpException(401)` (`isUnauthorized`). It is thrown immediately (never retried), and the
  ViewModel detects a `401` anywhere in a browse/playback failure's cause chain
  (`MainViewModel.isSessionExpired`), clears the dead token, and routes back to the login screen with an
  actionable message — instead of showing a generic "couldn't load" error the user can't act on. The
  saved server address is kept so re-login is one step.

These calls run on `Dispatchers.IO`, so the backoff sleep is an ordinary blocking sleep on a background
thread (the same approach the byte proxy already uses). The retry/backoff logic itself is the
pure-JVM `core.resilience` code that is exhaustively unit-tested.

### Device-code login (Quick Connect)

For sign-in without typing a password on the phone, the app supports Jellyfin **Quick Connect**
(`JellyfinClient.quickConnectInitiate/quickConnectPoll/authenticateWithQuickConnect`):

1. The phone calls `POST /QuickConnect/Initiate` (device-identifying header, **no token**) and shows
   the returned 6-digit **code**.
2. The user approves that code on a device already signed in to their Jellyfin server.
3. The phone polls `GET /QuickConnect/Connect?secret=…` until approved, then exchanges the secret at
   `POST /Users/AuthenticateWithQuickConnect` for the same encrypted access token a password login
   would yield.

The **secret** is held only in the ViewModel (never shown in the UI or logged); only the human-facing
code is surfaced. The whole flow — initiate → approve → poll → authenticate — is covered end-to-end by
`JellyfinLiveIntegrationTest.liveQuickConnectDeviceCodeLogin` against a real Jellyfin server.

---

## Verification

All of the above is covered by pure-JVM tests that run in the sandbox:

- `core/ResilienceTest.kt` — backoff math, budget reset, resume offset/Range header, status
  classification, and paging termination.
- `core/LanInterfaceSelectorTest.kt` — tunnel/WAN rejection, Wi-Fi/Ethernet preference, and the
  VPN-up scenario.

Run them with the offline harness described in [BUILD.md](BUILD.md). The Android wiring
(`LocalProxyServer`, `NetworkInfoProvider`) requires the full Gradle/Android toolchain to compile and
is validated on a Google-Maven-connected machine (see [NEXT_STEPS.md](NEXT_STEPS.md)).
