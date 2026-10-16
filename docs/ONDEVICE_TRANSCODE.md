# On-device transcoding

All supported sources use one continuous phone encoder on both Google Cast and DLNA.
Playback options → **TV output → Transcoding → On device** enables conversion during playback.
Format and resolution choices negotiate the phone encoder and receiver together; changes preserve
position and play/pause intent. **Original** requests original media, and **Auto** keeps the usual
source selection with a bounded phone fallback if server conversion fails.

## Streaming pipeline

1. Open a local file/content URI or an opaque provider input URL. The existing provider lease retains
   authentication, refresh, range verification, and HLS reference rewriting. Credentials and private
   source URLs stay on the phone.
2. Start one Media3 Transformer decode/encode lifecycle at the requested source position, producing
   H.264 or HEVC video with AAC. The output height is capped without upscaling known source dimensions.
   HDR input uses the shared OpenGL HDR-to-SDR path. B frames are disabled, and keyframes are requested
   every two seconds.
3. Publish encoded media as it is produced:
   - **Cast:** short fragmented-MP4 HLS segments, with one initialization segment and a growing/sliding
     media playlist. Playback starts after the first media fragment is ready; ENDLIST appears only
     when encoding reaches source EOF.
   - **DLNA:** continuous MPEG-TS packets over an unknown-length HTTP response. PAT/PMT, PCR, PES
     timestamps, Annex B H.264/HEVC and ADTS AAC are emitted while encoding. No Content-Length or byte
     seeking is advertised. HEAD probes do not open or consume the stream.

No source waits for whole-video conversion, and no completed output file is required. Unknown-duration,
HLS, nonseekable and HDR inputs use the same pipeline. The current source catalog exposes finite local
videos and provider movies/episodes; the continuous lifecycle itself does not require a known runtime.

## Buffering, seeking, and lifecycle

Bounded queues apply receiver backpressure to the encoder. HLS retains up to eight segments within a
24 MiB budget, using the receiver cursor and a small retry window; skipped startup segments cannot
permanently block production. DLNA has a 2 MiB producer queue and a bounded startup replay buffer for
receivers that probe bytes then reopen the URL. A later unsupported reconnect fails explicitly rather
than serving an arbitrary midstream byte as the beginning.

All phone output is rebased to renderer time zero at the requested source position. App seeking and
format/resolution changes restart the streaming encoder there, rather than waiting for prior content
to be encoded or claiming byte access to future output. Source watch progress remains absolute.
Stop wakes blocked readers/writers before cancelling and draining only that session's encoder.

Online input and output use separate opaque proxy sessions. Provider Started/Progress/Stopped reporting
survives phone conversion, and Stop revokes both sessions. Encoder reads never count as receiver
throughput, playback progress, or evidence of a receiver format failure. Server bitrate adaptation is
disabled while phone output is active. Existing provider audio/subtitle selection remains source-owned.

## Verification limits

Unit tests cover first media before export completion, bounded output/retry behavior, authenticated
input, source reporting, playback controls, timestamp rebasing, and TS packet structure. Independent
FFmpeg decoding can validate packaging but does not exercise Android hardware. Builds, lint and compiled
instrumentation tests do not establish physical MediaCodec speed, colour, thermal behavior, Cast/DLNA
interoperability or rendered UI. Those require real-phone and receiver validation.
