# App validation — 2026-09-05

Reviewed commit: `030b0d0` (Stream Ferry 0.6.0).

The debug build and automated checks pass, but the review found three functional
defects and a CI coverage gap worth addressing. Passing unit tests does not establish
physical TV compatibility. This report changes no application behavior.

## Findings

### 1. P2 — Full item details are discarded before the first watch-state mutation

Evidence: `app/src/main/java/com/adsamcik/streamferry/app/MainViewModel.kt:1100–1119`.

Opening an online Jellyfin item captures its watch-state revision with
`jellyfinWatchStateRevisions[key] ?: 0L`. When the detail request returns, the
supersession check compares the raw nullable map lookup with that captured value.
For an untouched item, this is `null != 0L`, so a valid response is always rejected.
The map is initially empty and is populated only when a watch-state mutation is
materialized; changing accounts clears it again.

The initial gallery request asks for `Overview`, while `itemDetails()` explicitly
asks for `Chapters` (`JellyfinClient.kt:315,325–329`). Consequently, normal gallery
navigation does not acquire the chapter data and chapter artwork intended for the
playback seek preview. Other fields available only in the full response are also
discarded. Existing overview text from the gallery can still display.

Recommended change: normalize the current revision with `?: 0L` too. Add a
ViewModel regression test that opens an untouched item and accepts its returned
chapters, plus cases that reject a response after a mutation or account change.

Validation level: confirmed by the control flow and Kotlin nullable comparison;
not reproduced through a running Android UI in this review.

### 2. P2 — Notification Stop leaves UI-owned recovery running

Evidence: `app/src/main/java/com/adsamcik/streamferry/app/AppContainer.kt:457` and
`app/src/main/java/com/adsamcik/streamferry/app/MainViewModel.kt:3183–3197,3250–3329,3492–3529`.

System media and notification Stop call `playbackEngine.stop()` directly. The
in-app Stop path additionally cancels `reconnectJob`, advances
`reconnectGeneration`, clears `reconnectContext`, and leaves Now Playing. Those
additional actions do not happen for system Stop.

If Stop arrives during the reconnect delay or TV rediscovery, the engine stops,
but the ViewModel still considers itself reconnecting and retains the playback
presentation when engine status becomes null. The delayed recovery then continues.
The engine's stopped-session guard prevents reservation of another automatic
protocol attempt, but the ViewModel still calls `surfaceTerminalPlaybackFailure()`
and returns to a failed Now Playing screen after the user's explicit Stop.

Recommended change: use one process-owned stop/cancellation path for system and
in-app controls, or propagate an explicit stop event that invalidates the UI's
recovery generation. Keep it usable without an Activity. Test notification Stop
during both reconnect delay and rediscovery, asserting no later discovery work,
failure card, or playback session is created.

Validation level: confirmed wiring and control-flow gap; timing scenario still
needs an integration test. This report does not claim that media necessarily
restarts after Stop.

### 3. P2 — A service-only process restart cannot resume downloads

Evidence: `app/src/main/java/com/adsamcik/streamferry/data/download/DownloadService.kt:49–68`,
`app/src/main/java/com/adsamcik/streamferry/app/StreamFerryApplication.kt:30–46`, and
`app/src/main/java/com/adsamcik/streamferry/app/MainViewModel.kt:406–420`.

The download service returns `START_STICKY` specifically to resume persisted
downloads following process death. On a fresh process, however,
`JellyfinAuthRepository.currentUser` starts null. The Application creates the
container but does not restore a session. The service reads that null value and
immediately stops itself. Session restoration is initiated by the ViewModel, which
does not exist in a background service-only restart. The connectivity callback
also requires an already restored current user.

Thus a download can remain paused until the user opens the app, even when the
server and network are available. Its persisted queue and partial bytes survive.

Recommended change: move bounded, identity-verified active-session restoration to
a process-owned component that the service can await after entering the foreground.
Preserve the existing account boundary: never install an arbitrary queued owner's
credentials. Test process recreation with no Activity, an active queued owner, and
both reachable and unavailable servers. A user force-stop is a separate scenario
and should not be expected to restart the service automatically.

Validation level: confirmed startup dependency gap; actual OS process-recreation
behavior was not exercised on a device.

