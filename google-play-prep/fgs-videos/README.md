# Foreground-service declaration videos

This directory holds local-only screen recordings for the Google Play foreground-service
declarations. Video files are ignored by Git because Play Console requires a public or unlisted
video link rather than a repository asset.

Expected local files:

- `data-sync-demo.mp4` — starts an offline Jellyfin download, shows its foreground notification,
  leaves Stream Ferry, and confirms that the user-requested download continues.
- `media-playback-demo.mp4` — starts playback on the configured demo DLNA receiver, shows the
  playback controls and media notification, and confirms that playback remains controllable after
  leaving Stream Ferry.

Recordings use the opt-in fixture in `tools/demo-environment/`; the production and release builds
do not include or enable its renderer configuration.

After starting the fixture, installing the opt-in debug APK, and granting notification permission,
record a fresh take from the repository root with:

```powershell
powershell -ExecutionPolicy Bypass -File .\google-play-prep\scripts\record-fgs-video.ps1 -Scenario data-sync
powershell -ExecutionPolicy Bypass -File .\google-play-prep\scripts\record-fgs-video.ps1 -Scenario media-playback
```

The script records for a fixed time, pulls the result into this directory, and removes only its
temporary `/sdcard/stream-ferry-<scenario>.mp4` file from the selected emulator.
