# API 37 Migration Notes

## Current SDK status (verified on the build host)

- **Android SDK Platform 37 (Android 17) IS installed** — `platforms/android-37.0`, extension level
  22, `build-tools/37.0.0` present.
- `ACCESS_LOCAL_NETWORK`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `POST_NOTIFICATIONS`, and
  `NEARBY_WIFI_DEVICES` are **real constants** in the installed `android-37` `android.jar` (verified).
- Therefore `compileSdk = 37`, `targetSdk = 37`, `minSdk = 34` are **genuine**, not faked.

So the API-37 fallback scaffold described in §2 is **not** required for SDK constants — the project
targets API 37 directly.

## Android dependency requirement

The Android Gradle Plugin, AndroidX, Compose, and Google Cast SDK must be available from the Gradle
cache or their configured repositories. A cold restricted environment without Google Maven access
cannot run `assembleDebug` or `lintDebug`; a connected or pre-warmed environment can. Record the exact
gate results for each change instead of carrying a sandbox-specific passing/failing claim here.

## Dependency version verification

`gradle/libs.versions.toml` is the authoritative pin set. CI should resolve that exact set before a
release, and dependency/license changes should be reviewed through [DEPENDENCY_RISK.md](DEPENDENCY_RISK.md)
and [LICENSES.md](LICENSES.md). Do not infer current compatibility from an old web-search result.

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
- No claim of "API 37 readiness" beyond the exact unit, assembly, lint, and device results recorded for
  the current change. Unit/compile success is not physical Cast/DLNA validation.
