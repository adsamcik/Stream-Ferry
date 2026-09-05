# Watched-state and remote synchronization validation

Date: 2026-09-05. Application: Stream Ferry 0.6.0.

**Result: not ready to sign off as fully synchronized.** Manual mark watched,
explicit progress reset, online automatic completion, and failed-write replay pass.
Four defects remain in offline/download synchronization and cached state freshness.
No application behavior was changed by this validation.

## Executed validation

An isolated **Jellyfin 10.11.11** server was started on `127.0.0.1:18096`, using the
[official Windows portable archive](https://ams1.mirror.jellyfin.org/files/server/windows/latest-stable/amd64/jellyfin_10.11.11-amd64.zip).
It had a disposable account and one synthetic 20-minute video; no personal library
or credentials were used. The server was stopped after validation. Its files and
execution logs remain in the ignored `build/watch-state-validation/` directory.

The new [LiveWatchStateValidationTest](../app/src/test/java/com/adsamcik/streamferry/integration/LiveWatchStateValidationTest.kt)
executes the actual MainViewModel mutation/reconciliation code, Jellyfin HTTP
client and user-state provider, metadata cache, persisted mutation journal,
download metadata, and local resume store. Server truth is checked through a
separate item-detail read. This is more than checking request URLs or mock responses.

The Android environment is Robolectric API 34. Renderer completion events and
account-state publication are simulated; the AppContainer and auth-operation
gate are mocked to isolate these flows. Login, remote mutation, remote readback,
and playback lifecycle report HTTP requests use the real test server. Cache file
contents are real, with the existing direct-writer test seam used for cache writes.
The mutation journal uses its normal AtomicFile persistence.

| Acceptance scenario | Result |
| --- | --- |
| Manual watched: remote, visible item, cache, downloaded copy, resume cleanup | PASS |
| Reset progress: remote unwatched/zero progress plus local cleanup | PASS |
| Online renderer completion automatically marks the exact item watched | PASS |
| Stale online completion generation is ignored | PASS |
| Partial playback report retains resume and does not mark watched | PASS, combined with next row |
| Near-end playback reports cause Jellyfin to mark watched and clear resume | PASS |
| Failed write preserves the intent across journal recreation, then syncs on refresh | PASS |
| Pending write waits until its owning account is published again | PASS |
| Remote watched change survives a subsequent offline-cache lookup | FAIL |
| Newer cache state wins independently of snapshot filename order | FAIL |
| Mark unwatched keeps downloaded progress consistent with Jellyfin | FAIL |
| Download completion sends or queues remote watched state | FAIL |
| Refreshing a remote watched change also updates the downloaded copy | FAIL |

The partial/near-end cases are one test: **12 tests, 7 passed, 5 failed, 0 skipped**.
The four original failures reproduced in the second run; the additional cache
test isolates one of them without depending on the live server's response timing.
These are intentionally strict acceptance assertions, not passing tests that
encode the defective behavior. They remain opt-in until their fixture is provided.

Existing suites were freshly executed as well: `source:api` **6**, `source:jellyfin`
**75**, and `playback` **91** — **172 passed, none failed or skipped**. These cover
native API routes, source ownership, payloads, cache overlays, token restoration,
playback reporting, and completion policies, but did not catch the integration gaps.

## Defects to address

### 1. P1 — Old cache snapshots can reverse freshly synchronized watched state

Location: `source/jellyfin/src/main/java/com/adsamcik/streamferry/data/cache/LibraryCache.kt:343–372`.

After changing the item to watched on Jellyfin, a search correctly shows it watched.
A subsequent `cache.item()` nevertheless returns the old unwatched version.
`indexForLookup()` rebuilds from every cached list, then merges those records over
the current index. `rebuildIndex()` sorts the files by their hashed filenames and
uses the last matching item, ignoring which response is newer.

The deterministic reproduction writes the same item unwatched under key `a`, then
watched under key `b`. An item lookup returns unwatched because the older `a` hash
sorts later. Thus even an otherwise successful refresh can revert during offline
lookup/search. This is not a failed network write.

Fix direction: retain authoritative per-item freshness/order across cache records
and the index. Rebuilding a missing/legacy index must not allow older list records
to overwrite a newer indexed item. Validate both offline lookup and every cached
gallery representation after a newer remote response.

### 2. P2 — Mark unwatched leaves a downloaded copy at stale progress

Locations: `MainViewModel.kt:2772–2777,2879–2911`,
`source/api/src/main/kotlin/com/adsamcik/streamferry/domain/WatchState.kt:23–31`, and
`JellyfinClient.kt:369–385`.

With a watched title resumed at **300 seconds**, the app calls Jellyfin's native
mark-unplayed endpoint. Live server readback returns unwatched with no resume point.
The app's downloaded metadata still contains **300 seconds**. Its local transform
deliberately preserves resume for MARK_UNPLAYED, and that mutation also bypasses
the local resume-clearing path. The authoritative item reconciliation updates UI
state but does not repair the persisted download snapshot.

Fix direction: define one consistent contract for mark unwatched. Either follow
the native endpoint's reset behavior everywhere, or use a remote operation that
actually preserves progress if that is the intended product behavior. Reconcile
the downloaded snapshot and account-owned local checkpoints with server readback.
The separate explicit Reset progress action passed its current cleanup tests.

### 3. P2 — Completing a downloaded Jellyfin video never queues remote watched state

Location: `app/src/main/java/com/adsamcik/streamferry/app/MainViewModel.kt:1685–1727`.

A genuine completion of an online source session calls the durable MARK_PLAYED
path and passes the live test. A downloaded video is classified as a local session:
completion only clears local resume, then returns without creating a watched-state
mutation. In the acceptance test the video remains unwatched on Jellyfin and the
pending journal is empty, even while the owning account and server are available.

Fix direction: retain the immutable Jellyfin account/item identity on a downloaded
playback completion and journal its watched intent for that owner. Sync immediately
when verified online, otherwise on later restoration. Arbitrary local files must
continue to avoid remote watch mutations; stale completions and other accounts
must remain fenced out.

### 4. P2 — Server-originated watched changes do not update downloaded metadata

Locations: `MainViewModel.kt:2746–2765,2879–2893` and
`source/jellyfin/src/main/java/com/adsamcik/streamferry/data/cache/CachingMediaLibraryRepository.kt:57–85`.

Marking a downloaded title watched remotely and refreshing search updates the
visible online result, but `DownloadStore` still says unwatched. Only the app's
own mutation materialization updates its persisted download snapshot. Normal
authoritative remote reads do not do so. Fixing the cache ordering defect alone
will therefore leave the offline downloaded copy inconsistent.

Fix direction: reconcile account-scoped downloaded metadata from successful
authoritative remote item data too, while preserving newer unsynced local intents
and rejecting stale or foreign-account responses.

## Reproduction and evidence

The fixture must be disposable and loopback-only, with user `validation`, password
`watch-validation-only`, library `WatchValidation`, and movie `Validation`. Generate
a 20-minute video rather than a very short clip: Jellyfin treats short-media resume
differently. On Windows, submit a canonical backslash filesystem path when creating
the library. Wait until the item is indexed before running tests.

```powershell
$env:STREAM_FERRY_WATCH_VALIDATION_URL = 'http://127.0.0.1:18096'
.\gradlew.bat --offline --no-parallel --max-workers=2 :app:testDebugUnitTest --tests '*LiveWatchStateValidationTest' --rerun --console=plain
```

Use `--rerun`: the opt-in environment variable is read by the test and is not a
declared Gradle task input. A missing opt-in skips safely; once enabled, missing
server/media and acceptance failures fail the run instead of silently skipping.

Saved evidence under `build/watch-state-validation/`:

- `live-results.xml`, `results.json`, `app-tests.log`: final live results.
- `first-live-results.xml`: original 11-scenario run before the isolated cache case.
- `supporting-tests.log`: fresh supporting-suite execution.
- `opt-out-tests.log`: verification of default opt-out behavior.

No physical Cast/DLNA end-event delivery, fully killed Android process restoration,
or arbitrary Jellyfin version/configuration is certified here. Manual actions are
currently disabled when the UI already knows the server is unavailable; the replay
test covers an action whose remote write fails after it begins. Incoming server
changes were explicitly refreshed, not pushed in real time.
