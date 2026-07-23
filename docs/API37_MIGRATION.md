# API 37 Migration Notes

## Current SDK status (verified on the build host)

- **Android SDK Platform 37 (Android 17) IS installed** — `platforms/android-37.0`, extension level
  22, `build-tools/37.0.0` present.
- `ACCESS_LOCAL_NETWORK`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `POST_NOTIFICATIONS`, and
  `NEARBY_WIFI_DEVICES` are **real constants** in the installed `android-37` `android.jar` (verified).
- Therefore `compileSdk = 37`, `targetSdk = 37`, `minSdk = 34` are **genuine**, not faked.

So the API-37 fallback scaffold described in §2 is **not** required for SDK constants — the project
targets API 37 directly.

## The actual blocker: Google Maven unreachable in the build sandbox

- `dl.google.com` / `maven.google.com` return HTTP 000 (blocked) in this environment. `mavenCentral()`,
  `services.gradle.org`, and `plugins.gradle.org` are reachable.
- Consequence: the Android Gradle Plugin, AndroidX, Compose, and the Google Cast SDK **cannot be
  resolved here**, so `./gradlew assembleDebug` / `lintDebug` / instrumented tests **cannot run in the
  sandbox**.
- What was proven instead: the entire pure-JVM core (`com.videobridge.core`) compiles with
  `kotlinc` and **128 unit tests pass** (see [BUILD.md](BUILD.md)).

## Versions to re-verify at the first online build

These were resolved from official release notes / web search but **could not be downloaded** to
confirm here:

- Compose BOM `2026.06.00`
- `play-services-cast-framework 22.3.0`
- AndroidX `core-ktx`, `lifecycle`, `activity-compose`

Confirmed against Maven Central directly: AGP `9.2.1`, Kotlin `2.4.0`, Jellyfin SDK `1.8.11`
(LGPL-3.0).

## Exact migration / build steps on a connected machine

1. Ensure network access to `dl.google.com` / `maven.google.com`.
2. Confirm installed tooling: `sdkmanager --list | grep "37"` shows `platforms;android-37`,
   `build-tools;37.0.0`.
3. Pin/adjust the versions above in `gradle/libs.versions.toml` to the latest stable releases and
   re-resolve.
4. Generate dependency verification metadata:
   `./gradlew --write-verification-metadata sha256 help` → commit `gradle/verification-metadata.xml`.
5. Build & verify:
   - `./gradlew clean`
   - `./gradlew assembleDebug`
   - `./gradlew testDebugUnitTest`
   - `./gradlew lintDebug`
   - `./gradlew :app:dependencies`
   - release build once signing is configured.
6. If any API-37 tooling (lint baseline, R8 rules) is missing for the chosen AGP, drop `compileSdk`
   to the highest supported by that AGP **only as a provisional scaffold**, do **not** fake API-37
   constants, and record the exact gap here.

## Do-not-fake commitments

- No invented API-37 constants/permissions are used.
- No claim of "API 37 readiness" beyond what is verified: SDK present + core tests pass; full Android
  assembly is pending an environment with Google Maven access.
