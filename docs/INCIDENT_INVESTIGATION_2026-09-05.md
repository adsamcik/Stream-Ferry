# Playback issues and two reported shutdowns

Investigation date: 2026-09-05. Source reviewed: `7e14d1d` on `main`.
Evidence: the supplied 550-line event-log excerpt, 21:32:03.691–22:18:17.127,
user clarification that both failures happened with the screen off, and subsequent
read-only ADB collection from the affected phone. Event line numbers below refer
to the original attachment. Installed build: debug 0.6.0 (6000), Android 16.
Exact installed source revision is unavailable; code attribution uses the reviewed
implementation, whose log messages match.

**Result:** Android killed both processes for excessive CPU usage while classified
as cached/background. Active playback lacked lasting foreground-service protection;
an unguarded service Stop/Start race is the leading code-level suspect. Four further
defects explain playback symptoms: a stuck recovery flag, invalid media-segment
query serialization, retries after proxy shutdown, and a seek-status ordering race.
Two initial direct streams also fail and recover through transcoding.

## The two shutdowns: Android CPU-policy kills confirmed

The phone became available over ADB during the follow-up investigation. Read-only
exit history and system logcat identify **the same termination reason for both
incidents** on Samsung SM-S928B, Android 16, package
`com.adsamcik.streamferry.debug`, version 0.6.0 (6000), last updated 20:41:10.
The user confirms that the screen was off and that they reopened the app seconds
after noticing each shutdown.

| Incident | Android kill time / PID | Recorded reason | CPU measurement | Next app initialization |
| --- | --- | --- | --- | --- |
| First | 21:56:52.600 / 31231 | EXCESSIVE RESOURCE USAGE (9), EXCESSIVE CPU USAGE (7), importance=400 | 13,080ms CPU / 300,108ms elapsed = **4.36%**, limit=2 | 21:58:30.031 |
| Second | 22:16:54.672 / 11993 | EXCESSIVE RESOURCE USAGE (9), EXCESSIVE CPU USAGE (7), importance=400 | 8,390ms CPU / 300,109ms elapsed = **2.80%**, limit=2 | 22:18:15.267 |

Both Android exit records have `trace=null`. System `ActivityManager` independently
logs the same kills, with cached OOM adjustments 910 and 900. These are **OS kills
for CPU consumption while classified as cached**, not Java exception crashes,
foreground-service start timeouts, or low-memory kills. The 2% threshold is the
policy recorded for these incidents, not a universal limit for media playback.
The percentages are CPU time divided by the measured interval, not whole-phone
CPU utilization. They do not demonstrate a runaway 100% CPU loop.

