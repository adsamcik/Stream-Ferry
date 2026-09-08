# Delivery-backed progress recovery

When Cast status is delayed, the phone can save a bounded estimate of playback progress. Fresh TV
status remains authoritative, including a lower position after a pause or a seek with the TV remote.
The SDK's periodic approximate-position heartbeat is explicitly separate from a new receiver report.

## Estimation and recovery

The estimator anchors at a confirmed TV position and uses Android's monotonic `elapsedRealtime()` clock,
which includes deep sleep. It advances at the reported playback rate only while the last confirmed state
was playing. It uses contiguous delivered media around that anchor to constrain the estimate. Requests,
prefetch at an unrelated position, byte counts alone, retries, subtitles, keys and initialization metadata
cannot establish playback progress.

| Evidence | Bound |
| --- | --- |
| Confirmed position and playing state, no usable delivery timeline | Up to 30 seconds of elapsed-time inference |
| Contiguous delivery, with new coverage in the past 30 seconds | Up to 5 minutes since the last TV confirmation |
| Pause, buffering, disconnect, pending seek or stopped session | No further inference until a new playing confirmation |
| Seek, reload or replacement session | New delivery epoch; old in-flight transfers cannot contribute |

The inferred position never exceeds contiguous delivered coverage or a known duration. Recovery rewinds
by at least five seconds; the margin grows with confirmation age and observed buffering. These are
conservative policy bounds, **not measured error guarantees or statistical confidence intervals**. A
receiver can pause while continuing to buffer. The phone therefore stops extrapolating when the bounds
expire, preserving its last saved checkpoint rather than advancing indefinitely.

History records retain the confirmed position and a separate estimate with its original anchor, age,
margin, evidence type and capture time. Normal disk checkpoints are spaced five seconds apart; lifecycle
checkpoints, seeks and corrections to a persisted estimate can write immediately. Version 1 and 2 records
migrate to version 3. Invalid optional estimates are discarded without losing confirmed progress. On
restart the saved point is used as-is, with the recovery rewind; time spent crashed or disconnected is
never added. The history action says **Resume around** when it uses an estimate. Inferred credits/end
positions cannot finish a history record or erase an otherwise resumable confirmed checkpoint.
Legacy local/download resume storage, saved queues and reconnect use the same recovery point. A cached
legacy position is reconciled as cached data; it cannot masquerade as a fresh TV report and bypass the
new estimate.

## Supported media evidence

- **HLS:** full-timeline media playlists starting at media sequence zero. `EXTINF` durations locate each
  segment; `EXT-X-BYTERANGE` requires the exact advertised range to complete. Master variants identify
  the media playlists. External audio groups require overlapping completed audio and video coverage;
  subtitles cannot stand in for either. Successful non-HEAD response bodies count after flush. Truncated,
  failed and revoked responses do not. The phone's HLS/fMP4 transcode uses its media playlist similarly.
- **Conventional MP4/MOV:** bounded, opportunistic parsing of `moov` bytes already relayed to the TV.
  `stts`, `stsc`, `stsz`, `stco`/`co64`, simple edit trims and composition offsets map whole delivered chunks
  to time. Coverage must overlap across the indexed audio/video tracks. Partial HTTP writes/ranges can
  accumulate into complete chunks. B-frame reordering shrinks the outer boundaries conservatively. The
  index can be at the beginning or after `mdat`, provided the necessary headers are observed. Metadata
  is capped at 8 MiB, one million samples and 100,000 chunks across all tracks; intervals and track counts are
  also bounded. No additional source requests, demuxing, decoding or transcoding are introduced.
- **Fallback:** sliding live HLS, discontinuities/gaps, fragmented MP4 outside HLS, complex edit lists,
  external MP4 data references, unavailable/oversized indexes, Matroska/WebM and unindexed progressive
  streams retain the short elapsed-time fallback. Unsupported metadata never becomes a byte-percentage
  guess. Media indexes and delivery intervals remain in memory, scoped to the proxy session.

