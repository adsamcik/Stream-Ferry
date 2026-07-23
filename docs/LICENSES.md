# Licenses & Open-Source Report

No telemetry, analytics, ads, cloud services, or tracking are included. All runtime dependencies are
permissively licensed except the Jellyfin SDK (LGPL-3.0) and the closed-source Google Cast SDK
(allowed exception). A machine-generated license report should be produced at the online build via
`./gradlew :app:dependencies` plus a license plugin or the AndroidX OSS-licenses output; the table
below is the curated summary.

| Component | License | Notes |
| --- | --- | --- |
| Android Gradle Plugin, AndroidX, Compose | Apache-2.0 | Permissive. |
| Kotlin, kotlinx-coroutines, kotlinx-serialization | Apache-2.0 | Permissive. |
| OkHttp | Apache-2.0 | Permissive. |
| JUnit | EPL-1.0 | Test-only. |
| MockK, Robolectric, Espresso | Apache-2.0 | Test-only. |
| **Jellyfin Kotlin SDK** (`org.jellyfin.sdk:jellyfin-core:1.8.11`) | **LGPL-3.0** | See obligations below. |
| Google Cast Sender SDK (`play-services-cast-framework`) | Google Play Services Terms (closed-source) | Allowed §14 exception; requires Google Play services. |

## LGPL-3.0 obligations (Jellyfin SDK)

Because the app links the LGPL-3.0 Jellyfin SDK, to remain compliant when distributing:

1. **State** that the app uses the Jellyfin Kotlin SDK under LGPL-3.0 and include the license text
   (in the About/licenses screen and this file).
2. **Allow replacement** of the LGPL library: keep it as a normal dynamically-resolved dependency
   (do not statically fold/obfuscate it into a non-replaceable form), and provide enough information
   (e.g. the dependency coordinate + relinkable build, or unstripped object/library) for a user to
   relink against a modified version of the SDK.
3. **Do not impose** further restrictions that conflict with LGPL on that component.
4. R8/minification must not strip the SDK in a way that prevents relinking; document keep rules in
   `app/proguard-rules.pro` and keep the SDK separable.
5. Provide the **corresponding source / written offer** for the LGPL component (Jellyfin SDK source is
   publicly available; link it from the About screen).

> If meeting LGPL relinking obligations for a minified Android app proves impractical, the alternative
> is to use the **documented Jellyfin HTTP API directly** (already the chosen playback path via
> OkHttp) and drop the SDK dependency entirely. This decision is recorded in
> `data.jellyfin.JellyfinApiContract` and [KNOWN_LIMITATIONS.md](KNOWN_LIMITATIONS.md).

## App license

Choose and add a top-level `LICENSE` for this project at publication time (e.g. GPL-3.0-compatible if
shipping the LGPL SDK statically, or any license otherwise). Not yet selected.
