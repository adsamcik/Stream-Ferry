# Tracebox diagnostics

All Stream Ferry-owned telemetry remains offline and now uses Tracebox. The Google Cast SDK's
separate vendor-owned collection cannot be rerouted by this integration; its existing disclosure
still applies. No uploader, endpoint, analytics SDK or native Crashpad dependency is added.

| Evidence | Owner and migration |
| --- | --- |
| App, source, playback, proxy, downloads, discovery and service events | Existing `DiagnosticSink` producers feed `TraceboxDiagnosticsLogger`; Tracebox alone owns the durable bounded store and optional Logcat mirror. |
| Detailed Cast/DLNA traffic | Existing opt-in switch plus Tracebox's log-level policy. Redaction runs before recording; long Unicode messages are split without breaking code points. |
| Fatal JVM and handled exceptions | Tracebox handler installed during `attachBaseContext`; handled exceptions retain structural type/frames without raw exception messages. |
| ANRs and Android process exits | Tracebox watchdog and exit reconciliation. Historical exits are not attributed to the current app build. |
| Network/playback snapshot | Refreshed before opening package review, then recorded through the same sink. Target names, media titles and URLs are excluded from this snapshot. |
| Browsing | Bounded Tracebox history queries on IO; event timestamps and build codes are carried in the app's static envelope. There is no second in-memory or disk log store. |
| Save/share | Tracebox's exact-package disclosure and approval, followed by Android save/share. Packages use `.tbdiag` and may include retained historical evidence. No automatic upload. |
| Deletion | Stop the legacy importer, delete Tracebox data, legacy files and old share staging, reset the TV-tracing preference. Recording remains disabled until explicitly enabled. |

## Existing installations

The importer re-redacts legacy event and crash files and keeps the newest 1,500 bounded records,
consistent with finite diagnostic retention. Imported evidence is labelled `legacy` with its source
build and file observation time; it is included in reviewed packages, not presented as a new crash.
Each batch is read back before the old files are retired. If recording is disabled, storage is
unavailable, or a batch is dropped, originals remain for a later launch. An interrupted import can
repeat a partial batch. Oversized or non-local files are not imported. This is a bounded recent-history
handover, not a lossless archive of every historical report.

The event browser filters the running build's explicit event envelope. Crash browsing uses the
package installation time as an earliest observation filter. Tracebox segment observation timestamps
are not exact crash wall-clock timestamps, so imported/recovered evidence can cross that boundary;
the reviewed package retains historical context and should be used for investigation.

Legacy stores remain only as upgrade readers and deletion helpers. The app no longer runs their
writer, uncaught handler, periodic flush loop, separate crash activity or text-export actions.

## Coordinated source dependency

`vendor/tracebox` pins a reviewed Tracebox Git commit. The Gradle composite substitutes the managed
runtime and Compose UI coordinates, including transitive modules. The version in the dependency
declarations is the baseline release coordinate; the submodule commit is the actual source identity.
Initialize submodules before building. CI and release checkouts initialize them recursively.

For local library development use `-PtraceboxSourceDir=/absolute/path/to/Tracebox/src`. Commit and
push Tracebox first, update the submodule pin, then push Stream Ferry. A parent revision referring
to an unpublished library commit cannot be cloned by CI until that library commit is pushed.

To inspect an approved package locally with Tracebox's pinned Rust toolchain:

```text
cargo run --manifest-path vendor/tracebox/Cargo.toml -p tbdiag-cli -- validate report.tbdiag
cargo run --manifest-path vendor/tracebox/Cargo.toml -p tbdiag-cli -- decode report.tbdiag
```

Tracebox's local history extension applies current-policy and deletion fences, limits reads to 2,000
ordinary records, filters event types before bounded selection, and excludes raw crash artifacts
and private storage identities. Reads never approve an export.

## Verification

Run `:app:assembleDebug :app:testDebugUnitTest :ui:testDebugUnitTest :app:lintDebug :ui:lintDebug`
and the root `checkSourceBoundaries` task. Tracebox's runtime, export and ANR/exit unit suites cover
the library changes. App tests exercise tracing gates, redaction before Tracebox, long Unicode
messages, build filtering, history readback, and retention of legacy files on import failure.
Device qualification should cover a deliberate debug crash, relaunch, trace toggle, reviewed
save/share and deletion while legacy import is active.
