# Dependency Risk Table

Principles (§14): only low-risk, actively maintained, trusted dependencies; prefer official
Google/AndroidX/Jellyfin libraries and platform APIs; every dependency justified. Repositories are
restricted to `google()` and `mavenCentral()` with `RepositoriesMode.FAIL_ON_PROJECT_REPOS`. No
JitPack, jcenter, mavenLocal, dynamic versions (`+`, `latest.release`, ranges) or SNAPSHOTs. Gradle
dependency verification metadata and Gradle Wrapper validation are required.

> Verification status: AGP **9.2.1**, Kotlin **2.4.0**, and Jellyfin SDK **1.8.12 (LGPL-3.0)** were
> verified directly against Maven Central / official release notes. Compose BOM **2026.06.00** and
> Cast **22.3.1** could not be resolved in the build sandbox because `dl.google.com` /
> `maven.google.com` is blocked, and **must be re-verified at the first online build** (flagged in the
> version catalog and [BUILD.md](BUILD.md)). Pin exact versions and regenerate
> `gradle/verification-metadata.xml` then.

| Dependency | Version | Source | License | Why needed | Risk notes |
| --- | --- | --- | --- | --- | --- |
| Android Gradle Plugin | 9.2.1 | google() | Apache-2.0 | Build Android app, compileSdk 37 | Official Google; requires Gradle ≥ 9.4.1. |
| Kotlin plugin / stdlib | 2.4.0 | mavenCentral() | Apache-2.0 | Language | Official JetBrains; matches build host. |
| Compose Compiler plugin | 2.4.0 | mavenCentral() | Apache-2.0 | Compose | Bundled with Kotlin plugin. |
| Compose BOM | 2026.06.00 ⚠ | google() | Apache-2.0 | UI toolkit versions | **Re-verify**; pins all `androidx.compose.*`. |
| androidx.compose.* (ui, material3, tooling) | via BOM | google() | Apache-2.0 | Material 3 UI | Official AndroidX. |
| androidx.core:core-ktx | 1.15.0 ⚠ | google() | Apache-2.0 | Core KTX | Re-verify. |
| androidx.lifecycle (runtime/viewmodel-compose) | 2.9.0 ⚠ | google() | Apache-2.0 | ViewModel + lifecycle | Re-verify. |
| androidx.activity:activity-compose | 1.9.3 ⚠ | google() | Apache-2.0 | Compose host activity | Re-verify. |
| play-services-cast-framework | 22.3.1 ⚠ | google() | Closed-source (Google Play Terms) | Google Cast Sender SDK (§10) | Allowed closed-source exception; needs Google Play services on device; re-verify version. |
| org.jellyfin.sdk:jellyfin-core | 1.8.12 | mavenCentral() | **LGPL-3.0** | Jellyfin models/SDK (§8) | Verified on Maven Central. LGPL obligations — see [LICENSES.md](LICENSES.md). Currently the playback path uses the documented HTTP API via OkHttp; SDK retained for models/future. |
| com.squareup.okhttp3:okhttp | 4.12.0 | mavenCentral() | Apache-2.0 | HTTP client for Jellyfin upstream + proxy upstream fetch | Widely used, maintained. |
| kotlinx-coroutines-android | 1.9.0 | mavenCentral() | Apache-2.0 | Structured concurrency | Official JetBrains. |
| kotlinx-serialization-json | 1.7.3 | mavenCentral() | Apache-2.0 | Parse Jellyfin JSON | Official JetBrains; pinned. |
| io.coil-kt:coil-compose | 2.7.0 | mavenCentral() | Apache-2.0 | In-app poster/thumbnail loading (memory-cache only; shares app OkHttp; header auth) | Official Coil; phone-UI only, never forwarded to the TV. |
| androidx.media3:media3-transformer / -muxer / -common | 1.10.1 | google() | Apache-2.0 | Experimental local-file hardware transcoding into a Cast-only phone-hosted HLS/fMP4 origin | Official AndroidX/Media3; uses platform HW codecs; output served only as the phone proxy URL (`@UnstableApi` usage confined behind `data.transcode`). |
| junit:junit | 4.13.2 | mavenCentral() | EPL-1.0 | Unit tests | Test-only. |
| kotlinx-coroutines-test | 1.9.0 | mavenCentral() | Apache-2.0 | Coroutine tests | Test-only. |
| io.mockk:mockk | 1.13.13 | mavenCentral() | Apache-2.0 | Mocking | Test-only. |
| org.robolectric:robolectric | 4.14.1 | mavenCentral() | Apache-2.0 | JVM Android unit tests | Test-only. |
| androidx.test.ext:junit, espresso-core | 1.2.1 / 3.6.1 | google() | Apache-2.0 | Instrumented/UI tests | Test-only. |
| compose ui-test-junit4 / ui-test-manifest | via BOM | google() | Apache-2.0 | Compose UI tests | Test-only. |

No DLNA/UPnP library is used: SSDP, device-description parsing and AVTransport SOAP are implemented
with platform APIs + the hardened `SecureXml` parser to avoid abandoned/unmaintained DLNA libraries
(§11 dependency rule). No analytics/ad/DI/logging-helper libraries are included. The only image loader
is Coil (in-app posters/thumbnails, memory-cache only); the only media-processing library is AndroidX
Media3 Transformer (on-device transcoding) — both first-party/official.

## Required supply-chain controls (configured / to finalize at online build)

- `settings.gradle.kts`: `RepositoriesMode.FAIL_ON_PROJECT_REPOS`, only `google()` + `mavenCentral()`. ✅
- No dynamic versions / SNAPSHOTs — all versions pinned in the catalog. ✅
- Gradle Wrapper validation (CI: `gradle/wrapper-validation`). ▶ add CI workflow at online setup.
- `gradle/verification-metadata.xml` (checksums/signatures) — **generate with
  `./gradlew --write-verification-metadata sha256 help` on a connected machine** (cannot be produced
  in the sandbox because Google Maven is blocked). ▶
- Dependency report: `./gradlew :app:dependencies`. ▶
- SBOM: optional, via a trusted plugin (e.g. CycloneDX) if accepted. ▶
