# ADR: online and CPU phone transcoding removed

## Decision

Stream Ferry does not transcode online Jellyfin media on the phone and does not offer a CPU/software
live-transcode fallback. Online media uses direct play, remux, or Jellyfin server transcoding. The sole
phone-transcoding exception remains the experimental, local-file-only, Cast HLS/fMP4 path using admitted
hardware H.264 or HEVC encoding.

## Rationale

Forwarding Jellyfin credentials into a generic remote Media3 source could leak them across redirects. The
online path also duplicates Jellyfin's server-side transcoding and adds significant protocol, thermal, and
support complexity. CPU encoders have no demonstrated real-time floor across devices and are particularly
unsuitable for a live phone-to-TV gateway.

## Reconsideration

A future proposal must be separate from the removed feature. It needs an authenticated origin-pinned
remote input design, an explicit receiver/output contract, measured real-time and thermal admission, and
physical validation across supported phones and Cast receivers.
