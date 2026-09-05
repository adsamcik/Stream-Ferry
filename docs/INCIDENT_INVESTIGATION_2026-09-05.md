# Playback issues and two reported shutdowns

Investigation date: 2026-09-05. Source reviewed: `7e14d1d` on `main`.
Evidence: the supplied 550-line event-log excerpt, 21:32:03.691–22:18:17.127.
Line numbers below refer to that attachment. It has no build/device header, crash
report, stack trace, process ID, or Android exit record. Code attribution assumes
the installed build contains the reviewed implementation; its log messages match.

**Result:** four actionable implementation defects explain several playback
symptoms: a stuck recovery flag, invalid media-segment query serialization, retries
after proxy shutdown, and a seek-status ordering race. Two initial direct streams
also fail and recover through transcoding. Neither reported app shutdown has a
provable termination cause in the supplied evidence.

## The two shutdowns

| Incident | Last persisted event before reinitialization | Next initialization | What can be established |
| --- | --- | --- | --- |
| First | 21:41:25.339: TV volume reaches 15% (line 490) | 21:58:30.031: Cast SDK initialized; auth and library restore follow (491–494) | The user confirms reopening seconds after the shutdown, placing the exit shortly before 21:58:30. The 17m 4.692s logging gap is not app downtime. The previous session was directly playing *Deadly Climb*. |
| Second | 21:58:47.405: buffering ends at the resumed position of 1016s (546) | 22:18:15.267: Cast SDK initialized; auth and library restore follow (547–550) | The user confirms reopening seconds after the shutdown, placing the exit shortly before 22:18:15. The 19m 27.862s logging gap is not app downtime. Direct playback had resumed successfully. |

`AppContainer.initializeCastContext()` caches its successful initialization and
does not repeat it on ordinary Activity recreation (AppContainer.kt:338–357).
Together with authentication restoration, these markers strongly support process
restarts rather than two playback-screen errors. They identify reopen times,
not the instants the process died. Quiet progressive streaming need not generate
new events for many minutes. The saved 1016s resume point also shows that the last
printed event is not necessarily the last successful playback activity.

**User clarification:** both reopenings occurred seconds after the respective
crashes/shutdowns. This narrows the exit windows to shortly before 21:58:30 and
22:18:15. Measured from each session's first confirmed PLAYING state, those are
roughly 18 and 19.5 minutes into the respective playback sessions, allowing for
the short reopening delay. These are elapsed session times, not proof of
uninterrupted playback. The failures therefore occurred well after startup; the
21:40:15 handled Cast error was about 18 minutes before the first exit.

The tighter timing warrants checking sustained-playback failures, but does not
prove a fixed timer, memory leak or OS background restriction. The app's proxy
session registry uses a 4-hour idle expiry and 24-hour hard ceiling, with no
production override at construction or session creation. The playback service
acquires its wake lock without a timed expiry. Those particular application
timeouts do not explain an exit after approximately 18–20 minutes. An Android
exit record or saved crash stack remains necessary to identify the actual cause.

There is **no fatal exception, ANR, out-of-memory message, native crash, or OS kill
reason** in the attachment. The earlier `IllegalStateException` at 21:40:15 is a
handled TV command failure: playback resumes at 21:40:18 and the app continues
through several later operations. `APP_ERROR` there is a Cast receiver error,
not an Android process-crash diagnosis.

The historically troublesome foreground-service startup deadline is **not
supported as the cause here**. All seven recorded starts use `startService`,
successfully enter the foreground, and take 3–21ms. The last starts before the
two reinitializations take 21ms and 3ms. This does not establish that the service
remained alive throughout each gap, but none of these starts missed promotion.

The recovery-flag defect below is serious, but it does not explain these two
exits: both immediately preceding playback sessions use direct streaming, and
the previous fallback flag is reset when starting a new item.

### Why the current diagnostics cannot distinguish causes

- `CrashReporter.kt:38–73` records uncaught Java/Kotlin exceptions in private
  `files/crashes/` storage, separately from the event log. Its debug crash screen
  can deliberately terminate the dying main process after saving a report. That
  kill is a consequence of an uncaught exception, not its original cause.
- The supplied text contains only event lines. It does not establish whether
  separate crash reports exist. Current exports include only reports whose version
  code matches the installed build (`MainViewModel.kt:3831–3840`), so an intervening
  version-code change can hide older reports without deleting them.
- Event snapshots normally flush every 10 seconds and asynchronously on Activity
  stop. Abrupt termination can lose the most recent events; this is not a precise
  crash-time recorder (`AppContainer.kt:505–519,616`).
- No implementation reads `ActivityManager.getHistoricalProcessExitReasons()`.
  Android's [ApplicationExitInfo](https://developer.android.com/reference/android/app/ApplicationExitInfo)
  distinguishes Java/native crashes, ANRs, memory pressure and other exits. On
  some devices memory-pressure kills appear as a signal rather than LOW_MEMORY;
  the reason, status, description and available trace must be considered together.
