package com.videobridge.data.jellyfin

/**
 * DECISION RECORD — Jellyfin integration (§8).
 *
 * Context
 *   The official Jellyfin Kotlin SDK (org.jellyfin.sdk:jellyfin-core:1.8.11, LGPL-3.0) is verified
 *   present on Maven Central and is included in the version catalog. The app needs: manual server
 *   URL, username/password auth, playback-info with explicit stream-selection parameters,
 *   media-source resolution, PlaySessionId preservation, playback reporting and transcode/HLS
 *   cleanup.
 *
 * Decision
 *   Use the documented, stable Jellyfin HTTP API directly (OkHttp + kotlinx.serialization) for the
 *   playback path, while keeping the official SDK available for future auth/version helpers.
 *   Rationale:
 *     - The proxy needs the EXACT upstream stream URL + the precise PlaybackInfo parameters
 *       (DeviceProfile-driven DirectPlay/Transcode decision, PlaySessionId, MediaSourceId, audio/
 *       subtitle stream indexes). Driving these explicitly against the documented endpoints keeps
 *       the contract auditable and avoids relying on SDK internal URL construction whose output we
 *       must redact and proxy.
 *     - It avoids guessing SDK method signatures (the task forbids inventing APIs).
 *
 * Endpoints used (all from the official Jellyfin OpenAPI; verify against your server version):
 *   - POST /Users/AuthenticateByName          (auth; returns AccessToken + User.Id)
 *   - GET  /System/Info/Public                (version detection; pre-auth)
 *   - GET  /Users/{userId}/Views              (video libraries)
 *   - GET  /Items?ParentId=...&UserId=...     (browse; paginate with StartIndex/Limit — drive the
 *                                              full-library walk with core.resilience.LibraryPagingPolicy
 *                                              so even a large library over a spotty link loads
 *                                              reliably instead of timing out on one huge request)
 *   - POST /Items/{itemId}/PlaybackInfo       (playback-info; body carries a DeviceProfile +
 *                                              MediaSourceId/AudioStreamIndex/SubtitleStreamIndex/
 *                                              MaxStreamingBitrate/StartTimeTicks; returns
 *                                              PlaySessionId + MediaSources[].{TranscodingUrl,
 *                                              DirectStreamUrl, SupportsDirectPlay,...})
 *   - GET  /Videos/{itemId}/stream(.ext)      (direct/remux upstream; static=true for direct)
 *   - {TranscodingUrl}                        (HLS/transcode upstream returned by PlaybackInfo)
 *   - POST /Sessions/Playing                  (report start)
 *   - POST /Sessions/Playing/Progress         (report progress)
 *   - POST /Sessions/Playing/Stopped          (report stop)
 *   - DELETE /Videos/ActiveEncodings?...      (stop transcode; PlaySessionId + DeviceId) — cleanup
 *
 * Auth header (documented "Emby Authorization"):
 *   Authorization: MediaBrowser Client="Video Bridge", Device="<model>", DeviceId="<stable-id>",
 *                  Version="<appVersion>", Token="<accessToken>"
 *   The Token (and the whole header) is a secret: held only in memory / Keystore, never logged or
 *   sent to the TV (the proxy strips it and substitutes a phone session URL).
 *
 * NOTE: The DeviceProfile sent in PlaybackInfo encodes the *target TV's* capabilities (from
 *   StreamSelectionService), NOT the phone's, because the phone only proxies bytes and the TV is the
 *   real renderer. This is what makes Jellyfin pick a TV-compatible DirectPlay/Transcode result.
 */
internal object JellyfinApiContract
