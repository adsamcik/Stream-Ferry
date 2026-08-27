# Play Console preparation checklist

Nothing in this checklist has been submitted.

## Account and app record

- [ ] Complete the required Play developer account and identity verification.
- [ ] Create the app as **Stream Ferry**, default language **English (United States)**, type **App**,
      pricing **Free**, category **Video Players & Editors**, and **Contains ads: No**.
- [ ] Confirm the permanent application ID is `com.adsamcik.streamferry`.
- [ ] Select countries/regions and provide a public support email and website.

## Store presence

- [x] Title, short description, full description, and 0.6.0 release notes drafted.
- [x] Eight real 1080 x 1920 portrait screenshots captured, including the local demo library,
      DLNA receiver picker, Now Playing, and completed offline-copy state.
- [x] 512 x 512 app icon and 1024 x 500 feature graphic prepared.
- [ ] Proofread all copy in the rendered Play listing and localize if desired.
- [ ] Confirm trademark and third-party naming are acceptable.

## App content

- [ ] Publish and enter `https://adsamcik.github.io/Stream-Ferry/privacy/` as the privacy-policy URL.
- [ ] Complete **Data Safety** using `data-safety-draft.md`; independently verify the Cast SDK and
      every production network flow immediately before submission.
- [ ] Complete **App access** using `app-access.md`; add a temporary Jellyfin demo login in Play
      Console only if reviewers need the remote-server flow.
- [ ] Complete both foreground-service declarations using `foreground-service-declarations.md`.
- [ ] Upload the local data-sync and media-playback clips as unlisted reviewer-accessible videos and
      paste their public links into the declarations.
- [ ] Complete the content-rating questionnaire. The app supplies no media, but it can display media
      chosen by the user or available on their Jellyfin server.
- [ ] Confirm the intended target audience. The current product is not designed for children; make
      the final audience choice deliberately in the console.
- [ ] Complete any News, Ads, Government, Financial features, Health, and other declarations shown
      by the current console; answer from actual app behaviour.

## Technical release

- [ ] Enrol the app in Play App Signing and securely back up the upload key.
- [ ] Create ignored `keystore.properties`; do not commit the key or passwords.
- [ ] Run `scripts/build-play-bundle.ps1` and archive its SHA-256 checksum privately.
- [ ] Inspect the signed AAB in Android Studio's APK Analyzer or `bundletool`.
- [ ] Upload to an internal testing track first and install through Google Play.
- [ ] Test Jellyfin HTTPS and trusted-LAN HTTP, local video selection, offline downloads, Cast,
      DLNA/UPnP, relay/transcode playback, foreground-service notifications, and account removal.
- [ ] Test on real Android 14+ phones and real TV/receiver hardware; emulator screenshots do not
      replace physical playback validation.
- [ ] Review the permission inventory, especially local-network, media-library, notifications,
      foreground-service, and battery-optimization behaviour, against current Play policy.
- [ ] Check target API, 64-bit native-code, SDK policy, pre-launch report, Android vitals, and device
      catalog warnings shown by Play Console at upload time.

## Current blockers before production

- A production upload key / `keystore.properties` is not part of this repository.
- Any reviewer-only Jellyfin demo URL and credentials must be provisioned securely in Play Console.
- Foreground-service video files exist only locally until they are uploaded to a reviewer-accessible
  video host; Play Console cannot use local file paths.
- Cast and DLNA playback still need final tests on real supported hardware and representative media.
- Data Safety categories must be confirmed against the exact current Play Console form and Cast SDK
  release used in the production bundle.
