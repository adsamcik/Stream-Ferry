# On-Device Transcoding

On-device transcoding is a **limited, experimental recovery path for local video files** that a
receiver cannot direct-play. When it is admitted, the phone hardware-encodes the local source and
hosts a seekable VOD HLS origin; the TV still receives only an opaque phone-proxy URL.

This is not a general phone-transcoding platform. The active path is **Cast-only** and produces
**HLS with fragmented-MP4 (fMP4) media**. It is not used for DLNA. Jellyfin server transcoding remains
the normal online-media path and is described separately in [STREAM_SELECTION.md](STREAM_SELECTION.md).

## Active phone-output contract

The phone path has a deliberately narrow contract:

- **Container/transport:** VOD HLS with fMP4 initialization and media fragments. The phone does not
  publish DASH or MPEG-TS output.
- **Video/audio:** H.264 + AAC is the best-effort compatibility floor. HEVC + AAC is considered only
  when both the phone's hardware encoder and the selected Cast receiver admit it.
- **Encoder:** hardware only. There is no active CPU/software encoder fallback.
- **Colour and profile:** output is 8-bit. There is no explicit Main10 or HDR conversion contract;
  HDR/10-bit local sources are rejected rather than silently changing colour treatment.
- **Not active in this path:** AV1 and VP9 encoding, DASH, MPEG-TS, CPU encoding, Main10, and HDR
  output. Types or planning code that model those choices are not a production phone-output promise.

The maximum resolution selected by negotiation is a capability-admission ceiling, not a guarantee of
realtime 4K operation, a fixed frame rate, a fixed bitrate, or sustained performance. The server-side
Jellyfin profile may have broader capabilities; do not infer them from this phone-output contract.

## Online Jellyfin media is server-transcoded

Online Jellyfin media is never transcoded on the phone. The supported online recovery chain is direct
play → Jellyfin server transcode → clear failure. This keeps authentication, redirect validation, and
media conversion with the Jellyfin server rather than turning the phone into a remote media gateway.

The local-file path is separate: it accepts only `content://` or `file://` sources and never exposes a
Jellyfin URL or token to the receiver.

## Runtime flow

```
local content URI/file ──▶ Media3 Transformer (hardware decode/encode) ──▶ fragmented MP4
                                                                                  │
                                                                                  ▼
                                               fMP4 splitter ──▶ init + media fragments
                                                                                  │
Cast receiver ◀── phone proxy HLS playlist / opaque segment URLs ◀── LocalProxyServer ◀──┘
```

- **`OnDeviceTranscoder`** uses AndroidX Media3 Transformer to export a requested clip to a
  fragmented-MP4 file. It requests H.264 or HEVC video and AAC audio, and applies the negotiated output
  height as a cap.
- **`Fmp4Splitter`** separates the initialization data from media fragments so the proxy can expose an
  HLS `EXT-X-MAP` plus opaque segment URLs. This is an fMP4 delivery implementation; it is not a claim of
  independently certified CMAF conformance across phones or receivers.
- **`ClientTranscodeSession`** serializes hardware exports and keeps a short in-memory segment cache.
  A segment failure is represented as an HTTP failure, never as a successful empty media segment.
- **`LocalProxyServer`** serves the master playlist, media playlist, initialization data, and on-demand
  media fragments. A `HEAD` probe does not need to start a hardware export.

The playlist advertises the full local runtime, allowing a compatible Cast receiver to request a
position on demand. It is not a raw-frame push or a whole-file pretranscode.

## Admission and fallback

`PlaybackEngine.playLocal` first attempts direct play. For an incompatible local file it considers the
phone path only when the selected receiver can play HLS/fMP4. `TranscodeNegotiator` then gates the
candidate on both receiver support and the phone's hardware-encoder capabilities, selecting H.264 or
opportunistic HEVC at an admitted height. If no candidate is admitted, the app declines phone
transcoding rather than advertising a different container or encoder path.

DLNA does not receive this HLS/fMP4 output. An incompatible local file on a DLNA renderer must be made
directly playable or played through a supported server-side route.

## Security

- The receiver receives only `…/session/<id>/stream` URLs and opaque segment identifiers.
- The local `content://` URI is opened by the phone and is never sent to the receiver.
- The local path carries no Jellyfin credential and does not accept a remote source.

## Validation status and limits

This path is not yet a production-reliability claim across Cast devices or OEM phones. It still requires
physical validation of sustained encode speed, thermal behaviour, random-access segment boundaries,
audio/video continuity, speculative receiver requests, receiver fMP4 handling, and HDR conversion
behaviour. The pure playlist/splitting/negotiation helpers can be unit tested, but they cannot establish
MediaCodec, Media3, network, or receiver interoperability on their own.
