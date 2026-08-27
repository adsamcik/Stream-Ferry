# Stream Ferry demo environment

This opt-in local fixture makes the Android app look and behave as if a small Jellyfin server and a
DLNA television are on the network. It is intended for screenshots, Google Play foreground-service
declaration videos, manual QA, and future regression tests.

It deliberately uses Stream Ferry's real paths:

- server discovery, credential login, library browsing, artwork, playback-info, Range downloads,
  watch reporting, and offline storage;
- target selection, DLNA description validation and SOAP control, phone proxy URL generation,
  playback foreground service, MediaSession, and notification controls.

Only the receiver's behavior and Jellyfin API are mocked. The Android demo receiver entry is included
only when `-PdemoEnvironment=true` is passed to a **debug** build. Release builds cannot enable it.

## Start

From the repository root:

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\demo-environment\download-sample.ps1
powershell -ExecutionPolicy Bypass -File .\tools\demo-environment\start-demo.ps1
.\gradlew :app:assembleDebug -PdemoEnvironment=true
```

Install the generated debug APK, then connect with:

- Server: `http://10.0.2.2:8096` (Android Emulator host alias)
- Username: `demo`
- Password: `streamferry`

The library contains one normal playback item and one deliberately throttled download item. The TV
picker will include **Stream Ferry Demo TV** while the fixture is running. Its dashboard is at
`http://127.0.0.1:8097/` on the development machine.

The fixture listens on all host interfaces so the Android Emulator can reach it through `10.0.2.2`.
Use it only on a trusted development network, allow it through the host firewall only when needed,
and never reuse the demo credentials for a real server. Grant the debug app notification permission
before recording foreground-service notifications.

Stop the fixture with:

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\demo-environment\stop-demo.ps1
```

## Tests

The dependency-free contract tests do not require the downloaded media:

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\demo-environment\test-demo.ps1
```

The sample video and poster stay under ignored `media/`; see [ATTRIBUTION.md](ATTRIBUTION.md).
