# Stream Selection

The phone **does not transcode**. It only proxies bytes. Therefore the app must ask Jellyfin for a
stream the **target TV** can play, encoding the TV's capabilities (not the phone's) into the Jellyfin
`DeviceProfile` / PlaybackInfo request and choosing among Jellyfin-provided media sources.

## How transcoding is actually requested (implemented)

The live path drives Jellyfin with an **accurate DeviceProfile built from the target's real
capabilities** (`data.jellyfin.DeviceProfiles.forTarget`), then lets the server decide direct-play vs.
transcode and returns the matching media source. The phone never transcodes; it only proxies the
server's chosen stream.

- **DirectPlayProfiles** list only codecs/containers the target actually supports (so we never
  advertise a format the TV can't decode). HEVC/H.265 is dropped unless the target advertises it; a
  `VideoBitDepth <= 8` `CodecProfile` is added when the target isn't 10-bit/HDR capable, so HDR
  sources are transcoded to SDR.
- **TranscodingProfiles** are protocol-appropriate:
  - **Cast** (supports HLS): an **HLS** H.264/AAC profile. The proxy fetches the playlist, rewrites
    every URI to a phone proxy `…/stream?seg=<opaque>` URL (`core.hls.HlsRewriter` +
    `core.hls.HlsSegmentRegistry`), and proxies each segment/key/subtitle — so the TV only ever sees
    phone proxy URLs and the Jellyfin token never leaves the phone.
  - **DLNA** (no HLS): a **progressive MPEG-TS** H.264/AAC profile served as a single stream through
    the resilient byte-range proxy.
- `forceTranscode` advertises no DirectPlay; `allowSubtitleBurnIn` adds `Encode` subtitle methods for
  ASS/SSA/PGS/DVD subs.

The target capability baselines live in the controllers (`CastTargetController` /
`DlnaTargetController`) and are conservative by default (correct-format first; per-renderer probing via
`GetProtocolInfo` is future work). The phone-side `StreamSelectionService` decision table below remains
available and unit-tested; the server-driven DeviceProfile is the authority for the live path.

## Direct-play first, with automatic transcode fallback (Cast & DLNA)

To avoid transcoding when it isn't needed, playback is **optimistic** (toggle: Settings → "Prefer
original quality", default on). For the first attempt the engine advertises a broad codec profile — for
Cast the explicit `PlaybackEngine.CAST_DIRECT_PLAY_CAPS` (HEVC/10-bit, MKV/WebM/TS containers,
AC3/E-AC3/Opus/FLAC audio, VP9/AV1); for DLNA the renderer's `DLNA_BASELINE` (H.264 + AAC/AC3/MP3 in
MP4/MKV/TS/AVI/MOV) — so Jellyfin **direct-plays or lightly remuxes** the original when the TV can decode
it (better quality, no server CPU, lower latency). If the renderer then reports it **can't decode** the
stream, the engine falls back **once** to the conservative profile + `forceTranscode` and reloads from
the current position (`forceTranscodeFallback`): Cast transcodes to HLS, DLNA to a progressive MPEG-TS
H.264/AAC stream every renderer can play.

Detecting that failure comes from several sources, all funnelled into one typed
`PlaybackTargetEvent.Error(kind, …)` and one handler:

- **Cast** rejecting the media does **not** reliably fire `RemoteMediaClient.Callback.onMediaError` — it
  often just parks in `PLAYER_STATE_IDLE` with `IDLE_REASON_ERROR`. `CastTargetController` raises `Error`
  for **both**, classifying the `onMediaError` `detailedErrorCode` (decode/unsupported → `FORMAT`, media
  network → `NETWORK`, else `UNKNOWN`). Missing the IDLE/ERROR path meant the fallback never ran and
  playback died silently (e.g. an MKV cast to an LG webOS receiver).
- **DLNA** renderers push nothing, so `DlnaTargetController` polls `GetTransportInfo` and raises a `FORMAT`
  `Error` when `CurrentTransportStatus=ERROR_OCCURRED` (the UPnP-standard "can't play that file").