### 4. P2 — CI omits the JVM suites and source-boundary check

Evidence: `.github/workflows/ci.yml:49–62`, `core/build.gradle.kts:1–3`,
`source/api/build.gradle.kts:1–4`, and `build.gradle.kts:116–119`.

CI invokes `assembleDebug`, `testDebugUnitTest`, and `lintDebug`. The `core` and
`source:api` modules are JVM libraries and expose `test`, not
`testDebugUnitTest`. Neither assembling their dependent Android modules nor running
those modules' tests executes the JVM suites. `checkSourceBoundaries` is attached
only to `check`, which CI also does not request.

The omitted JVM suites contain 377 tests in this checkout, including proxy-session,
HTTP range, trusted-origin, redaction, recovery-policy, and source-identity checks.
The existing report upload also collects only `app/build/reports`, losing the other
modules' test and standalone lint reports.

Recommended change: add `:core:test :source:api:test checkSourceBoundaries` to CI,
or introduce one explicit aggregate validation task; collect reports from every
module. Verify the task graph and demonstrate that a failing core test and a
source-boundary violation both fail the gate.

## Acceptance gaps

- The live Jellyfin integration suite skips when no server is reachable. This
  review's three live tests were skipped. Add a dedicated integration job with a
  provisioned, pinned server and sample media; that job should fail if its fixture
  is unavailable rather than silently skip.
- Only two instrumented Compose test files exist, covering library search input
  and the Quick Connect code card. There are no end-to-end UI checks for the
  detail/recovery/service scenarios above. No Android device was connected during
  this review; available emulator definitions were not launched.
- The physical compatibility matrix contains a plan, not executed results. Run
  the documented first-frame → 30 seconds → seek → pause/resume → stop gate on
  Cast and DLNA hardware, including screen-off playback, VPN/Wi-Fi changes,
  restart recovery, and local transcoding. This remains necessary before claiming
  reliable device compatibility.
- Dependency verification metadata remains absent. The documented core-only
  fallback build commands still reference pre-module-split source paths, so that
  fallback procedure also needs updating.

## Automated validation

Commands were run offline using the installed SDK and existing dependency cache:

```powershell
.\gradlew.bat --offline :core:test :source:api:test testDebugUnitTest assembleDebug lintDebug checkSourceBoundaries --console=plain
.\gradlew.bat --offline --no-parallel --max-workers=2 :core:test --rerun :source:api:test --rerun testDebugUnitTest --rerun --console=plain
.\gradlew.bat --offline assembleDebug testDebugUnitTest lintDebug --dry-run --console=plain
```

The first command completed successfully in 6m 27s: 294 actionable tasks,
101 executed, 2 from cache, and 191 up-to-date. The explicit second pass forces
fresh unit-test execution rather than relying on previous up-to-date test results.
It completed successfully in 2m 7s; all seven test tasks executed and their XML
reports have fresh timestamps. The dry run completed successfully in 1m 5s and
confirmed that the CI graph contains the five Android module test tasks but
neither JVM test task nor `checkSourceBoundaries`.

Local execution logs are retained under `build/validation-2026-09-05/` (ignored
build artifacts); the original module test and lint reports remain under each
module's `build/` directory.

| Module | Tests | Failures / errors | Skipped |
| --- | ---: | ---: | ---: |
| core | 371 | 0 | 0 |
| source:api | 6 | 0 | 0 |
| app | 38 | 0 | 3 |
| playback | 91 | 0 | 0 |
| source:jellyfin | 75 | 0 | 0 |
| source:local | 6 | 0 | 0 |
| ui | 27 | 0 | 0 |
| **Total** | **614** | **0** | **3** |

Lint completed without errors but with warnings: app report 60, playback 24,
source:jellyfin 1, source:local 0, and ui 3. These are per-report counts and can
overlap because the app report includes dependency findings; they are not 88
distinct defects. Warnings include package visibility, storage allocation, and
style suggestions. Individual warnings need triage rather than blanket suppression.

Scope: source review, debug assembly, JVM/Android unit tests, debug lint, and
architecture validation. No physical playback, live Jellyfin, Android UI execution,
or release/R8 runtime validation is claimed. No deployment was performed.