- `ProxyPlaybackService` returns `START_NOT_STICKY` (line 82), so process death
  does not automatically recreate this playback service. That explains why an
  actual kill can leave casting stopped; it does not explain what killed it.

**Evidence still required:** the complete diagnostics export including its
build/device header and crash section, plus **Diagnostics → Share crash logs**.
If no crash was recorded, obtain Android exit history for the installed package
(`com.adsamcik.streamferry`, or `.debug`) and available crash/ANR logs. Read-only
ADB collection, once the phone is connected:

```powershell
adb shell dumpsys activity exit-info com.adsamcik.streamferry
adb shell dumpsys activity exit-info com.adsamcik.streamferry.debug
adb logcat -b crash -d -v threadtime
```

Use the matching package and correlate exit timestamps with the two reopenings.
At investigation time, `adb devices -l` returned **no devices**, so those records
could not be collected. The app's own stored crash report is preferable when
available, particularly for debug builds that launch their separate crash screen.

## Confirmed implementation defects

### 1. P1 — Successful recovery leaves the engine permanently in reload mode

Evidence: lines 342–372 show fallback followed by confirmed PLAYING at 21:40:08.657.
At 21:40:15.835, a subsequent error still sees `reloading=true` and is ignored
(400–404). Both intervening seeks explicitly use renderer seeking, so they are
not server-side reloads.

Cause: `PlaybackEngine.handlePlaybackFailure()` sets `isReloadingStream=true`
before scheduling recovery (1926–1934). `launchRecoveryReload()` invokes
`reloadStream()` but never releases that flag (1784–1813). `reloadStream()` saves
the already-true value and restores it in `finally` (1754–1773). Successful
PLAYING callbacks do not clear it. The flag remains true until another path,
such as full session teardown, resets it.

Impact: later recovery errors are suppressed; the startup watchdog also skips
its failure handling (1998), and real end-of-media events can be discarded (2337).
At 21:32:32 a FINISHED event is ignored, followed by manual Stop over three
minutes later (6–12). That earlier event is consistent with the same defect,
although the reload that preceded it is outside the supplied excerpt. Automatic
completion/watch-state updates and queue advancement can consequently be missed.

Fix direction: make the recovery operation explicitly own and release its reload
guard, fenced to its generation and released on success/failure/cancellation.
Test an actual fallback → PLAYING → Ended sequence and a second failure after
fallback, including silent failure of the replacement stream. The existing
`PlaybackRecoveryTest` tests the decision function, not this flag lifecycle.

### 2. P2 — Every initial segment lookup sends an invalid array query

Seven HTTP 400 responses occur just after initial playback starts: 21:37:57,
21:38:52, 21:39:17, 21:39:40, 21:40:02, 21:40:38 and 21:58:42
(58,176,228,278,337,456,527).

`JellyfinClient.mediaSegments()` passes one value:
`includeSegmentTypes=Intro,Outro,Recap,Preview,Commercial` (354–361).
Jellyfin's [10.11.11 endpoint](https://raw.githubusercontent.com/jellyfin/jellyfin/v10.11.11/Jellyfin.Api/Controllers/MediaSegmentsController.cs)
binds an enum collection from the query. A live check against the repository's
disposable, loopback-only Jellyfin 10.11.11 fixture reproduced:

| Request | HTTP result |
| --- | --- |
| Current comma-separated value | **400**: `errors.includeSegmentTypes` says the combined value is invalid |
| Repeated `includeSegmentTypes` parameters, one per type | **200** |
| No optional type filter | **200** |

The client catches the failure and returns an empty list. Intro/outro skipping
therefore gets no segment data. The error's timing matches the asynchronous
segment fetch at `PlaybackEngine.kt:567–578`; playback-start reporting happens
before the logged session start, and its failures have a separate reporter label.
This makes segment lookup a strongly supported attribution for the seven log
warnings; their generic log lines alone do not name the endpoint. **These warnings
are not evidence that Jellyfin watch-progress synchronization failed.**

Fix direction: omit the optional filter or send repeated query parameters, preserve
cancellation, and log a safe operation name with failures. The live check used
synthetic media only; the fixture was stopped afterward. Results are retained in
ignored `build/incident-investigation-2026-09-05/media-segment-http-results.json`.

### 3. P2 — A stopped proxy transfer keeps retrying after cancellation

The old *Powerless Plunge* transfer is stopped at 21:39:54.289. It continues
retrying offset 29,369,370 through 21:40:08.989, while the next item has already
loaded and fallen back to HLS. It finally gives up at 21:40:08.990
(305–321,374–376): **14.701 seconds after Stop**.

Cause: `LocalProxyServer.stop()` closes responses/sockets and cancels its scope
(161–189), but `streamResilient()` catches generic `Exception`, including coroutine
cancellation, as a retryable upstream failure (887–915). Its loop does not check
coroutine cancellation or session revocation. `retryOrGiveUp()` uses blocking
`Thread.sleep()` (924–937). Consequently, cancelled/revoked work can consume the
retry budget instead of stopping promptly.

