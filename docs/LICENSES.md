# Licenses and Third-Party Notices

No telemetry, analytics, ads, cloud services, or tracking are included. Stream Ferry-owned code is
source available under PolyForm Noncommercial 1.0.0, while third-party components retain their own
terms. All runtime dependencies are permissively licensed except the Jellyfin SDK (LGPL-3.0) and the
closed-source Google Cast SDK (allowed exception). A machine-generated license report should be
produced at the online build via `./gradlew :app:dependencies` plus a license plugin or the AndroidX
OSS-licenses output; the table below is the curated summary.

| Component | License | Notes |
| --- | --- | --- |
| **Stream Ferry-owned code** | **PolyForm-Noncommercial-1.0.0** | Source available for noncommercial purposes; see [App license](#app-license). |
| Android Gradle Plugin, AndroidX, Compose | Apache-2.0 | Permissive. |
| Kotlin, kotlinx-coroutines, kotlinx-serialization | Apache-2.0 | Permissive. |
| OkHttp | Apache-2.0 | Permissive. |
| JUnit | EPL-1.0 | Test-only. |
| MockK, Robolectric, Espresso | Apache-2.0 | Test-only. |
| **Jellyfin Kotlin SDK** (`org.jellyfin.sdk:jellyfin-core:1.8.12`) | **LGPL-3.0** | See obligations below. |
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

Stream Ferry-owned code in distributions made after the licensing cutoff is offered under the
[PolyForm Noncommercial License 1.0.0](../LICENSE), SPDX identifier
`PolyForm-Noncommercial-1.0.0`. It may be used, changed, and redistributed for permitted
noncommercial purposes. It is source available, not OSI-approved open source, and the public license
does not grant commercial-use rights. Contact the copyright holder through the repository's contact
channels for a separate commercial license.

The cutoff is tag `v0.5.0`, commit `605dbcc7272f9025621c1ad9bd1b48970739b835`. Versions through and
including that point were published under the [MIT License](../LICENSE-MIT); those historical grants
are not revoked. [NOTICE](../NOTICE) records the scope, required copyright notice, and cutoff.

This first-party license does not apply to the Jellyfin SDK, Google Cast SDK, AndroidX, or any other
third-party component. Those components remain under the terms identified above. In particular, the
PolyForm terms and any separate commercial agreement must not restrict a recipient's LGPL rights in
the Jellyfin SDK.
