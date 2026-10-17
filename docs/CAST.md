# Google Cast Integration

Cast uses the **direct Google Cast Sender SDK**
(`play-services-cast-framework`), not AndroidX Media3 Cast / `CastPlayer` / `RemoteCastPlayer` (§10).
A written decision is required before introducing Media3 Cast; none is made because the
direct SDK fully covers load/control/status/lifecycle of a phone-hosted URL on the Default Media
Receiver.

## Components

- `data.cast.CastOptionsProvider` — `OptionsProvider` referenced from the manifest `meta-data`
  (`com.google.android.gms.cast.framework.OPTIONS_PROVIDER_CLASS_NAME`).
- `data.cast.CastTargetController` — implements `CastTargetController`: session lifecycle,
  generation-bound route/session binding, result-aware `RemoteMediaClient` load/control, media status
  + error handling, progress, SDK-owned resume, and stop cleanup.
- `TargetPickerScreen` renders protocol-independent `PhysicalTv` rows. `CastTargetController` owns an
  active `MediaRouter` scan while that screen is visible and contributes raw Cast endpoints to the
  conservative Cast/DLNA aggregator. Selecting a TV chooses an endpoint internally.

## Discovery & permissions

Discovery/selection uses AndroidX `MediaRouter` through the app-level physical-TV picker. The app
**explicitly requests `ACCESS_LOCAL_NETWORK`** for LAN discovery/control and the proxy-to-TV leg
([MANIFEST_PERMISSIONS.md](MANIFEST_PERMISSIONS.md)). Android's system **Output Switcher** can act as
an in-app picker that is *exempt* from the local-network permission, but it is not Stream Ferry's normal
selection path. The proxy leg needs LAN access regardless of how the target is picked,
and an explicit request yields a clear rationale prompt plus a graceful denied/revoked fallback
(Jellyfin browsing stays usable — [VPN_LAN_DIAGNOSTICS.md](VPN_LAN_DIAGNOSTICS.md)).

## Receiver

- **Receiver app ID:** the **Default Media Receiver** (`CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID`,
  i.e. `CC1AD845`). It is preferred and **does not require Google Cast Developer Console registration**.
- **Receiver type:** Default Media Receiver (no Styled/Custom receiver in MVP).
- A Styled/Custom receiver **would** require Developer Console registration. A Custom receiver is
  future-only, justified only if needed for auth behavior, advanced subtitle/audio tracks,
  diagnostics, HLS handling, or receiver-side probing.

## Connecting

Connecting selects the device's `MediaRouter` route (briefly polling for a route that is momentarily
absent) and awaits a Cast session start, bounded by a connect timeout. A session start can fail
**transiently** — the SDK fires `onSessionStartFailed` quickly and a fresh attempt usually succeeds — so
the controller retries automatically instead of surfacing a first-try hiccup to the user. The pure,
unit-tested `com.adsamcik.streamferry.core.cast.ConnectRetryPolicy` decides:

- a fast **failure** is retried up to `MAX_ATTEMPTS` (default 3), dropping any half-open session and
  re-selecting the route between attempts (a short settle delay in between);
- a full **timeout** is retried at most once (a genuine timeout is rarely transient and each retry costs
  another full timeout), so the connecting wait can't pile up.

Only after retries are exhausted is a connection error surfaced.

Target replacement is generation-bound: the controller waits for the session that matches the requested
route, then atomically installs its session/client callback binding before reporting `Connected`. During a
suspension, the Cast SDK owns recovery; the app only receives a terminal disconnect after resume failure
or session end, so it cannot race the SDK with a second full reload.

## Progress after screen-off suspension

The controller checkpoints the SDK's approximate position every five seconds while Android schedules
the app and the receiver is playing. Approximate progress is cached sender state; it must not be the
only source indefinitely. After 30 seconds without a fresh status, the heartbeat requests media status
from the receiver. The refresh deadline uses `SystemClock.elapsedRealtime()`, including deep sleep, so
the first scheduled heartbeat after a long sleep can refresh immediately.

Returning to the activity and resuming a suspended Cast session both request fresh receiver status and
restart progress tracking. A successful request publishes the current position even when the SDK does
not issue another status callback. This updates the engine, UI, media session, and resume checkpoints
through the existing status event path. Paused and buffering states stop the recurring heartbeat.

