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

## Media load

The receiver receives **only the phone proxy URL** — never a Jellyfin URL or token. The `MediaInfo`
includes: phone proxy URL (content id/URL), correct MIME/content type, stream type, title, duration
if known, safe metadata, optional poster only if approved and proxied, subtitles only if served
through the proxy, and HLS only if rewritten through the proxy. No credentials in `customData`.
Server-provided title metadata is control-character normalized and capped by UTF-8 byte length before it
is sent to the receiver or shown in the notification.

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

## Tracks (test-required, not guaranteed)

- Text subtitles are treated as test-required; embedded / ASS / SSA / PGS are not assumed to work.
  PGS/ASS/SSA generally need server-side burn-in.
- Audio/video track switching is treated as unavailable in MVP unless verified on the target.

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
