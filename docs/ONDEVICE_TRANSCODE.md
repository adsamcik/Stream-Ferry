# On-Device Transcoding

When a video the TV can't decode comes from a source with **no server to transcode** (a local file),
the phone transcodes it itself, hardware-accelerated, into a **seekable, phone-hosted HLS/CMAF origin**.
The TV still only ever receives the phone proxy URL — the security invariant is fully preserved (and
strengthened: no Jellyfin transcode session is involved).

For Jellyfin sources the server still transcodes (preferred). When the user opts in (**Transcode online
videos on this device**), the same machinery is also a **last-resort fallback** for Jellyfin sources —
see "Multi-codec negotiation & the fallback chain" below.

## Multi-codec negotiation & the fallback chain

Codecs supported end-to-end: **H.264, HEVC, VP9, AV1** (server transcode and on-device where the phone
has an encoder). The pure `com.videobridge.core.transcode.PlaybackPlanner` expresses the strategy: pick
the best **quality band** the TV can play (Native → Max → Very High → High → Standard → Low), preferring
direct play, and satisfy it with the best **engine** in priority order:

```
direct play  >  server transcode  >  on-device hardware  >  on-device CPU (software)
```

with the most efficient codec the TV supports (AV1 > HEVC > VP9 > H.264); STANDARD/LOW pin to H.264 as
the universally-decodable floor. At runtime the engine realises this via:

- **Server side** — `DeviceProfiles` offers the best TV-supported codec first (AV1/VP9/HEVC/H.264), so a
  forced transcode preserves 4K/10-bit/quality; H.264 stays the capped fallback.
- **Escalation** — `decideRecovery` walks direct → server transcode → (opt-in) **on-device transcode of
  the Jellyfin original** on a decode/format failure. Separately, if a **server transcode keeps
  rebuffering at the lowest quality rung** (adaptive bitrate has nothing left to give — the server can't
  keep up), the playback monitor escalates to on-device transcode automatically (opt-in, one-shot, only
  after playback started). Both use `reloadAsOnDeviceOnlineTranscode`, which resolves the untouched
  original (`DeviceProfiles.forOriginalDirectStream`) and feeds it to `OnDeviceTranscoder` with the
  Jellyfin Authorization header attached to the **source request only** — the original URL/token stay on
  the phone; the TV still only ever gets the proxy URL.
- **Encoder probing** — `MediaCodecCapabilityProbe` reports each codec's hardware encode tier plus which
  codecs have a software encoder (the opt-in CPU band, bounded to 720p).

**Preferences** (Settings): *Transcode online videos on this device* (opt-in), *Allow CPU on-device
transcode* (hardware-only by default). **Manual controls** (playback quality card): a **Quality** picker
(Auto + bitrate rungs) and a **Codec** picker (Auto + the codecs the TV can accept) re-resolve live.

> Experimental: realtime on-device transcode of a **remote** 4K source is device- and network-dependent;
> AV1/VP9 hardware encoders are rare and software/CPU transcode rarely keeps up above low resolutions, so
> negotiation simply skips engines the phone can't sustain. A failure surfaces (it is not retried).

## Why HLS/CMAF (not raw frames, not whole-file)

Google Cast is **receiver-fetches-media**: the receiver loads a URL from the phone's LAN IP. So the
phone is a realtime transcoder **plus** a local HTTP origin — never a raw-frame pusher, and the TV is
never handed a `localhost`/`content://` URL. A **seekable VOD HLS playlist** (the whole runtime is
advertised) lets the receiver seek anywhere; the phone transcodes the requested segment on demand.

## Pipeline

```
content:// source ──▶ Media3 Transformer (HW decode → Surface/GL → HW encode → fMP4) ──▶ fragment bytes
        per segment [startMs,endMs)                                                         │
                                                                                            ▼
                                          Fmp4Splitter → init segment (ftyp+moov) + media segment (moof+mdat)
                                                                                            │
   TV ◀── http://PHONE_LAN_IP:PORT/session/<id>/stream(?seg=init | ?seg=N) ◀── LocalProxyServer ◀──┘
```

