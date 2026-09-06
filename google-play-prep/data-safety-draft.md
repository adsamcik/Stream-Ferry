# Data Safety draft — review in Play Console

This is a preparation worksheet, not a submitted declaration. Re-check the current Play Console
question wording, every dependency, and actual production traffic before answering. Google makes
the developer responsible for complete and accurate declarations, including third-party SDKs.

## Likely top-level answer

Answer **Yes** to whether the app or included SDKs collect data. Stream Ferry has no
developer-operated analytics backend, but the Google Cast Sender SDK automatically sends anonymous
Cast interaction, device, SDK, and client-app metadata to Google for aggregate SDK improvement.

## Google Cast Sender SDK

Google's current disclosure says the Android Sender SDK collects:

- general device information;
- Cast SDK and client-app metadata;
- general discovery and Cast session events; and
- anonymous Cast app activity, such as interactions with receiver apps.

Google says this data is encrypted in transit, used in aggregate to improve the Cast SDK, not used
to model a specific user's experience, and not shared with third parties. There is no opt-out or
deletion mechanism in the SDK.

Map this conservatively in Play Console to the closest current categories—likely **App activity /
Other actions** plus a device or identifier-related category if the console's definition covers the
reported device metadata. Mark collection as automatic and required. Confirm the exact category and
permitted-purpose selections against the current Cast SDK disclosure at submission time; do not
guess where Play Console wording has changed.

## Stream Ferry data flows to review

| Data or action | Destination | Draft treatment |
| --- | --- | --- |
| Jellyfin server URL, username, access token, and user ID | The user's chosen Jellyfin server | Optional feature; encrypted on device at rest. Review whether Play's user-directed transaction or service-provider rules apply. |
| Library queries and playback activity | The user's chosen Jellyfin server | Required only when the user enables Jellyfin. Describe as app functionality if the console requires declaration. |
| Selected video bytes | The chosen TV/receiver, Jellyfin server, or phone-local relay | User-initiated app functionality. Review Play's user-directed sharing exemption. |
| Local video metadata and downloads | On device | On-device-only processing is outside Play's collection definition unless transmitted by another flow. |
| Crash reports | On device until the user taps Share | Treat the Android share-sheet action as user-initiated; verify the destination chosen by the user is not represented as developer collection. |
| Cast discovery/session metadata | Google Cast SDK log service | Declare according to Google's current Cast SDK guidance. |

## Security and deletion answers to verify

- **Encrypted in transit:** Jellyfin can be configured with HTTPS, but users may choose HTTP on a
  trusted LAN. Do not claim every network flow is encrypted unless the console question and app
  behaviour support that answer. Cast SDK telemetry is encrypted in transit according to Google.
- **Data deletion:** A user can remove a Jellyfin source, clear app data, or uninstall the app to
  remove app-held credentials and local metadata. Data retained by the user's Jellyfin server is
  controlled by that server. Google's Cast SDK says users cannot opt out of or delete its anonymous
  SDK logging data.
- **Accounts:** Stream Ferry does not create developer-operated accounts. Jellyfin credentials are
  for an external server selected by the user.

## Primary references

- Google Play Data Safety guidance: https://support.google.com/googleplay/android-developer/answer/10787469
- Google Cast Android Sender SDK disclosure: https://developers.google.com/cast/docs/android_sender/data_disclosure
- Stream Ferry privacy policy draft: https://adsamcik.github.io/Stream-Ferry/privacy/
