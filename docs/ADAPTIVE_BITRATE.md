# Adaptive bitrate (throughput-driven quality, ≥ 30 s window)

During playback the phone proxies bytes from Jellyfin to the TV. If the phone→server link cannot
sustain the current quality, the TV rebuffers. Video Bridge watches the link and **gradually,
intelligently adjusts the requested quality** so playback stays smooth — without thrashing.

The decision logic lives in the framework-free, deterministic, unit-tested
`com.videobridge.core.adaptive.AdaptiveBitrateController` (with `BitrateLadder`). Like the rest of
the `core` package it holds **only numbers** (byte counts, bitrates, timestamps), so it is safe to log
and never touches the Jellyfin URL, token, or the TV-facing proxy URL.

## Signals

1. **Average throughput** — the real bytes the proxy delivered downstream to the TV, reported by
   `LocalProxyServer.byteListener` and averaged over a rolling window of **at least 30 seconds**.
   This is the "average possible speed" measured over time.
2. **Rebuffer events** — explicit renderer stalls. Cast reports `PLAYER_STATE_BUFFERING` via
   `RemoteMediaClient.Callback`; DLNA is polled (`GetTransportInfo`/`GetPositionInfo`). Each stall is
   recorded in the same window.

Throughput is accumulated into fixed time buckets (default 1 s) so memory is bounded regardless of how
often bytes arrive (~30–60 buckets retained).

## Decision rules (`AdaptiveBitrateController.evaluate`)

The controller exposes a sorted **bitrate ladder** (`BitrateLadder.forSource` caps it at the source
bitrate) and a current rung. Each evaluation:

- **Warm-up** — returns `Hold` until ≥ 30 s of data has accumulated (`minObservationMillis`), so a
  decision is always based on a full window.
- **Cooldown / hysteresis** — at most one change per `minSwitchIntervalMillis` (default 30 s). The
  first decision is gated only by warm-up; the cooldown applies to every change *after* the first.
- **Step down** when the window shows rebuffering, or the average can't sustain the current rung. It
  jumps straight to the highest rung the measured throughput can support with safety headroom
  (`avg × downSafetyFactor`, default 0.8) — intelligent, not a blind single notch — but always at
  least one rung so it makes progress. It never drops below the lowest rung.
- **Step up** exactly **one** rung, and only when the average comfortably exceeds the next rung
  (`next × upHeadroomFactor`, default 1.3) with **zero** rebuffering in the window — cautious, so it
  doesn't immediately re-stall.
- After any change the window is **reset**, so the next decision is again based on a fresh ≥ 30 s
  sample at the new quality.

All thresholds live in `AdaptiveBitrateController.Config` and are validated (`windowMillis` and
`minObservationMillis` must be ≥ 30 s).

## Applying a change (`PlaybackEngine`)

A quality change varies `MaxStreamingBitrate` in the Jellyfin DeviceProfile — the same mechanism
Jellyfin clients use for adaptive quality. On a `ChangeBitrate` decision the engine:

1. captures the current absolute position,
2. re-resolves PlaybackInfo at that position with the new bitrate (Jellyfin returns a new direct or
   transcoded source),
3. tears down the old proxy session (which also stops the old server-side transcode), creates a new
   one, and
4. reloads the renderer with the **new phone proxy URL** at the captured position (HLS transcodes
   resume server-side at the position; direct streams are re-seeked).

The TV therefore only ever receives an opaque `http://PHONE_LAN_IP:PORT/session/<id>/stream` URL,
before and after the switch. Hysteresis keeps switches infrequent, so the brief reload is rare.

For HLS the engine tracks an absolute position as `streamStart + rendererReported`, so repeated
switches resume at the correct place. A seek resets the measurement window (without changing quality)
to avoid a spurious down-shift from the seek gap.

## Manual override (user-selected quality)

Adaptation is the default (**Auto**), but the user can pin a specific quality from the playback
screen's quality card. The picker is built by the pure `com.videobridge.core.adaptive.QualityMenu`
(**Auto** plus each ladder rung, best-quality first, labelled in Mbps), so a manual pick is always one
of the exact rungs the adaptive controller uses — never an invented bitrate.

`PlaybackEngine.selectQuality(bitrateBps)`:

- **A rung** — snaps the request to the ladder (`indexAtOrBelow`), re-resolves at that bitrate at the
  current position (the same reload path as an adaptive switch — Jellyfin transcodes to fit), and sets
  a `manualQuality` flag that **pauses adaptation**. The monitor keeps measuring the link (so "Measured
  link" still updates) but makes no automatic changes. The flag is re-checked under the engine lock
  before any adaptive switch, so a pin can't race with an in-flight auto-adjust.
- **`null` (Auto)** — clears the flag and resumes adaptation from the current rung.

The picker only appears for an online session whose ladder has more than one rung (a single-rung
source has no meaningful choice), and it is reset to Auto when playback stops.

## Verification

`app/src/test/java/com/jellyfinbridge/core/AdaptiveBitrateControllerTest.kt` (pure-JVM, runs in the
sandbox) covers: warm-up hold before 30 s, average-throughput computation, step-down on starvation,
step-down on rebuffering, cautious one-rung step-up, no step-up without headroom, the lowest-rung
floor, window reset after a change, the switch cooldown, and source-capped ladders. Run them with the
offline `kotlinc` harness in [BUILD.md](BUILD.md) or `./gradlew testDebugUnitTest`.
