# Watched-state synchronization fixes

Follow-up to [the September 5 validation](WATCH_STATE_VALIDATION_2026-09-05.md).

## Changes

1. **Cache freshness:** the item index now takes precedence over older list snapshots. Missing index entries are recovered from records in timestamp order. Offline list reads use the current indexed metadata while retaining list membership and the original freshness timestamp. Regression tests cover cache recreation, missing-index recovery, and late remote responses.
2. **Downloaded completion:** the playback engine includes the original local playback identity in its completion event. An account-owned download creates the same durable watched mutation as online completion, including when offline or when another account is selected. Replay still requires the owning account. Old playback generations and ownerless local files cannot mutate a remote account.
3. **Mark unwatched:** Jellyfin-specific optimistic state now clears resume position and percentage, matching the native endpoint. The existing durable mutation path also clears the matching downloaded and smart-resume checkpoints. The source-neutral watch-state helper retains its existing semantics.
4. **Remote changes and downloads:** successful ordered cache writes update existing downloaded metadata under the download-store mutex. Updates match the original server and user, retain download files, and cannot recreate a removed download. Browsing or refreshing the Downloaded shelf explicitly fetches metadata for its members while signed in, so titles that do not appear in another gallery/search result can also be reconciled. A refresh stops after the first connectivity failure and probes again on the next refresh; cache-only sessions make no network requests.

## Verification

These results cover the watched-state changes through `6361240`. Separate saved-queue work merged into the shared main worktree during final reporting; its additional behavior is outside this validation. The watched-state fixes remain present after that merge.

- All **16 live Jellyfin acceptance checks passed**, with zero failures or skips. This includes all five assertions that failed in the original validation.
- The full unit suite reported **654 tests: 635 passed, 19 skipped, zero failures/errors**. The 19 skips are the 16 opt-in watched-state checks (already exercised in the separate live run) plus three existing optional live-server tests.
- The final debug APK built successfully. `lintDebug` and `checkSourceBoundaries` passed. The final lint run used a fresh single-worker Gradle process after the AndroidX experimental-annotation checker crashed in a reused process while analyzing unchanged UI code.
- The disposable fixture was shut down after the live run.
- Live evidence is retained in the ignored `build/watch-state-fixes-2026-09-06/live-results.xml` and `live-tests.log`. Module test counts are in `unit-results.json`; final app build/test results are in `final-app-validation.log`, and the successful final static checks are in `fresh-lint-validation.log`.

Final static-check command:

```powershell
.\gradlew.bat --offline --no-daemon --no-parallel --max-workers=1 lintDebug checkSourceBoundaries --console=plain
```

## Validation scope

The disposable Jellyfin 10.11.11 fixture contains one synthetic 20-minute video. HTTP authentication, mutation, readback, playback reports, cache, journal, and download stores are real. Renderer completion events and account-state publication are simulated in Robolectric; physical Cast/DLNA and Android process-death behavior were not exercised in this follow-up. Store recreation verifies durable offline intent.

The live suite remains opt-in through `STREAM_FERRY_WATCH_VALIDATION_URL=http://127.0.0.1:18096`; use `--rerun` when toggling that environment variable. It now includes offline downloaded completion, completion across an account switch, stale/ownerless local events, and Downloaded-shelf refresh through watched and partial-progress changes.

This work does not change the separate restriction on manually editing the currently playing item's watch state.