- **Silent failures** — a renderer that never plays *and* never reports an error — are caught by a
  **startup watchdog**: if playback doesn't demonstrably start within a grace window it synthesizes a
  failure, and the proxy's *early-close* signal (the TV connected, read a little, then bailed) triggers it
  sooner. The heuristics are the pure, unit-tested `StartupWatchdog`.
- A **synchronous load failure** (e.g. a DLNA renderer rejecting the URI outright) is routed into the same
  recovery instead of hard-failing the UI (`startPlaybackWithFallback`).

The decision is the pure, unit-tested `decideRecovery` (see `PlaybackRecovery.kt`), applied uniformly to
Cast and DLNA:

- a `NETWORK` blip → retry the **same** stream once (transcoding wouldn't fix connectivity);
- a `FORMAT`/`UNKNOWN` failure on an **online**, direct-play session that isn't already transcoding →
  fall back once to a server transcode and reload from the current position;
- everything else (local file — no server; already transcoding; already recovered; direct play off) is
  **surfaced**.

An error that arrives **while a reload is already in flight** (a fallback, retry, HLS seek, or bitrate
switch) is from the dying old stream and is ignored, so it can't abort the reload or flash a spurious
error; the re-entry flags are set under a lock so the event collector, watchdog and proxy signal can't
double-fire. Finally, a successful fallback is **remembered per (renderer, source container)** in a
`RendererCapabilityStore`, so the next play of that format to that TV skips the doomed direct-play attempt
entirely. The TV still only ever receives the phone proxy URL — the broad profile only affects the
phone↔Jellyfin DeviceProfile.

## Decision order (per target)

1. **DIRECT_PLAY** — Jellyfin reports the source is directly compatible (container + video + audio +
   subtitle all supported by the target). Proxy bytes as-is.
2. **REMUX / DIRECT_STREAM** — compatible codecs, incompatible container; Jellyfin remuxes container
   only.
3. **AUDIO_TRANSCODE** — video compatible, audio not (e.g. DTS/TrueHD → AC3/AAC).
4. **HLS / FULL_TRANSCODE** — video and/or audio incompatible; Jellyfin transcodes to an
   HLS/transcoded stream (proxied + URL-rewritten through the phone — see [PROXY_DESIGN.md](PROXY_DESIGN.md)).
5. **SUBTITLE_BURN_IN** — only if required and allowed; treated as a server-side transcode.

Compatibility is **never inferred from file extension alone** (§9). The decision uses the
target's declared/known capabilities and Jellyfin's MediaSource flags.

`core.stream.StreamSelectionService` implements this decision (unit-tested), returning a
`StreamDecision` with the chosen mode and whether subtitle burn-in is needed.

## Inputs

- Protocol: Cast or DLNA.
- Target model/name if known; Cast receiver type; DLNA `GetProtocolInfo` hints (treated as a hint
  only).
- Source media: container, video codec/profile/level, bit depth, HDR, resolution, bitrate, audio
  codec/channels, subtitle format.
- Network conditions (VPN/remote/LAN), Jellyfin transcoding capability, selected audio/subtitle
  streams, user preference.

## User options (§9)

- Prefer compatibility / prefer quality.
- Force transcoding.
- Bitrate limit.
- Disable subtitles / allow subtitle burn-in.
- Resume / start over.
- **Maximum resolution** (`maxVideoHeight`: 2160/1080/720/480, default **2160**). Caps the resolution
  streamed to the TV for **every** codec via a codec-agnostic `Height <= max` CodecProfile, so the server
  downscales any taller source (direct play still applies when the source already fits). At the **4K
  (2160)** setting the app also does **HDR passthrough**: because H.264 can't carry HDR, the only way to
  get 4K HDR to the TV is HEVC **direct play** (or an HEVC transcode), so `effectiveCaps` advertises
  HEVC + 10-bit for direct play on **both** Cast and DLNA (a TV's native decoder can passthrough the
  original), the initial request cap is lifted to ~120 Mbps so a high-bitrate 4K HDR source isn't
  force-transcoded to fit, and `DeviceProfiles` offers an **HEVC transcode profile first** (preserving
  10-bit HDR) with H.264 as the compatibility fallback (`StreamPreferences.allow4kHdr` is derived as
  `maxVideoHeight >= 2160`). Below 4K, the compatibility-first path applies at the chosen height. If the
  target genuinely can't decode HEVC, playback falls back to the capped H.264 (8-bit/≤1080p) transcode. A
  **"Reset learned TV capabilities"** settings
  action clears `RendererCapabilityStore` so a previously-rejected format is re-attempted (e.g. after
  enabling this or a TV firmware update). Whether 4K HDR actually plays depends entirely on the receiver:
  many Cast "Default Media Receivers" cap at 1080p H.264, while a TV's native DLNA player usually decodes
  4K HDR HEVC.
- **Audio track selection** and **subtitle enable/disable + language selection** during playback
  (online Jellyfin sources). The playback screen exposes pickers built from the tracks Jellyfin reports
  in `PlaybackInfo` (`MediaTrack`s carried on `PlaybackInfo` → `PlaybackStatus` → `PlaybackUiState`).
  Selecting a track calls `PlaybackEngine.selectAudioTrack`/`selectSubtitleTrack`, which re-resolve the
  stream at the current position with the new `AudioStreamIndex`/`SubtitleStreamIndex` (like a seek).
  A selected subtitle is **burned into the video** (`DeviceProfiles` marks every subtitle format
  `Method:"Encode"` when burn-in is on) — the only way to reliably render a subtitle through the phone
  proxy on both Cast and DLNA, so enabling a subtitle forces a transcode. "Off" (the default) passes
  `SubtitleStreamIndex=-1`.
  Likewise, choosing a **non-default audio track** forces a server transcode
  (`AudioTrackSelection.requiresServerTranscode`): on direct play the TV receives the whole container and
  plays *its own* default track — the proxy can't switch an embedded audio stream on Cast/DLNA — so the
  server must mux the selected audio. Video is stream-copied where possible (`AllowVideoStreamCopy`), so
  this is usually an audio-only remux, not a full re-encode. Selecting the default track (or clearing the
  choice) direct-plays as before.
- **Preferred languages + per-show memory.** Settings offers a **preferred audio language** and
  **preferred subtitle language** (`PlaybackPreferences`, curated `core.language.Languages` list). When a
  show starts, the engine auto-selects the audio track (and, for subtitles, auto-enables the burned-in
  track) matching the preferred language, if the media has one — applied once, after the first resolve
  reveals the tracks, and only re-resolving when it differs from the server default (so the common case
  causes no reload). Matching normalizes ISO variants (`deu`/`ger`/`de`) via `LanguageMatcher`. The
  **last language the user picks for a show is remembered** (`ShowLanguageStore`, keyed by series id for
  episodes else item id, subtitles can be remembered as *off*) and **overrides** the global preference for
  that show — so the next episode plays in the same language. Resolution (per-show over global) is the
  pure `LanguagePreferenceResolver`; both are unit-tested. Local files are unaffected (no server tracks).

If no compatible stream exists, the UI shows an **actionable** error (e.g. "This TV can't play this
audio; enable transcoding or pick another track"), never a raw stack trace.

## Track limitations (must be tested, not assumed)

- Text subtitles (SRT/WebVTT) are **test-required**, not guaranteed; embedded/ASS/SSA/PGS are not
  assumed to work and PGS/ASS/SSA generally require server-side burn-in.
- **Subtitle** selection burns the chosen track into the video (server transcode), so it works on any
  target; **audio** track switching re-resolves via Jellyfin (which remuxes/transcodes to the chosen
  track). Both re-resolve at the current position. Text-subtitle *external* rendition (no burn-in) is
  intentionally not used through the proxy (a DLNA renderer can't fetch it).
- Codec probing from the Android sender alone is not claimed; real playback testing (load → first
  frame → 30 s → seek → pause/resume → stop) is mandatory and recorded by the compatibility runner
  (see [COMPATIBILITY_MATRIX.md](COMPATIBILITY_MATRIX.md)).

## No guessed URLs

Stream URLs are obtained from Jellyfin's official PlaybackInfo/MediaSource responses, not constructed
by guessing endpoints. The `PlaySessionId` is preserved and associated with the proxy and Cast/DLNA
sessions. Any unavoidable manual URL construction is documented with the exact official API reference
in `data.jellyfin.JellyfinApiContract`.
