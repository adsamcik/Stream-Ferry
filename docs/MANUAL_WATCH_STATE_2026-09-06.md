# Manual watched-state precedence

Manual watched-state edits are available during playback, including from the current and upcoming queue rows. There is no requirement to stop the video first.

## Behavior

| Action | Effect on automatic reporting |
| --- | --- |
| Edit the currently playing video from its details or current queue row | The manual value wins and automatic watched/progress updates stop for this playback session. |
| Edit an upcoming queue entry | The edit synchronizes normally; current playback and that entry's future playback retain automatic reporting. |
| Edit an upcoming duplicate of the current title | Queue entry identity keeps the edit from disabling the currently playing copy's reporting. |
| Pause, reload, reconnect, or retry the current playback | Its manual override remains in force. |
| Start a fresh playback, including the next queue entry or a new playback of the same title | Automatic reporting is enabled again. |

Mark watched, mark unwatched, and the existing episode reset-progress action share this policy. Further manual edits remain possible after an override. Playback and its displayed playhead continue normally, and completion still advances the queue or autoplay.

The override suppresses automatic Jellyfin Started/Progress/Stopped reporting, completion-based watched mutations, SmartResume tracking, and local/download/queue resume checkpoints. This prevents a later progress update or stop event from undoing a manual reset. Already-running lifecycle reports finish before the absolute manual server mutation. Source-session and proxy cleanup still run even when reporting is suppressed.

The override belongs to the original server, user, item, and playback session. It does not follow the title into subsequent playback. Buffered completion events retain their original session's policy, so changing sessions cannot reactivate an old completion mutation. An upcoming duplicate still shares the same Jellyfin item metadata; its current playback can subsequently update that metadata automatically because the queued edit does not override current playback.

## Synchronization and queue metadata

Manual operations for the same account and item are serialized, preserving the latest accepted edit. Journal replay checks that its pending operation is still current. Watched-state changes update matching current, upcoming, and saved queue snapshots without altering another account's entries.

An offline cached account can accept and durably queue manual edits, including during downloaded playback. Replay requires reconnection to the owning authenticated account. Queue actions validate ownership before accepting an edit. Ownerless local files have no Jellyfin watched state to edit.

## Validation

Validated through implementation commit `773d69e` (session policy introduced in `e1f8265`).

- **22 live Jellyfin acceptance tests passed**, with zero failures, errors, or skips. The original 16 synchronization regressions remain covered. Six added checks exercise current-video manual watched/unwatched precedence, current queue edits, upcoming duplicate isolation, overlapping manual edits, and offline manual edits overriding downloaded completion before replay.
- **193 unit tests passed** across app, playback, and UI, with zero failures/errors. App: 47 passed and 25 optional live checks skipped; playback: 119 passed; UI: 27 passed. The 22 watched-state live checks were exercised separately; the other three optional live tests were not enabled.
- Playback tests cover suppression before Started, draining an in-flight Progress request, suppression of later Progress/Stopped requests, source/proxy cleanup, retry continuity, fresh-session reset, and buffered completion isolation.
- The debug APK, `lintDebug`, and `checkSourceBoundaries` passed.
- The disposable Jellyfin fixture was shut down after verification.

The live fixture uses Jellyfin 10.11.11 on loopback with one synthetic 20-minute video. Watched-state HTTP calls, playback reports, cache, mutation journal, and downloaded metadata stores are real. Renderer events and account-state publication are simulated; no physical Cast/DLNA device was exercised.

Evidence is retained in the ignored `build/manual-watch-validation-2026-09-06/` directory: `live-results.xml`, `live.log`, and `validation.log`.

Final check command, with the live-suite environment variable removed:

```powershell
.\gradlew.bat --offline --no-daemon --no-parallel --max-workers=1 :app:testDebugUnitTest :playback:testDebugUnitTest :ui:testDebugUnitTest :app:assembleDebug lintDebug checkSourceBoundaries --console=plain
```

The live suite uses `STREAM_FERRY_WATCH_VALIDATION_URL=http://127.0.0.1:18096` and `:app:testDebugUnitTest --tests '*LiveWatchStateValidationTest'`. Force the test task to rerun when toggling this environment variable if Gradle would otherwise reuse its previous results.
