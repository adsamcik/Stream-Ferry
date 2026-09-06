# Physical-TV playback and recovery

This document describes the implemented simplified playback path. It is intentionally small enough
for a personal source-available project: no developer-operated device cloud, first-party telemetry
backend, compatibility database, background workflow engine, or claim of universal TV support.

## User flow

1. Choosing a playable movie or episode opens one physical-TV picker.
2. Tapping a TV starts playback. Cast versus DLNA is an internal endpoint choice.
3. The app enters one Now Playing route after the phone proxy service is foregrounded.
4. Connection, preparation, buffering, stream changes, reconnect, and protocol fallback update that
   same screen. They do not navigate back through the library or picker.
5. Exhaustion releases renderer/proxy resources but retains a stable error screen with **Retry**,
   **Change TV**, **Stop**, and expandable redacted attempt details.

The phone remains the only media gateway. Every renderer attempt receives a new ephemeral
phone-hosted proxy URL. No Jellyfin URL, token, authentication header, upstream playlist/segment URL,
subtitle URL, artwork URL, proxy port, or live transport handle is persisted or sent directly to a TV.

## Physical-TV aggregation

`physical.PhysicalTv` is a picker/session model above the existing Cast and DLNA discoveries. It owns
at most one endpoint for each protocol plus the last-successful/preferred protocol. It is not a global
device-management subsystem.

Stable endpoint keys use only documented discovery identities:

- Cast: the public Cast device ID; public model and address information can support the matcher.
- DLNA: UDN/USN; the validated SSDP source/description host and parsed manufacturer/model can support
  the matcher.
- A route ID, display name, host address, proxy address, or temporal co-discovery alone is not a
  persisted device identity.

The pure matcher returns a machine-readable outcome and reasons:

| Evidence | Outcome |
| --- | --- |
| Previously persisted stable Cast↔DLNA association, unless user-blocked | Confident |
| Shared validated host plus the same sufficiently specific model | Confident |
| Shared host without independent model evidence, or conflicting models | Ambiguous |
| Same name/room only | No match |
| Different validated hosts | No match |
| Cast model indicates a separate Chromecast/Google TV dongle | No match |
| One endpoint has several otherwise-confident candidates | Ambiguous |
| User previously unlinked the exact pair | No match |

Only mutual one-to-one confident pairs are merged. A duplicate row is preferable to controlling the
wrong screen. A confident pair becomes a durable stable-ID association only after the renderer reports
PLAYING. The same event records the successful protocol. **These are separate devices** removes the
pair and stores a negative association so a later scan does not recreate it.

Manual linking of ambiguous devices is deliberately deferred. It would require a careful confirmation
surface and offers less value than keeping harmless duplicates.

## Attempt and session model

The existing `PlaybackEngine` remains the single orchestrator. Each load is represented by a bounded,
redaction-safe `PlaybackAttemptDescriptor`: protocol, source kind, direct/server/on-device route,
known codec/container and capability summary, start position, audio/subtitle indices, generation,
automatic-recovery kind, reason, and classified failure. It never contains a title, media ID, usable
address, URL, token, or raw exception.

The authoritative phase is one of connecting, preparing, loading, waiting for playback, playing,
paused, buffering, reconnecting, changing stream, changing protocol, stopped, completed, or failed.
Attempt generations increase monotonically. Replaced controllers and stale watchdog/recovery work are
ignored. One recovery coroutine is admitted at a time; Stop invalidates the session and cancels its
work. Attempt history is a six-entry redacted ring.

Remote Jellyfin and local/downloaded media share this high-level orchestration and secure proxy/session
lifecycle. They keep separate low-level providers where their capabilities differ.

## Bounded recovery

Recovery is a finite policy, not an open-ended search:

1. An initial Google Cast or DLNA connection failure retries that endpoint twice. If both retries fail,
   a confidently paired alternate endpoint for the same TV is tried automatically and receives its own
   two-retry allowance if its first connection also fails. Per-protocol counters survive the handoff, and
   the single-use protocol reservation prevents switching back and cycling when both services are broken.
2. A transient error after the renderer connects retries the same stream once. It does not transcode merely
   because the network failed.
3. Direct-play startup incompatibility may use one conservative Jellyfin server transcode.
4. A failing server transcode may step down through meaningful resolution caps while budget remains.
5. The existing admitted local-file Cast transcode may try another phone/receiver-compatible codec.
6. After same-endpoint work is exhausted, one alternate endpoint of the same confidently merged TV may
   be reserved and tried. The reservation itself is single-use, so cancellation before load cannot cycle.
7. Otherwise the session becomes terminal on Now Playing.

Budgets are deliberately modest:

- connection recovery: 2 retries independently for Cast and DLNA;
- established-stream network recovery: 1;
- compatibility/quality/protocol variants: 3 shared;
- alternate protocol: 1 total;
- total automatic attempts: at most 6 (normally fewer because only eligible candidates are generated).

The latest renderer-confirmed position is used for every reload and protocol handoff. User-selected
audio/subtitle source indices are retained across a protocol handoff when the source still offers them.
Typed Jellyfin/source/phone-gateway preparation failures do not trigger a transcode or protocol switch;
changing the renderer cannot repair an unavailable source. Attempt failure stages and causes remain
coarse and redacted because receiver error taxonomies vary substantially.

## Smart Resume v2 and playback history