## Jellyfin behavior

Normal Started/Progress/Stopped lifecycle events retain the confirmed position. A separate position-only
user-data update may save the recovery estimate when delivery supports it, the captured anchor age is at
most two minutes, its margin is at most 15 seconds, and it remains a resumable point before the credits.
Elapsed-time-only guesses are not sent through this path. Once saved, that checkpoint can remain frozen
through later lifecycle reports and Stop until fresh TV truth or a seek replaces it.

The request is `POST /UserItems/{itemId}/UserData?userId=...` with only `PlaybackPositionTicks`. It does not
send `Played`, `PlayCount`, `LastPlayedDate` or favorites, and it never promotes an estimate to the
Sessions playback API. Unsupported servers have no legacy full-object fallback: local recovery still
works, and reporting failure does not break playback. Existing manual watch-state overrides suppress
these updates and drain in-flight reporting before a manual mutation. The existing account, provider
session and library-item identity guards apply. This does not add a receiver-side reporter; after the
phone process exits, only checkpoints already saved locally or successfully sent to Jellyfin survive.

The implementation follows Jellyfin's partial user-data semantics in
[ItemsController](https://github.com/jellyfin/jellyfin/blob/master/Jellyfin.Api/Controllers/ItemsController.cs)
and [UserDataManager](https://github.com/jellyfin/jellyfin/blob/master/Emby.Server.Implementations/Library/UserDataManager.cs).
Timeline references are [RFC 8216](https://www.rfc-editor.org/rfc/rfc8216.html), Apple's
[time-to-sample](https://developer.apple.com/documentation/quicktime-file-format/time-to-sample_atom) and
[sample-to-chunk](https://developer.apple.com/documentation/quicktime-file-format/sample-to-chunk_atom)
tables, and [Android SystemClock](https://developer.android.com/reference/android/os/SystemClock).

## Verification

Automated coverage includes monotonic sleep jumps, gaps and prefetch, duplicate delivery, evidence
expiry, playback speed, pause/buffering, lower receiver corrections, seeks and epoch rejection; MP4
sample tables and a generated H.264/AAC file; HLS external audio and exact ranges; real loopback HTTP
GET/HEAD/truncated responses; engine-to-history checkpoints; AtomicFile store reload and malformed
estimate migration; provider report ordering/suppression; and the exact Jellyfin request body with no
legacy fallback. The MP4 fixture's origin and regeneration command are beside the fixture.

Physical Chromecast playback and a live Jellyfin server are separate acceptance checks in
[PHYSICAL_TV_PLAYBACK.md](PHYSICAL_TV_PLAYBACK.md). Automated results cannot establish accuracy on a
particular TV, Android power-management policy, network or Jellyfin server version.

### Local validation, 2026-09-08

Implementation: `329db11`, with estimator/index foundations in `733d5b0` and `5d96097`.

```powershell
.\gradlew.bat testDebugUnitTest :core:test :source:api:test assembleDebug lintDebug checkSourceBoundaries --offline --console=plain
```

The final run succeeded in 3m 39s (74 executed tasks, 222 up-to-date). The combined module JUnit reports
contain 734 tests: **709 passed, 25 opt-in tests skipped, zero failures/errors**, across 115 suites.
The final queue test verifies that delivery-backed recovery can advance with no new TV revision, that
an unrelated display-position change cannot replace the checkpoint, and that lower fresh TV truth wins.

The full log is `build/reports/cast-progress/estimator-acceptance.log`. The debug APK is
`app/build/outputs/apk/debug/app-debug.apk` (96,125,660 bytes), SHA-256:

```text
516b0eeab68467677fed8439f37f4ed5e44f25120abb55670ee9ce4517224554
```

`adb devices` returned no connected devices. Physical screen-off/TV-remote/crash behavior and a live
Jellyfin server were not exercised in this run; request-shape tests use an intercepted HTTP client and
proxy transfer tests use loopback sockets. The checklist remains pending rather than being inferred from
the passing tests.
