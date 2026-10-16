# On-device transcoding

All supported video sources and receiver protocols share the phone's Media3 hardware encoder.
Playback options → **TV output → Transcoding → On device** selects phone conversion for gallery/SAF
files, downloaded files, and online provider media, on both Google Cast and DLNA. Format and resolution
controls then negotiate the phone encoder and receiver together. **Original** requests direct playback;
**Auto** retains the normal direct/server selection for online media and the saved phone-conversion
preference for local media.

## Shared pipeline

1. Obtain readable media: local file/content URI, or an opaque input URL from the phone proxy. Online
   input uses the provider's existing stream lease, including authentication, refresh, verified ranges,
   and HLS reference rewriting. The encoder never receives provider credentials or private URLs.
2. Select H.264 or HEVC with AAC and a resolution admitted by both endpoints. Apply the output height
   as a ceiling, preserving aspect ratio and avoiding upscaling when source dimensions are known.
3. Use the same `OnDeviceTranscoder` reservation, hardware encoder, cancellation, and error handling.
   HDR input is tone-mapped to SDR through Media3's OpenGL path; the phone does not promise Main10 output.
4. Publish receiver-compatible bytes through an opaque phone proxy URL:
   - Cast with HLS support can request SDR clips on demand from a seekable local progressive source with an
     exact duration. Each independent fragmented MP4 export has its own initialization and discontinuity.
   - Online provider media, DLNA, non-HLS receivers, HDR, nonseekable input, or input without an exact duration use a completed
     MP4. Conversion finishes before playback starts; progress is shown while preparing. The completed
     file supports normal byte ranges and renderer seeking.

The MP4 fallback uses private cache space and can take substantial time for a long video. Stop cancels
preparation before waiting for the playback command lock. Failed or stopped exports remove their owned
cache only after the encoder has stopped; a still-owned native output is retained rather than deleted.

## Source lifecycle and controls

Online input and renderer output have separate proxy sessions. Source Started/Progress/Stopped reports
and provider cleanup survive phone conversion. Encoder reads do not count as receiver throughput,
delivery progress, or evidence that the receiver rejected a format. Stop revokes both sessions.

Output changes preserve playback position and play/pause intent. A prepared MP4 uses renderer seeking,
not server re-resolution. Automatic server bitrate adaptation is disabled while phone output is active.
Existing source-selected audio/subtitle processing remains with the provider before phone encoding.

There is no source-type or Cast-only admission gate. Actual unsupported decoders, hardware encoders,
tone mapping, insufficient storage, or unreadable input surface a conversion failure. The current source
catalog provides finite movies/episodes and local videos; an endless live stream cannot finish a
whole-file MP4 preparation. AV1/VP9 encoding, CPU encoding, DASH, and MPEG-TS output are not implemented.

## Verification limits

Unit tests exercise routing, negotiation, authenticated input, provider lifecycle, cancellation, proxy
ranges, and output choices. Build/lint and compiled instrumentation tests do not establish physical
MediaCodec, thermal, Cast, DLNA, HDR colour, or sustained streaming behavior. Receiver and real-phone
validation remain separate.