The app-private store keeps media identities from the rolling last 90 days, with a generous defensive
count ceiling for corrupt or abusive input. Each record contains only reconstruction data: source type
and stable media identity, server/user identity where relevant, display title/subtitle, duration,
renderer-confirmed position, completion state, stable physical-TV/endpoint identity, last successful
protocol, update time, record version, session generation, and sequence. The previous single-record
document migrates into the history envelope in place. Version 1 records migrate with empty device fields;
malformed or unknown versions are discarded safely.

Smart Resume preserves the existing generation, stale-write, sequence, seek-regression, and completion
guards. It checkpoints confirmed start/progress/seek, pause, disconnect, failure, lifecycle stop, explicit
Stop, and completion. A completed record cannot be resurrected by delayed callbacks. The gallery keeps
only the existing latest resumable entry prominent; the complete recent list lives under **Settings →
Playback history**, where it can be searched by title, subtitle/episode text, or source and completed
entries restart from the beginning.

For Jellyfin media, resume uses the newer of the local renderer-confirmed checkpoint and the newly
resolved server resume point. Local/downloaded resume similarly reconciles the legacy local position.
After restart, the app performs one bounded Cast/DLNA scan. It automatically reuses the previous TV only
on an exact stable physical or endpoint identity; names are display hints only. If the TV is unavailable,
the picker stays open with the previous name and the resume record remains intact. A new proxy and
renderer session is always created.

## Optional night volume

Night volume is off by default and stored locally. It runs only while playback is active and discovery
validated volume control for the selected endpoint.

- **Gradual:** captures this playback session's effective starting volume and interpolates toward a
  configured target between local start/end times (including windows crossing midnight).
- **Hard:** sends at most one reduction when an already-active session crosses the configured local time.
- Commands are reduction-only, require at least a 3 percentage-point difference, and are at least five
  minutes apart. No morning volume increase is performed.
- An in-app or media-session manual volume action suspends automation for the remainder of the session.
- Scheduler state survives automatic protocol handoff, preventing duplicate commands, but resets for a
  fresh user playback. Settings survive process recreation. Time calculations use `java.time` and the
  phone's current zone, with unit coverage for midnight and DST gap/overlap behavior.

Changes made directly with a physical TV remote cannot be observed consistently across Cast and DLNA;
they are therefore not claimed as a detectable manual override.

## Validation status

| Area | Status |
| --- | --- |
| Matching outcomes, false-merge guards, persisted link/unlink and selection | Unit-tested |
| Attempt deduplication, finite budgets, stale generations, Stop, one protocol reservation | Unit-tested |
| Network/format/lower-resolution policy | Unit-tested |
| Playback-history migration/bounds, reconciliation, device identity, completion protection | Unit-tested |
| Gradual/hard/sparse/reduction-only/manual/DST volume decisions | Unit-tested |
| Android integration and Compose code | Compiled by `assembleDebug`; see the verification report for the exact run |
| Cast, Google TV, Samsung, LG, and dual-protocol behavior | Hardware-dependent; not verified by unit tests |

### Manual community/device checklist

Record phone model/Android version, app commit, TV model/firmware, network topology, source format, and
whether first frame, controls, confirmed resume, cleanup, and diagnostics behaved as expected.

- [ ] Chromecast / Google Cast: direct play, server fallback, seek, pause, volume, Stop.
- [ ] Google TV: same checks; confirm an integrated TV and separate dongle are not falsely merged.
- [ ] Samsung DLNA: discovery, load/first frame, polling, seek, pause, volume when RenderingControl exists.
- [ ] LG DLNA: the same, including reload/Stop ordering.
- [ ] One TV exposing Cast and DLNA: one row only when evidence is confident; force a failure and verify
      alternate protocol resumes at confirmed progress.
- [ ] Initial Cast and DLNA connection failures: starting with either protocol, verify two bounded retries,
      then automatic handoff on a confidently paired TV. If the alternate also fails, verify its two retries
      end terminally without switching back; source/load failures must not be mislabeled as connection retries.
- [ ] Separate Chromecast attached to a DLNA TV: separate rows; neither endpoint controls the other.
- [ ] Temporary Wi-Fi loss: one same-endpoint retry; no unnecessary transcode; Stop prevents later work.
- [ ] Incompatible original media: conservative server transcode, then lower resolution when eligible.
- [ ] App/process restart: Resume uses the newer checkpoint; exact previous TV auto-reuses when present;
      an offline previous TV leaves the record and picker usable.
- [ ] Gradual volume: sparse reductions only, no increases, manual phone adjustment suspends the session.
- [ ] Hard volume: one command at the configured time, no duplicate after protocol handoff, no morning raise.

Do not convert a successful unit/compile run into a hardware compatibility claim. Add dated, model-specific
manual results to `COMPATIBILITY_MATRIX.md` when community hardware is actually exercised.

## Deliberate deferrals and limits

- Remote Jellyfin-to-phone transcoding is not implemented. The router now represents seekability,
  reopenability, streamability, and server/client transcode capability, but Jellyfin has no authenticated,
  origin-pinned, seekable client-transcoder input provider. Seeking, thermal/encoder admission, muxing,
  cleanup, and physical receiver validation remain required. Jellyfin server transcoding is preferred.
- Manual linking of ambiguous TVs, generalized device fingerprinting, invasive scans, reflection/private
  Cast APIs, analytics, remote configuration, and compatibility databases are out of scope.
- Local phone transcoding remains the existing narrow Cast HLS/fMP4 hardware path. It is not a general
  codec platform and is not promised for DLNA, HDR/Main10, CPU encoding, or every phone/receiver.
- Device discovery and receiver control remain subject to OEM firmware, router isolation, VPN routing,
  and Cast/DLNA implementation differences. Failures should be reported with redacted diagnostics and
  the model-specific checklist above.
