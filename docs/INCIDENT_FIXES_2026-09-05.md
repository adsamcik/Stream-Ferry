# Incident fixes — 2026-09-05

Scope: the two screen-off shutdowns and all symptoms in the supplied log excerpt.
The [investigation](INCIDENT_INVESTIGATION_2026-09-05.md) and
[Android evidence](evidence/2026-09-05-android-exits.txt) remain the source of the
historical conclusions. The installed app's exact source revision was unavailable.

Android recorded both exits as excessive CPU use by a cached process: 4.36% and
2.80% over five-minute windows, against a 2% cached-process threshold. This proves
the OS termination mechanism. The earlier loss of lasting foreground protection
is established by that cached classification; the precise service teardown was
not retained. The unsafe Stop/Start sequence is a code-level explanation, not a
historically recorded stack trace of the two incidents.

## Implemented

| Issue | Change | Regression coverage |
| --- | --- | --- |
| Foreground service may destroy a newer playback start | Stop revokes the service directly through Android rather than queuing a STOP intent. Legacy STOP delivery uses `stopSelfResult(startId)`. Older instance destruction cannot clear the newer instance's ownership. | Service stop routing and overlapping-instance lifecycle tests; physical-device lifecycle test prepared. |
| Silent CPU-lock failure or later service loss | Foreground confirmation now requires the CPU wake lock. Engine checks ownership every five seconds, restores it without a renderer reload, and stops the relay if restoration is rejected. Creation, commands, ownership and destruction are logged. | Restore without reloading and rejected-restore cleanup tests. |
| Recovery flag remains set after successful fallback | The recovery owner clears the flag in `finally`, including failed or cancelled reloads. | Successful fallback followed by end-of-media and a second genuine failure. |
| Jellyfin segment lookup returns 400 | Removed the invalid comma-separated optional enum filter; supported segment types are mapped locally. Cancellation propagates; older-server 404 remains optional. Failure messages identify the operation. | Request/response mapping and older-server tests; live Jellyfin 10.11.11 comparison recorded in investigation. |
| Stopped relay continues upstream retries | Cancellation propagates; retry delays suspend; original-session validity is checked before opens and during copies. Closed accepted sockets are handled safely. | Real loopback sockets: stop during blocked read, immediate replacement, no old-session callback/retry; valid and invalid range resumes. |
| `clean=true` falsely claims a truncated response completed | Logs distinguish completion, cancellation, upstream failure and downstream closure. Flush failures are counted. Known response lengths are checked for progressive and HLS copies. Upstream failures cannot trigger a codec-rejection callback. | Truncated progressive and HLS bodies, invalid resume, and successful exact-byte resume. |
| Rapid seek ignores newer renderer confirmation | Install the new settle target before sending the remote command; restore the previous guard if the command fails. | Second seek status arrives while command is pending; rejected seek preserves position. |
| Slow source Started reporting delays TV load | Start reporting runs independently with a three-second bound. Progress and Stop wait for its completion to preserve ordering. Local teardown precedes remote reporting. | Blocked Started returns URL promptly; ordered Stop after local revocation; timeout releases Progress. |
| Redundant background notification work | Position/throughput changes keep updating the media timeline but reuse unchanged notification content and metadata. Volume publishes only when changed. | Position updates preserve notification instance; pause and restart refresh it. CPU savings have not been measured on the phone. |
| OS kills absent from app crash reports | Diagnostics include Android exit timestamps, PID, reason, importance, memory and redacted description. Historical records are explicitly labelled as potentially predating the current build. Process starts include PID. | CPU-kill formatting, cached importance, timestamp and secret redaction. |

## Conditions that do not establish another app defect

- Two direct MOV loads aborted early and successfully recovered to server HLS.
  Exact decoder/container rejection details are absent, and other MOV files played.
  The fixed recovery path preserves that fallback without permanently suppressing
  subsequent events. No unsupported blanket MOV restriction was added.
- Cast Play 2100 was followed by successful playback; Stop 2001 followed an already
  finished receiver session. The receiver's detailed cause is absent. Seek and
  recovery ordering are fixed; local cleanup remains effective when remote Stop
  is rejected. These status codes are not phone-process crashes.
- VPN SSDP binding EPERM always recovered through the physical LAN interface.
  The supplied log does not show a discovery outage requiring another change.
- Buffering after starts/seeks and delayed volume confirmation resolved in the
  log. Server/receiver/network timing cannot be isolated further from this excerpt.
  Source reporting no longer adds a serial wait before the TV load.

## Validation

Targeted tests were run during each implementation step. The full validation
command completed successfully in 5m 2s:

```powershell
.\gradlew.bat --offline --no-parallel --max-workers=2 :core:test :source:api:test testDebugUnitTest assembleDebug :app:assembleDebugAndroidTest lintDebug checkSourceBoundaries --console=plain
```

After the final transfer-length checks, playback tests, debug APK assembly,
device-test APK assembly, playback lint and source-boundary checks were rerun
successfully in 1m 7s.

| Module | Tests | Failures/errors | Skipped |
| --- | ---: | ---: | ---: |
| core | 371 | 0 | 0 |
| source:api | 6 | 0 | 0 |
| app | 51 | 0 | 15 |
| playback | 107 | 0 | 0 |
| source:jellyfin | 77 | 0 | 0 |
| source:local | 6 | 0 | 0 |
| ui | 27 | 0 | 0 |
| Total | 645 | 0 | 15 |

That is 630 passing tests and 15 skipped opt-in integration tests. Debug lint
passed with warnings, and source-boundary validation passed. The APK outputs are
`app/build/outputs/apk/debug/app-debug.apk` and
`app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`. Logs are retained
under ignored `build/incident-investigation-2026-09-05/` (`full-validation.log`,
`final-validation.log`, and per-step test logs).

The physical-device test exercises 20 rapid Stop/Start replacements and checks
Android's own foreground-service state after 15 seconds with the screen off.
It skips an already-active playback service. This is a lifecycle regression test,
not a substitute for a long TV playback/CPU soak test.

The device was disconnected when follow-up testing began. No claim is made that
the fixed APK has been tested during physical TV playback or that the original
two kills were reproduced. Unrelated findings in earlier general and watch-state
validation documents were not part of this incident's reproduction evidence.