This is evidence of stale retry work, not proof that the new HLS source suffered
an outage. Revoked responses are rejected by `trackResponse()` (571–581). Fix by
propagating cancellation, using cancellable delay, and checking the original
session before every retry/open. Verify Stop during a blocked read/backoff and
immediate replacement with another session.

The accompanying `clean=true` is misleading: it means only that no counted
downstream write threw (`LocalProxyServer.kt:266,840–856`). It does not establish
that the requested byte range completed. An explicit completion/cancellation/
upstream-failure result would make this diagnostic reliable.

### 4. P2 — Rapid seeks reject the newer, correct renderer position as stale

At 21:38:09 the app requests 60s but holds the old 30s target and ignores the TV's
60s report (108–111). The same sequence repeats at 21:40:14: requested 63s,
holding 33s, ignoring the correct 63s report (394–397).

Cause: `seekToLocked()` awaits the remote seek before installing the new settle
target (1078–1080). A renderer status arriving during that await is judged against
the previous target; `onTargetEvent()` returns early for the entire status
(2279–2288). Thus the status confirmation and playing/paused revision can be lost.
Later callbacks do show the correct positions, so permanent seek failure is not
demonstrated.

Fix direction: track the pending seek/generation before dispatch and reconcile or
restore state if the command fails. Preserve the current rule that a rejected
command must not appear successful. Test back-to-back seeks where the second
status arrives before the second command completion.

## Other observed failures, delays and recovered conditions

| Observation | Assessment |
| --- | --- |
| *Firebomber Down*: direct MOV aborted after ~1.63 MiB; *Second Thoughts*: after ~1.39 MiB | Real initial playback failures. The app infers FORMAT from an early abort without a detailed decoder error, then recovers to server HLS at 21:38:05 and 21:40:08. Container/codec incompatibility is plausible, but neither file corruption nor a specific decoder limitation is proven. Other MOV files play directly on the same TV, so a blanket MOV blacklist is unsupported. |
| Play fails with 2100 / APP_ERROR at 21:40:15 | Receiver command failure immediately after two paused seeks. The command error is surfaced, while automatic recovery is incorrectly suppressed by defect 1. The TV reaches PLAYING at 63s by 21:40:18.779. It is not a phone crash. |
| Stop fails with 2001 at 21:35:52 | Stop is rejected after the earlier FINISHED/IDLE state; local proxy cleanup still completes. Google defines 2001 as INVALID_REQUEST and 2100 as FAILED in its [Cast status reference](https://developers.google.com/android/reference/com/google/android/gms/cast/CastStatusCodes). The precise receiver-side reason is absent. |
| Socket closed warnings at 21:37:59 and 21:40:03 | Both occur immediately after intentional fallback teardown closes sockets. They are caught request-handler errors, not uncaught process exceptions. Expected shutdown should be logged less severely. |
| Repeated startup/seek buffering | Playback resumes in the shown sequences. Examples: 4.52s buffering after the 60s HLS seek, ~4.20s across the two direct seeks at 21:40:53–57, and 3.05s after resumed streaming at 21:58:44–47. No evidence isolates network bandwidth, transcoder latency or receiver decoding as the sole cause. |
| Slow first launch | First selected Cast connection takes ~6.9s; proxy-bound → session-start adds ~7.4s. The latter includes awaited source Started reporting, but no per-call timings isolate the delay. Together with fallback, connect → confirmed playback takes ~22.3s. Later launches are faster. |
| SSDP Network binding rejected with EPERM seven times while VPN is active | Every occurrence successfully falls back to the physical LAN interface; the TV description returns HTTP 200. This is a recovered binding restriction, not evidence that discovery or the VPN caused either exit. |
| `clean=false` on ordinary stream fetches | Many align with seek/range changes or intentional Stop. For example, the MKV tail probe and subsequent range fetch lead to successful playback. An individual disconnect is not sufficient evidence of a crash or unsupported format. |
| Volume initially reads 0%, then reports 10%; requested 15% briefly still reads 10% | Asynchronous volume reconciliation. The requested 15% is reported 110ms after the reconciliation trace. No lasting volume failure is shown. |
| Repeated “Proxy stopped” and “starting new playback” | Engine and coordinator both perform idempotent local teardown. These are stream/session stops, not Android application shutdown markers. |

## Scope and next priorities

Prioritize the stuck recovery flag, then the segment query, cancelled relay retries,
and rapid-seek ordering. Collect stored crash reports/Android exit history before
assigning a cause to either process exit. Add exit-history capture, process/session
identifiers and service lifecycle breadcrumbs to future diagnostics so ordinary
crashes and system kills can be distinguished from playback failures.

This investigation reviewed the entire supplied excerpt and relevant current
source, verified official API/status meanings, and executed the live HTTP query
comparison. It did not reproduce either process exit or run physical TV playback.
No production code was changed, and no claim of exhaustive application-wide
correctness follows from this incident review. Previously recorded issues in
[general validation](VALIDATION_2026-09-05.md) and
[watch-state validation](WATCH_STATE_VALIDATION_2026-09-05.md) remain separate
findings; this excerpt neither revalidates nor disproves them.
