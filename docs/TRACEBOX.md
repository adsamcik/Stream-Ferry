# Tracebox diagnostics

All Stream Ferry-owned telemetry remains offline and now uses Tracebox. The Google Cast SDK's
separate vendor-owned collection cannot be rerouted by this integration; its existing disclosure
still applies. No uploader, endpoint, analytics SDK or native Crashpad dependency is added.

| Evidence | Owner and migration |
| --- | --- |
| App, source, playback, proxy, downloads, discovery and service events | Existing `DiagnosticSink` producers feed `TraceboxDiagnosticsLogger`; Tracebox alone owns the durable bounded store and optional Logcat mirror. |
| Detailed Cast/DLNA traffic | Existing opt-in switch plus Tracebox's log-level policy. Redaction runs before recording; long Unicode messages are split without breaking code points. |
| Fatal JVM and handled exceptions | Tracebox handler installed during `attachBaseContext`; bounded cause chains and handled suppressed exceptions retain structural type/frames without raw exception messages. Reduced OOM summaries still trigger crash review. |
| ANRs and Android process exits | Tracebox watchdog and exit reconciliation, including historical PSS/RSS memory observations. Historical exits are not attributed to the current app build. |
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

## Published dependency

Stream Ferry consumes Tracebox `0.1.0-alpha.8` from the official GitHub Packages Maven registry.
The [published release](https://github.com/adsamcik/Tracebox/releases/tag/v0.1.0-alpha.8) includes
the bounded history API, crash-cause and historical exit evidence, and consumer rules needed for
minified apps without native capture. The version catalog pins both the managed runtime and
Compose UI; Gradle resolves their transitive released modules without source substitution.
See [BUILD.md](BUILD.md) for local and CI package credentials.

To inspect an approved package, use `tbdiag-cli` from a separate Tracebox checkout at the matching
release tag. That tooling is optional and is not part of the Stream Ferry build.

Tracebox's local history extension applies current-policy and deletion fences, limits reads to 2,000
ordinary records, filters event types before bounded selection, and excludes raw crash artifacts
and private storage identities. Reads never approve an export.

## Verification

Run `:app:assembleDebug :app:assembleRelease :app:testDebugUnitTest :ui:testDebugUnitTest :app:lintDebug :ui:lintDebug`
and the root `checkSourceBoundaries` task. Tracebox's own release pipeline validates its library
suites. App tests exercise tracing gates, redaction before Tracebox, long Unicode
messages, build filtering, history readback, and retention of legacy files on import failure.
Device qualification should cover a deliberate debug crash, relaunch, trace toggle, reviewed
save/share and deletion while legacy import is active.
