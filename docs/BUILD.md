# Build & Test

## Requirements

- JDK 17+.
- Android SDK Platform 37 + build-tools 37.0.0 (installed on the build host).
- Gradle wrapper and Android Gradle Plugin versions are pinned in the repository; use the wrapper.
- **Network access to `google()` / `maven.google.com`**, or a complete warm dependency cache — see
  Android dependency availability below.

## Standard commands (run on a machine with Google Maven access)

```bash
./gradlew clean
./gradlew assembleDebug
./gradlew testDebugUnitTest
./gradlew lintDebug
./gradlew :app:dependencies
# Dependency verification (generate once, then committed):
./gradlew --write-verification-metadata sha256 help
# Release (after configuring signing via keystore.properties):
./gradlew assembleRelease
```

## Continuous integration (GitHub Actions)

GitHub-hosted runners assemble the Android app, run unit tests, and run lint:

- **`ci.yml`** runs on pushes and pull requests to `main` and uploads the debug APK and reports.
- **`release.yml`** runs from a `vMAJOR.MINOR.PATCH` tag. It requires the stable release key,
  builds the R8-minified APK and publishes the APK, checksum, and signer report as a public GitHub Release.

Release signing, the protected GitHub Environment, public-certificate
verification, and the exact tagging process are documented in [RELEASE.md](RELEASE.md). The release
workflow never falls back to a debug key: a missing signing secret fails safely before building.

## Android dependency availability

The full Android gates require SDK Platform 37 and resolved artifacts from `google()` / Maven Central.
A host with a warm Gradle cache may run them offline; a cold host without repository access cannot.
Report that situation as an environment limitation, not as a passing build. GitHub Actions remains the
authoritative connected build described above.

## Pure-JVM fallback

The framework-free `com.adsamcik.streamferry.core` subset can be compiled and tested with `kotlinc` +
JUnit 4 when Android dependencies are unavailable. This is useful diagnostics, but it is not a
substitute for `testDebugUnitTest`, `assembleDebug`, and `lintDebug`:

```bash
KT=/usr/share/kotlinc/lib
LIBS=/tmp/verify/libs   # junit-4.13.2.jar + hamcrest-core-1.3.jar from Maven Central
mkdir -p /tmp/verify/out /tmp/verify/tests

# 1. Compile main core + the DLNA AVTransport helper:
MAIN=$(find app/src/main/java/com/adsamcik/streamferry/core -name '*.kt')
MAIN="$MAIN app/src/main/java/com/adsamcik/streamferry/data/dlna/AVTransport.kt"
kotlinc -cp "$KT/kotlin-stdlib.jar" $MAIN -d /tmp/verify/out

# 2. Compile tests:
CP="$KT/kotlin-test.jar:$KT/kotlin-test-junit.jar:$LIBS/junit-4.13.2.jar:$LIBS/hamcrest-core-1.3.jar:/tmp/verify/out"
TESTS=$(find app/src/test/java/com/adsamcik/streamferry/core -name '*.kt')
kotlinc -cp "$CP" $TESTS -d /tmp/verify/tests

# 3. Run:
CLASSES=$(cd /tmp/verify/tests && find . -name '*Test.class' | sed 's#^\./##;s#/#.#g;s#\.class$##' | grep -v '\$')
java -cp "$CP:/tmp/verify/tests:$KT/kotlin-stdlib.jar:$KT/annotations-13.0.jar" \
     org.junit.runner.JUnitCore $CLASSES
# => OK (current core-only test suite)
```

These same tests run under Gradle as `./gradlew testDebugUnitTest` on a connected machine (they live
in `app/src/test`).

## Test coverage (pure-JVM, runnable now)

- HTTP Range parsing (RFC 9110), 200/206/416 + Content-Range selection.
- Session validation: unknown/expired/path-traversal/non-session/sub-path rejection, 256-bit ids.
- Log redaction (tokens/URLs/query params).
- Stream selection (direct/remux/audio-transcode/HLS + burn-in).
- Memory buffer policy (bounds, memory-pressure decisions, seek window).
- HLS playlist URL rewriting.
- DLNA `SecureXml` XXE hardening + `AVTransport` control-URL resolution.

