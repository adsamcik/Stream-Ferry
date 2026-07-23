# Manifest Permission Inventory

Every declared permission, why it exists, its runtime status, when it is requested, what happens when
denied, and MVP/future status. Forbidden permission categories (location, storage, camera,
microphone, contacts, Bluetooth, accounts, SMS, call logs, exact alarms) are **not** declared. The
sole media-library permission, `READ_MEDIA_VIDEO`, **is** declared but is strictly **optional and
user-elective** — off by default and requested only if the user opts into the device-wide local video
gallery; the default on-device path uses the permissionless SAF picker.

| Permission | Reason | Runtime? | Requested when | If denied / revoked | Status |
| --- | --- | --- | --- | --- | --- |
| `INTERNET` | Reach Jellyfin upstream and serve the local proxy socket | No (install-time) | n/a | App cannot function (no network); not user-revocable | MVP |
| `ACCESS_NETWORK_STATE` | Detect Wi-Fi/VPN/network transitions for diagnostics and session cleanup | No | n/a | Diagnostics degrade; core still works | MVP |
| `ACCESS_WIFI_STATE` | Read the phone LAN IPv4 to build proxy URLs and show in diagnostics | No | n/a | Falls back to NetworkInterface enumeration; diagnostics may be limited | MVP |
| `ACCESS_LOCAL_NETWORK` | Android 16+/17 local-network access — TV fetching from the phone proxy, and Cast/DLNA discovery/control on the LAN | **Yes** | Just before first playback / discovery that needs LAN access, with rationale | Jellyfin browsing stays usable; target discovery + proxy-to-TV blocked with an actionable explanation; offer re-request and settings deep-link | MVP |
| `CHANGE_WIFI_MULTICAST_STATE` | Receive SSDP/mDNS multicast for DLNA discovery (paired with a `MulticastLock` held only during discovery) | No | n/a | DLNA discovery may miss devices on multicast-filtered networks; Cast still works | MVP |
| `POST_NOTIFICATIONS` | Show the persistent foreground-service playback notification | **Yes** (API 33+) | When playback (foreground service) starts | Playback still works; notification suppressed by OS; explain why the notification matters | MVP |
| `FOREGROUND_SERVICE` | Run the proxy/playback foreground service during active playback | No | n/a | Not revocable | MVP |
| `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | Type permission for the `mediaPlayback` foreground service | No | n/a | Not revocable | MVP |
| `WAKE_LOCK` | Hold a partial CPU wake lock + a high-perf Wi-Fi lock *while casting* so the phone keeps serving the proxy stream to the TV with the screen off / app backgrounded (the phone is a server, not a local player, so nothing else keeps the CPU/Wi-Fi awake) | No | n/a | Not revocable; released as soon as playback stops | MVP |
| `READ_MEDIA_VIDEO` | **Optional / user-elective:** read the device video library to populate the on-device "All device videos" gallery | **Yes** (API 33+) | Only when the user explicitly taps "Allow all videos" in the on-device source | The on-device source falls back to the permissionless SAF folder/file picker; nothing else is affected | MVP (elective, off by default) |

## Notes

- **Local media access is user-elective.** The on-device video source defaults to the **SAF** picker
  (`OpenDocumentTree` / `OpenDocument`), which needs **no permission** — the user picks a folder or
  files and the app holds a persisted URI grant. `READ_MEDIA_VIDEO` is requested **only** if the user
  explicitly chooses "Allow all videos" for a full device-wide gallery; denial silently falls back to
  the SAF picker. Picked content is opened on the phone and streamed via the proxy — the `content://`
  URI is never sent to the TV (the phone-gateway invariant holds for local files too).
- `ACCESS_LOCAL_NETWORK` is the first-class Android 17/API 37 local-network permission. The app must
  request and explain it, and keep Jellyfin browsing usable when it is denied (see
  [VPN_LAN_DIAGNOSTICS.md](VPN_LAN_DIAGNOSTICS.md)). Verified present as a real constant in the
  installed `android-37` platform.
- **Enforcement is platform-version dependent.** On releases where the platform does *not* yet treat
  `ACCESS_LOCAL_NETWORK` as a dangerous runtime permission (e.g. Android 16/API 36, where Local Network
  Protection is opt-in), the OS never shows a prompt and LAN access works without a grant. To avoid a
  misleading "denied", the diagnostics Permissions card reports **"not enforced (Android X)"** in that
  case and only shows **"denied"** when the platform actually defines it as a dangerous permission and
  the user has refused it. `AndroidNetworkPermissionManager.localNetworkStatus()` resolves this via
  `PackageManager.getPermissionInfo(...).protection == PROTECTION_DANGEROUS`.
- `NEARBY_WIFI_DEVICES` is **not** declared: this app does not use Wi-Fi Aware/Wi-Fi Direct/peer APIs.
  Revisit only if a discovery API requires it on the target SDK.
- No boot/`RECEIVE_BOOT_COMPLETED` permission: the proxy/service never auto-starts on boot.
- Foreground-service type rationale (`mediaPlayback` vs `connectedDevice`/`dataSync`/`specialUse`) is
  recorded in [PROXY_DESIGN.md](PROXY_DESIGN.md).
- All `<activity>`/`<service>` components set `android:exported` explicitly; only `MainActivity` is
  exported (launcher). The foreground service is `exported="false"`.
