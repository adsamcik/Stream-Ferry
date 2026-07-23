# Memory Buffer Policy

During **streaming/casting**, the phone buffers media **only in RAM**, only while playback is active.
Forbidden on the streaming path: full-file predownload, disk media cache, app cache/external storage
media payloads, HLS segment disk cache, subtitle disk cache, unbounded byte arrays/queues/channels,
and buffering an entire movie in memory. All limits are bounded constants in
`core.buffer.MemoryBufferPolicy` (unit-tested).

> **Opt-in exception — offline downloads.** The separate, **explicitly user-initiated** download
> feature (Settings/Detail → "Download for offline") is the *only* path that persists media to disk.
> It writes the original file to **app-private internal storage** (`filesDir/downloads/`, sandboxed
> and excluded from backup), with a filename derived only from the (alphanumeric) item id
> (`core.download.DownloadPaths`, no path-traversal surface), and never stores the Jellyfin token in
> its metadata. It is removed by "Delete all app data". Offline playback of a downloaded file is served
> to the TV by the proxy from the local file (the TV still only ever receives the phone proxy URL); the
> live streaming path above remains RAM-only and unchanged. A small **metadata** cache
> (`data.cache.LibraryCache`, library listings only — no media bytes, no token) similarly lives
> app-private and is cleared by "Delete all app data".

## Two strategies

1. **Pass-through (MVP, implemented first):** read an upstream chunk, forward it downstream, drop it.
   A small bounded in-flight buffer only.
2. **Rolling in-memory prebuffer (Phase 9):** a bounded read-ahead window with eviction, added only
   after pass-through works.

## Limits

| Parameter | Default | Hard cap |
| --- | --- | --- |
| Pass-through copy chunk | 64 KiB | — |
| Pass-through in-flight buffer | 2 MiB | 8 MiB |
| Rolling prebuffer window | 16 MiB | 64 MiB |
| Prebuffer target duration | 8 s | 20 s (max) |

The window in **bytes** is derived from observed bitrate × target seconds, then clamped to the hard
cap. For high-bitrate 4K/HDR content this means the window covers **fewer seconds** rather than
growing without bound.

## Memory-pressure behavior

`onMemoryPressure(freeHeapFraction, currentWindowBytes)` decides:

- free heap ≤ **12%** → **degrade to pass-through** (stop *prebuffering* — never the stream).
- free heap ≤ **25%** → **shrink** the window by half (never below the 2 MiB pass-through floor).
- otherwise → keep current.

Streaming is a user-initiated, **foreground-service-backed** activity (`ProxyPlaybackService`, type
`mediaPlayback`, holding CPU + Wi-Fi locks), so memory pressure **never stops the stream**: it only
reduces read-ahead *overhead* — the read-ahead window shrinks and, at the extreme, falls back to the
minimal-overhead pass-through mode, which still streams the same bytes at the same quality — before
OOM.

## Seek behavior

`seekServeableFromWindow(offset, windowStart, windowLen)` returns whether a seek target is inside the
current window. A seek **outside** the window evicts the old window and issues a fresh upstream byte
range request — **never** a disk read.

## Cleanup

On stop / error / session expiry: cancel upstream reads, clear all buffers, and revoke the session.
Buffers must be empty after playback (an MVP acceptance criterion). "Delete all app data" also clears
any active buffers after stopping playback.