## Live end-to-end test against a Dockerized Jellyfin (no device needed)

`app/src/test/java/com/adsamcik/streamferry/integration/JellyfinLiveIntegrationTest.kt` exercises the **real**
networking core — `JellyfinClient` (connect/validate/login/browse/playback-info), `HttpJellyfinRepository`,
`DeviceProfiles`, and `LocalProxyServer` (incl. HLS playlist rewriting + segment proxying) — against a
real Jellyfin server, and **simulates the TV** by fetching the phone proxy URL over HTTP. It asserts that
an H.264 source direct-plays, an HEVC source is transcoded server-side to **HLS for Cast** and to
**progressive MPEG-TS for DLNA** (i.e. it casts in the correct format), and that the playlist/bytes the TV
receives contain **no Jellyfin URL or token**. It **skips itself** (JUnit `Assume`) when no Jellyfin is
reachable, so it never affects normal CI.

Reproduce locally (Docker + the Jellyfin image's bundled ffmpeg — no host ffmpeg needed):

```bash
# 1. Run Jellyfin
docker run -d --name jf-it -p 8096:8096 -v jf-it-config:/config -v jf-it-media:/media jellyfin/jellyfin:latest

# 2. Generate a direct-play (H.264) and a transcode-forcing (HEVC) sample with the container's ffmpeg
FF=/usr/lib/jellyfin-ffmpeg/ffmpeg
docker exec jf-it sh -lc "mkdir -p /media/SampleH264 /media/SampleHEVC"
docker exec jf-it sh -lc "$FF -y -f lavfi -i testsrc=duration=15:size=640x360:rate=25 -f lavfi -i sine=duration=15 -c:v libx264 -pix_fmt yuv420p -c:a aac -shortest /media/SampleH264/SampleH264.mp4"
docker exec jf-it sh -lc "$FF -y -f lavfi -i testsrc=duration=15:size=640x360:rate=25 -f lavfi -i sine=duration=15 -c:v libx265 -pix_fmt yuv420p -c:a aac -shortest /media/SampleHEVC/SampleHEVC.mkv"

# 3. Complete the first-run wizard via the API (wait until /Startup/Configuration returns 200 first):
#    POST /Startup/User {"Name":"admin","Password":"jellyfin123"}  then  POST /Startup/Complete
#    Authenticate (POST /Users/AuthenticateByName), then add a "movies" library at /media
#    (POST /Library/VirtualFolders?name=Movies&collectionType=movies&refreshLibrary=true with
#     {"LibraryOptions":{"PathInfos":[{"Path":"/media"}]}}) and wait for the two movies to scan.

# 4. Run the live test (override host/creds with -Djellyfin.url / -Djellyfin.user / -Djellyfin.pass):
./gradlew testDebugUnitTest --tests "com.adsamcik.streamferry.integration.JellyfinLiveIntegrationTest"

# 5. Tear down
docker rm -f jf-it && docker volume rm jf-it-config jf-it-media
```

Verified end-to-end this way against Jellyfin 10.11.11: H.264 direct-played (233 KB streamed through the
proxy), HEVC transcoded to HLS for Cast (playlist + nested media playlist rewritten, a 61 KB transcoded
`.ts` segment proxied) and to progressive MPEG-TS for DLNA (441 KB streamed) — with **no token or
Jellyfin URL leaked** to the simulated TV at any level. The physical Cast/DLNA renderer leg still needs a
real device/emulator (see [COMPATIBILITY_MATRIX.md](COMPATIBILITY_MATRIX.md)).

## Tests that require an emulator/device (run on a connected machine)

Compose UI tests (welcome/server-setup/permissions/login/library/detail/target-picker/diagnostics
redaction) and instrumented integration tests (proxy socket, Range over the wire, fake Cast/DLNA
controllers) — see [COMPATIBILITY_MATRIX.md](COMPATIBILITY_MATRIX.md).