The [retained, app-specific evidence](evidence/2026-09-05-android-exits.txt) contains
the exact records and matching kill lines. Android documents importance 400 as
[cached/expendable](https://developer.android.com/reference/android/app/ActivityManager.RunningAppProcessInfo#IMPORTANCE_CACHED).
The [AOSP excessive-CPU check](https://android.googlesource.com/platform/frameworks/base/+/5f00e89989392c9ae00b360e1388d0179dfb36d7/services/core/java/com/android/server/am/ActivityManagerService.java)
compares interval CPU consumption against the configured percentage and terminates
qualifying processes with this reason/subreason. The device's own records establish
that this policy ran here; no assumption about generic Samsung battery behavior
is required.

### Reconciling the user's timing with the logs

Actual kill-to-initialization gaps are **97.431s** and **80.595s**. The user reopened
seconds after the visible playback shutdown; that does not require the phone
process to have died only seconds earlier. A TV consuming already-buffered media
after losing its phone-hosted source could account for that delay. Buffer depth
was not captured, so this is an explanation consistent with the observations,
not a measured fact. Android's kill timestamps supersede the earlier estimate
that placed process death seconds before each reopening.

Both preceding sessions directly streamed *Deadly Climb*. The last persisted
events (21:41:25.339 and 21:58:47.405) occurred long before the kills. They were not
termination markers. Ordinary progressive streaming can be quiet in the event
log. The saved 1016s resume point was also close to the first actual kill:
progressing from 65s at 21:40:57 would reach about 1020s by 21:56:52, assuming
continuous playback. That supports, but does not prove, buffering after source loss.

### P1 — Active playback lost foreground-service protection

The service-start latch is satisfied in both sessions, yet the process is cached
when killed. A successful one-time `startForeground()` call therefore did not
establish that foreground-service ownership survived for the stream's lifetime.
The immediate defect to address is active streaming/monitoring continuing in a
process Android considers expendable. Reducing CPU alone would leave that
lifecycle failure in place.

**Strong code-level suspect: overlapping service Stop and Start.**

1. Every `PlaybackEngine.play()` first calls `stopInternalLocked()` (509), even
   when there is no prior active stream.
2. Teardown calls `serviceController.stop()` (2599).
3. `ProxyPlaybackService.stop()` sends `startService(ACTION_STOP)` (262–265).
   This queues a service command and returns without waiting for teardown; it can
   even create a service only to ask it to stop.
4. Playback immediately calls `startAndAwaitForegrounded()` for ACTION_START.
5. ACTION_STOP calls `stopSelfSafely()`, which releases locks, calls
   `stopForeground()`, then unconditional `stopSelf()` (66,137–142). It ignores
   the request's `startId`. An older STOP can therefore stop a service for which
   a newer START has already been registered or queued.
6. The latch signals from `enterForeground()` without a lasting service-lifecycle
   check (122–131). The engine then keeps its independent process-owned proxy,
   monitor, reporting and status-publication coroutines alive. `onDestroy()` only
   releases locks; it neither invalidates that confirmation nor notifies the
   engine that protection is gone (145–147).

Android's [start-ID-aware stopping contract](https://developer.android.com/reference/android/app/Service#stopSelfResult(int))
exists specifically to avoid stopping when a newer start request has not yet been
delivered. The unsafe ordering is confirmed in the source. **Its occurrence in
these two historical sessions is not yet directly proven:** the retained logs do
not contain their precise service-destruction/command sequence. Do not present
this candidate race as an already reproduced root cause.

A later session, outside the original attachment, has an OS-confirmed FGS start at
22:18:48.180 and explicit STOP_FOREGROUND at 22:28:27.539. Thus the installed
service can remain registered; the evidence fits intermittent lifecycle loss,
not a manifest that always prevents foreground operation. No such retained OS
lifecycle pair establishes ownership throughout the two failed sessions.

Fix direction: serialize service ownership with the playback generation, avoid
starting a service just to stop it, and prevent stale stop commands from releasing
locks or demoting a newer session. Using start-ID-aware stopping must also guard
`stopForeground()` and lock release; replacing only the final `stopSelf()` is
insufficient. Log service create/start/stop/destroy with IDs and generations,
invalidate stale latch completions, and react explicitly if an active session
loses its service. Verify immediate Stop→Start, initial playback with no existing
service, rapid item replacement and screen-off playback on this phone.

### Screen-off and battery findings

- `MainActivity.onStop()` only flushes diagnostics and checkpoints resume
  (107–113). No screen-off handler was found that intentionally stops playback.
- At capture, both release and debug packages are already on the user's
  device-idle exemption list; debug `RUN_ANY_IN_BACKGROUND` is `allow`. The
  historical setting at the kill instant is not separately logged, but advising
  the user simply to enable unrestricted battery use is not an established fix.
- The service tries to acquire a partial CPU wake lock and a low-latency Wi-Fi
  lock. Both failures are silently swallowed (159–177), and the foreground latch
  is signalled before acquisition. The log cannot establish successful lock
  acquisition or continued ownership.
- The [Wi-Fi lock API](https://developer.android.com/reference/android/net/wifi/WifiManager#WIFI_MODE_FULL_LOW_LATENCY)
  says this mode is active only with the screen on and the app foregrounded.
  The source comment claiming that holding it still guarantees screen-off radio
  association is unsupported. Switching to HIGH_PERF is not a general fix on
  API 34+, where that mode is replaced by LOW_LATENCY. This is a screen-off
  reliability limitation, not the recorded cause of either process kill.
- Generic [Doze restrictions](https://developer.android.com/training/monitoring-device-state/doze-standby)
  can restrict networking and wake locks, subject to exemptions. They do not by
  themselves establish a process death. Here the Android CPU-kill evidence is
  stronger and more specific than a Doze hypothesis.

### Why no corresponding app crash report appeared

Only an old July 25 file is present in private `files/crashes/`; no September 5
Java/Kotlin report exists. The uncaught-exception handler cannot capture an OS
CPU-policy kill. The app also never imports Android `ApplicationExitInfo`, so its
own report omitted the decisive cause. Event snapshots normally flush every ten
seconds, and a kill may discard the latest events. `START_NOT_STICKY` explains
why Android did not automatically recreate the playback service after death.

Read-only device evidence is saved under ignored
`build/incident-investigation-2026-09-05/`: exit history, app-specific system log
matches, service events, and the second/third session's persisted event files.
No battery setting, permission, installed application or playback state was changed
by collection. No new playback or crash was induced. Remaining investigation is
about the exact service-loss mechanism and which background work accounts for
the CPU consumption, rather than the now-confirmed OS termination reason.

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

Prioritize preserving foreground-service ownership throughout active playback and
validating the Stop/Start race. Then address the stuck recovery flag, segment query,
cancelled relay retries and rapid-seek ordering. Add exit-history capture and
process/session/service identifiers so future reports distinguish OS kills from
playback failures. Profile background CPU after lifecycle protection is correct;
the current measurements alone do not isolate a hot loop or prove a memory leak.

This investigation reviewed the entire supplied excerpt and relevant current
source, verified official API/status meanings, executed the live HTTP query
comparison, and retrieved historical termination evidence from the affected phone.
It did not induce either process exit or run a new physical TV playback test.
No production code was changed, and no claim of exhaustive application-wide
correctness follows from this incident review. Previously recorded issues in
[general validation](VALIDATION_2026-09-05.md) and
[watch-state validation](WATCH_STATE_VALIDATION_2026-09-05.md) remain separate
findings; this excerpt neither revalidates nor disproves them.
