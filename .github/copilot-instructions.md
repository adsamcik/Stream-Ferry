# Copilot instructions — Stream Ferry

These are repository-wide instructions for GitHub Copilot (chat, code review, and the cloud
coding agent). Read this before making changes. Keep changes small, surgical, and consistent
with the existing code and docs.

## What this project is

Stream Ferry is an Android app that streams Jellyfin video to a TV **through the phone**. The
phone is the sole media gateway; **the TV never accesses Jellyfin directly**. The TV only ever
receives an ephemeral, high-entropy, phone-hosted URL
(`http://PHONE_LAN_IP:EPHEMERAL_PORT/session/<256-bit-id>/stream`). It must never receive the
Jellyfin base URL, access token, `Authorization` header, or any playlist/segment/subtitle/poster
URL. **This security invariant is the whole point of the project — never weaken it.**

- Kotlin · Jetpack Compose · Material 3 · ViewModel/coroutines/Flow.
- `minSdk 34` (Android 14) · `targetSdk`/`compileSdk 37` (Android 17).
- Gradle wrapper pins **9.5.1**; AGP **9.2.1**; Kotlin **2.4.0**; JDK **17**.
- Two playback modes: **Proxy Cast** (preferred) and **Proxy DLNA**. No direct Jellyfin→TV path.

## Repository layout

- `app/src/main/java/com/adsamcik/streamferry/core/` — **pure-JVM, framework-free security/correctness
  core** (http/range, session, hls, buffer, redaction, stream selection, dlna xml). This is the
  part that can be compiled and tested without the Android toolchain.
- `app/src/main/java/com/adsamcik/streamferry/{data,playback,ui,domain,diagnostics,permissions,logging}/`
  — the Android app (Compose UI, Cast, DLNA, Jellyfin HTTP, foreground proxy service).
- `app/src/test/java/com/adsamcik/streamferry/core/` — JUnit 4 unit tests for the core.
- `docs/` — design and policy docs. **When you change behavior, update the relevant doc.** Start
  with `docs/BUILD.md`, `docs/ARCHITECTURE.md`, and `docs/THREAT_MODEL.md`.
- `gradle/libs.versions.toml` — the single source of truth for dependency versions (version
  catalog). Change versions here, not inline in build scripts.

## Build, test, and lint

On a host with Google Maven access (`google()` / `dl.google.com` reachable):

```bash
./gradlew assembleDebug        # build the debug APK
./gradlew testDebugUnitTest    # run JVM unit tests
./gradlew lintDebug            # Android lint
```

Run the relevant subset for your change; run `testDebugUnitTest` and `lintDebug` before opening a
PR whenever you touch buildable code. Documentation-only changes do not need to be built.

## Sandbox / firewall limitation (read this before reporting a "broken build")

By **default** the Copilot cloud-agent firewall **blocks `dl.google.com` / `maven.google.com`**
(while `mavenCentral()` and the Gradle services are reachable). With that default, the Android
Gradle Plugin, AndroidX, Compose, and the Google Cast SDK cannot be resolved during a session, so
`assembleDebug` / `lintDebug` / instrumented & Compose UI tests **cannot run**. This is an
environment constraint, not a project defect.

Two things lift it:

1. `.github/workflows/copilot-setup-steps.yml` pre-warms the Gradle dependency cache from Google
   Maven while the setup runner still has full internet, so cached artifacts are available offline.
2. **Preferred:** a repository admin adds `dl.google.com` and `maven.google.com` to the **Copilot
   coding agent custom firewall allowlist** (repository **Settings → Copilot → Coding agent**). See
   <https://docs.github.com/en/copilot/how-tos/use-copilot-agents/cloud-agent/configuring-agent-settings>.
   The allowlist is applied **at session start**, so allowlist edits only take effect on the
   **next** agent session — not mid-session.

With the allowlist configured, the full Gradle build runs in-session and has been verified
end-to-end: `./gradlew assembleDebug testDebugUnitTest lintDebug` all succeed (128 unit tests pass).

### Verifying the pure-JVM core offline (always works in the sandbox)

The `com.adsamcik.streamferry.core` package is framework-free and can be compiled and tested with
`kotlinc` + JUnit 4 without the Android toolchain. The full, copy-pasteable recipe lives in
[`docs/BUILD.md`](../docs/BUILD.md) ("What IS verified in the sandbox"). Use it to validate any
change to the core (currently ~128 tests).

## Conventions and constraints

- **Supply chain:** only `google()` and `mavenCentral()` are allowed (see `settings.gradle.kts`,
  which sets `FAIL_ON_PROJECT_REPOS`). Do **not** add JitPack, jcenter, `mavenLocal`, or any other
  repository, and do not add a dependency without a clear need. Prefer existing libraries.
- **Versions:** edit `gradle/libs.versions.toml`; keep AGP/Gradle/Kotlin compatibility intact.
- **AGP 9 built-in Kotlin:** AGP 9 applies Kotlin itself, so the standalone
  `org.jetbrains.kotlin.android` plugin is intentionally **not** declared (applying it fails the
  build). Keep the Compose and serialization Kotlin compiler plugins; they attach to AGP's built-in
  Kotlin. Unit tests use the `kotlin.test` API, backed by the `kotlin-test-junit` test dependency.
- **Privacy:** no telemetry, analytics, ads, cloud calls, or tracking. Never add any.
- **Secrets & logging:** never log tokens, the `Authorization` header, the Jellyfin URL, or
  session stream URLs; route anything sensitive through the existing redaction helpers
  (`core/redaction`). Never commit a keystore or `keystore.properties`.
- **Code style:** Kotlin official style (`kotlin.code.style=official`). Match the style of the
  surrounding file; only add comments where the file already uses them or to explain non-obvious
  logic.
- **Tests:** add or update tests for behavior changes, preferably in the pure-JVM core so they run
  in the sandbox. Do not delete or weaken existing tests to make a build pass.

## Before you finish

- Keep the diff minimal and focused on the request.
- Update affected docs in `docs/` and this file if the build/test workflow changes.
- Validate what you can: run the pure-JVM core tests for core changes; for Android-only changes
  that cannot build in the sandbox, say so explicitly and explain how you reasoned about
  correctness.