Only one refresh runs at a time, with the existing 20-second Cast command timeout. Failed periodic
requests retry no sooner than the next 30-second deadline and do not turn a progress refresh failure
into a playback failure. Suspension/disconnect cancels pending refreshes; session generation checks
reject old callbacks. These checks do not acquire a wake lock or promise network access during Doze.

`CastProgressTest` covers a ten-minute sleep gap, periodic reconciliation, stale paused state on
foregrounding, silent session recovery, paused/buffering state, single in-flight requests, timeout/failure
retries, and late callbacks after disconnect. These are tests with a simulated receiver; physical
screen-off playback remains a separate acceptance check in [PHYSICAL_TV_PLAYBACK.md](PHYSICAL_TV_PLAYBACK.md).

Validation on 2026-09-08: `testDebugUnitTest assembleDebug lintDebug checkSourceBoundaries` and
`:core:test :source:api:test` succeeded with the existing Gradle cache (including up-to-date tasks).
Combined JUnit reports account for 674 passing tests, 25 skipped opt-in tests, and zero failures.
The eight Cast progress regressions passed. No ADB device was connected for the physical TV check.

API references: [RemoteMediaClient status and position](https://developers.google.com/android/reference/com/google/android/gms/cast/framework/media/RemoteMediaClient)
and [Android elapsed time and deep sleep](https://developer.android.com/reference/android/os/SystemClock).

Delivery-backed crash recovery and Jellyfin position estimates are described in
[DELIVERY_PROGRESS.md](DELIVERY_PROGRESS.md). They combine confirmed TV status with elapsed time and
indexed media delivery, retaining a separate, bounded recovery estimate when receiver status is delayed.

## Volume control and reconciliation

Android's `CastSession.setVolume()` is an asynchronous `void` request, not a result-bearing media command.
An immediate `CastSession.getVolume()` can therefore still expose the SDK's prior cached value and is not
proof that the receiver rejected the requested level.

The active Cast session owns a generation-bound `Cast.Listener`. Its `onVolumeChanged()` callback emits an
authoritative normalized volume event to the playback engine, including changes originating from another
sender or the TV. After setting volume, the controller also requests fresh Cast status, while the engine
accepts an immediate read only when it already matches the requested level.

The UI keeps the requested level optimistically while waiting for this reconciliation. A matching report
retires the pending state immediately. After two seconds, a newer mismatching renderer report wins; with no
new report, the UI keeps the requested value without presenting a false failure or returning the slider to
its old position. The timeout and every eventual Cast volume report are recorded in diagnostics, and any
later receiver report remains authoritative, so delayed or failed communication is still observable and
correctable without disrupting a volume command that audibly succeeded.

## Media load

The receiver receives **only the phone proxy URL** — never a Jellyfin URL or token. The `MediaInfo`
includes: phone proxy URL (content id/URL), correct MIME/content type, stream type, title, duration
if known, safe metadata, optional poster only if approved and proxied, subtitles only if served
through the proxy, and HLS only if rewritten through the proxy. No credentials in `customData`.
Server-provided title metadata is control-character normalized and capped by UTF-8 byte length before it
is sent to the receiver or shown in the notification.

## System playback controls

`MediaSessionController` owns the active media session for both Cast and DLNA. Its callbacks route
play/pause, relative skips, timeline seeking, stop, and remote volume through `PlaybackEngine` to the
selected renderer. The proxy service uses that session's notification, so there is one playback card
with the media title, TV name, and current state. Paused playback keeps the session active; stopping
deactivates it. Notification taps open Now Playing. Android controls the final system layout.

The Cast SDK's notification and media session are both explicitly disabled. Disabling only its
notification does not leave a working SDK media session: the SDK also requires notification options
before attaching that session. Previously the app deactivated its own session for Cast on the
assumption that the SDK was handling it, leaving no active session for system media controls.

Framework tests cover session ownership, metadata, transport commands, remote-volume callbacks, and
capability gating. Lock-screen appearance and screen-off volume routing still need physical phone
and TV verification.

The native card uses numbered 30-second rewind/forward icons, play/pause, and Stop. Android 13+
places play/pause and the two skip actions in its three compact slots; Stop is available when
expanded. A known positive duration and `ACTION_SEEK_TO` enable the system's draggable timeline.
Unknown-duration playback retains relative skips without advertising timeline seeking. The card
publishes the sanitized media title and selected TV name; the notification also reports preparing,
buffering, playing, or paused status. Position updates refresh the session without reposting the card.

The Edge Play browser preview is a design study, not the system notification layout. Traditional
custom RemoteViews do not support SeekBar. Android 15+ DrawInstructions can render richer content,
but the framework's action bridge dispatches prebound intents without forwarding dynamic action
metadata, so it does not establish a working custom drag-to-seek control across the app's Android
14+ range. The approved fallback retains Android's native seeking and layout. See the
[RemoteViews API](https://developer.android.com/reference/android/widget/RemoteViews),
[framework action bridge](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/widget/RemoteViews.java),
and [system media controls](https://developer.android.com/media/implement/surfaces/mobile).

## CORS

The Cast receiver's player fetches HLS playlists/segments (and any subtitle tracks) via cross-origin
XHR, which the browser blocks without CORS headers — a common cause of "casts but won't play". The
phone proxy therefore sends `Access-Control-Allow-Origin: *` plus `Access-Control-Allow-Methods`,
`Access-Control-Allow-Headers: Range, Content-Type, Accept-Encoding`, and
`Access-Control-Expose-Headers: Content-Length, Content-Range, Accept-Ranges, Content-Type` on **every**
response (media, HLS playlist/segment, and status), and answers the receiver's `OPTIONS` preflight with
`204`. The canonical header set lives in `core/http/HttpResponsePlan.CORS_HEADERS` (unit-tested).

Permissive origin does not weaken the model: reachability is gated by the LAN-only bind + the
unguessable 256-bit session id, and proxied bytes never contain the Jellyfin URL/token. CORS only
controls which web origin may *read* a response it could already request.

## Audio languages

The Audio control lists source tracks, including separate commentary tracks. Local files, downloaded
copies and content URIs discover embedded languages through Media3. Multi-audio local media uses the
phone's continuous encoder so the receiver cannot substitute its own default track. Exact track IDs
are retained; duplicate labels include a track number. Phone decoder support still applies.

Jellyfin prepares a generated stream containing the chosen audio, even when choosing the source
default or Original video output. The selected media version, language, pause state, subtitles and
absolute position survive seeks, output changes, protocol switches and failed-switch retries. Saved
language preferences apply to new playback; an explicit choice during startup takes precedence.
Missing or unsupported selections report an error instead of silently switching language.

## Subtitles

The Captions control offers available tracks, Off, and a local subtitle-file picker. Selected Jellyfin
subtitles are burned into a server-generated stream, pinned to the selected media version. Local
embedded captions and picked subtitle files use the phone's streaming encoder; the TV receives the
same captioned video through HLS/CMAF. Selection and source timing survive seeking, pause/resume,
output changes and protocol handoffs, including retries.

Media3 decodes embedded text and supported bitmap subtitle codecs. External files support SRT,
WebVTT, ASS/SSA and TTML. Styling follows Media3's subtitle renderer; unsupported or unreadable
selections report an error. Captions are not sent as provider URLs or credentials. Off strips known
embedded caption packets instead of relying on the TV's default track choice. This can require a
generated stream even when video itself could direct-play. Physical receiver rendering still needs
the acceptance checks in [PHYSICAL_TV_PLAYBACK.md](PHYSICAL_TV_PLAYBACK.md).

## Codec probing

No codec probing is claimed from the Android sender alone. Receiver-side probing would need a custom
receiver and is only a hint. With **Prefer original quality** enabled, Cast makes one deliberately broad
direct-play attempt, then falls back to the conservative H.264/AAC profile if the receiver supplies
explicit decode or unsupported-source evidence. Only that qualified evidence is remembered as a future
transcode requirement; ambiguous proxy, network, CORS, and generic idle failures are transient.
Physical playback coverage remains required: load the phone URL → first frame → 30 s play → seek →
pause/resume → stop (recorded by the compatibility runner — [COMPATIBILITY_MATRIX.md](COMPATIBILITY_MATRIX.md)).

## Google Play services dependency

Cast requires up-to-date Google Play services + Cast services on the device. The activity initializes
`CastContext` asynchronously through `getSharedInstance(context, executor)` and exposes only a completed
cached context to background work. If initialization fails or times out for a scan, Cast is unavailable
for that scan while **DLNA** remains usable; no `GoogleApiAvailability` fallback is claimed. Samsung/LG
TVs are generally **not** Cast targets — see [DLNA.md](DLNA.md).
