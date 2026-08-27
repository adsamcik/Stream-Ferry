# Google Play release preparation

This directory is a **local draft kit** for a future Google Play release of Stream Ferry 0.6.0.
Nothing here has been uploaded to Play Console. Review every declaration in the console before
submitting it; the developer is responsible for the final answers.

## Prepared locally

- English (United States) store title, descriptions, and release notes.
- Eight real 1080 x 1920 screenshots captured from Stream Ferry on an Android 16 emulator, including
  fixture-backed library, receiver picker, playback, and offline-download states.
- A reproducible 512 x 512 app icon and 1024 x 500 feature graphic.
- Draft App access, Data Safety, content, and release checklists.
- Local-only foreground-service declaration recordings and repeatable capture tooling.
- A guarded release-bundle script that requires a non-debug upload key.

The store-ready files are under `en-US/`. Raw captures and UI hierarchy diagnostics stay under
`source-captures/` and are not intended for upload.

## Rebuild the graphics

From the repository root:

```powershell
powershell -ExecutionPolicy Bypass -File .\google-play-prep\scripts\prepare-assets.ps1
```

This derives the store icon from the checked-in app artwork and the feature graphic from the
checked-in website social card. It does not regenerate or alter the app screenshots.

After replacing any emulator screenshot, normalize its PNG encoding to Google Play's 24-bit,
no-alpha format:

```powershell
powershell -ExecutionPolicy Bypass -File .\google-play-prep\scripts\normalize-screenshots.ps1
```

## Build a Play bundle later

1. Enrol in Play App Signing and decide which long-lived key is the upload key.
2. Create the ignored root `keystore.properties` with `storeFile`, `storePassword`, `keyAlias`, and
   `keyPassword`.
3. Run:

```powershell
powershell -ExecutionPolicy Bypass -File .\google-play-prep\scripts\build-play-bundle.ps1
```

The script rejects a missing key, an Android debug certificate, or a failed release build. A
successful run copies the signed AAB and a SHA-256 checksum into `artifacts/`, which is local-only
and ignored by Git.

## Foreground-service declarations

The local recordings and their repeatable workflow are described in `fgs-videos/README.md`. Use
`foreground-service-declarations.md` for the exact draft selections and reviewer-facing explanation.
The MP4 files are deliberately ignored; upload the final clips to an unlisted, reviewer-accessible
video host and paste those links into Play Console.

## Before opening a production release

Work through `play-console-checklist.md`, then validate the AAB on an internal testing track. In
particular, review the Google Cast SDK disclosure in `data-safety-draft.md`; Stream Ferry does not
run an analytics backend, but the Cast SDK reports anonymous SDK interaction and device/app
metadata to Google.