- **`OnDeviceTranscoder`** (`data.transcode`) — AndroidX **Media3 Transformer**. Each call transcodes a
  clip `[startMs, endMs)` (via `ClippingConfiguration`) to a **fragmented MP4** using
  `InAppFragmentedMp4Muxer` + the device's HW encoder (H.264/AAC, optionally HEVC). The output height is
  **capped to the negotiated tier** with a `Presentation.createForHeight(...)` video effect, so a source
  taller than the phone's encoder can handle (e.g. a 4K file negotiated down to 1080p) is scaled down
  instead of being handed to the encoder at full resolution (which fails). Transformer runs on a
  dedicated `HandlerThread` Looper. Media3's `@UnstableApi` opt-in is confined here.
- **`core.transcode.Fmp4Splitter`** (pure, unit-tested) — splits each transcoded fragment into the
  shared CMAF **init** (`ftyp`+`moov`) and a bare **media** segment (`moof`…). One `EXT-X-MAP` init
  serves every segment because the encoder config is identical across segments. **Timeline continuity:**
  each segment is an independent clip transcode, so its encoder resets the media timeline to ~0; if served
  as-is, every segment claims to start at t=0 and the renderer can't sequence them (Cast never leaves
  LOADING; DLNA loops a few frames). `applyMediaTiming` therefore rewrites every `moof/traf` `tfdt`
  baseMediaDecodeTime to the segment's true cumulative offset (in each track's `mdhd` timescale,
  preserving intra-clip multi-fragment gaps) and stamps the `mfhd` sequence number, producing one
  continuous, monotonic timeline. `trackTimescales` reads the per-track timescale from the init `moov`.
- **`core.hls.MediaPlaylistPlanner`** (pure, unit-tested) — builds the **seekable VOD** playlist and
  maps a seek position to a segment index (the full-seek mechanism).
- **`ClientTranscodeSession`** (`data.transcode`) ties these together with a small LRU segment cache;
  transcoding is serialized (one HW encode at a time).
- **`LocalProxyServer`** serves the session: no `seg` → playlist; `seg=init` → init; `seg=N` → media
  segment N (transcoded on demand).

## Capability negotiation (4K is negotiated, never assumed)

`PlaybackEngine.playLocal` decides direct-play vs transcode:

1. **`LocalMediaProbe`** (`MediaExtractor`) → a `MediaProfile` (codecs, resolution).
2. `core.stream.StreamSelectionService` + `core.transcode.PlaybackRouter` →
   `RouteKind.DIRECT_PLAY` or `CLIENT_TRANSCODE` (local sources can't server-transcode).
3. If transcoding, `core.transcode.TranscodeNegotiator` picks the `TranscodeTarget` by gating BOTH the
   phone's HW encoders (`MediaCodecCapabilityProbe` → `DeviceEncodeCapabilities`) and the receiver,
   following the ladder **HEVC-4K → H.264-4K → 1080p → 720p** (all + AAC). Default is 1080p H.264/AAC;
   HEVC is only ever packaged as fMP4/CMAF, never MPEG-TS.

If any probe/negotiation step fails, playback falls back to direct play.

## Security

- The TV receives only `…/session/<id>/stream` proxy URLs (playlist + opaque `?seg=` segments).
- The `content://` source is opened on the phone; its URI is never sent to the TV.
- No Jellyfin URL/token is ever involved in the local-transcode path.

## Encoding defaults & limitations

- ~2 s segments; one HW encode at a time; recent segments cached (LRU).
- Initial target set: H.264/AAC (broadest Cast support), HEVC where both sides support it. Resolution
  capping via `Presentation` effects and 4K30/thermal tuning are follow-ups.
- **Device-validated:** the Media3/codec/GL pipeline cannot be unit-tested in the build sandbox; it is
  validated on real hardware. The pure-JVM pieces (`Fmp4Splitter`, `MediaPlaylistPlanner`,
  `TranscodeNegotiator`, `PlaybackRouter`) are unit-tested.
