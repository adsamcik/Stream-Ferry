# Local Media & Multi-Source Gallery

Stream Ferry browses and casts from multiple **sources** behind one gallery. Today there are two —
the **Jellyfin** server and **on-device local files** — and the design is extensible to more.

## The `MediaSource` abstraction

`com.adsamcik.streamferry.domain.MediaSource` is the single browse contract the UI talks to:

```
roots() / children(parentId) / item(itemId) / search(query)  +  id, displayName, capabilities
```

- `JellyfinMediaSource` (`data.jellyfin`) wraps the existing `MediaLibraryRepository`.
- `LocalMediaSource` (`data.local`) enumerates on-device videos.

`MediaItem` carries a `sourceId` (`MediaSourceIds.JELLYFIN` / `LOCAL`; defaults to Jellyfin so existing
Jellyfin code is unchanged). The `MainViewModel` routes browse / search / detail / play through the
**active** source (`AppUiState.activeSourceId`); the gallery shows a source switcher at the top.

`capabilities` is the pure-JVM `core.transcode.SourceCapabilities`:

| Source | `canServerTranscode` | `isSeekable` | Incompatible-codec path |
| --- | --- | --- | --- |
| Jellyfin | `true` | `true` | server-side transcode (existing) |
| Local | `false` | `true` | **on-device transcode** (see below) |

## Local access is user-elective

The on-device source never forces a broad permission. The user chooses the access level:

- **SAF (default, zero permissions):** the system folder picker (`OpenDocumentTree`) or file picker
  (`OpenDocument`). The app takes a **persisted URI grant** and remembers it in `LocalSourceStore`
  (only the URI strings; the read permission is held by the system). Granted folders are enumerated
  via `DocumentsContract`; granted files are listed directly.
- **Optional `READ_MEDIA_VIDEO`:** if the user taps "Allow all videos", a synthetic **"All device
  videos"** folder lists every video via `MediaStore`. Denial silently falls back to SAF. This is the
  one media-library permission the app declares, and it is off by default
  (see [MANIFEST_PERMISSIONS.md](MANIFEST_PERMISSIONS.md)).

Video listing uses `core.local.LocalMediaRules` (extension/MIME filter, filename→title, container
MIME). Gallery thumbnails are produced on the phone via `ContentResolver.loadThumbnail` (cached,
no new dependency) and shown in the phone UI only.

## Casting a local file

A local `MediaItem.id` **is** its `content://` URI. To cast it:

1. `MainViewModel.play()` routes local items to `PlaybackEngine.playLocal(uri, mime, …)`.
2. The session coordinator registers a proxy session whose `localFilePath` is the `content://` URI.
3. `LocalProxyServer` opens the URI on the phone via the injected `ContentResolver`
   (`openFileDescriptor` → seekable `FileChannel`) and serves it with **full HTTP byte-range / seek**.

**Security invariant (unchanged):** the TV only ever receives the phone proxy URL. The `content://`
URI, like the Jellyfin URL/token, is opened on the phone and never sent to the TV. The proxy only ever
opens the URI fixed on the session — never a request-supplied path.

## Resume + reliability

Local files have no server-side resume point, so the app keeps a small local **`ResumeStore`**
(`data.resume`) of per-item positions (keyed by the `content://` URI, or `dl:<id>` for a downloaded
copy), governed by the pure `core.resume.ResumePolicy` (don't resume in the first few seconds or once
effectively finished). The position is saved periodically while casting and on stop; the media detail
shows a "Resume from …" hint and playback continues from there.

On-device local sessions also participate in the same **auto-reconnect**: if the TV connection drops,
the app retries with bounded (capped) backoff and resumes at the last position, and the CPU + Wi-Fi
locks are held for the whole session so multi-hour, screen-off playback keeps working.

## Incompatible codecs → experimental on-device transcode

When a local file's codec/container cannot be played by the target, there is no server to transcode it.
The phone can offer a limited hardware-accelerated fallback **only to a compatible Cast receiver**. It
publishes a seekable VOD HLS playlist with fMP4 fragments: H.264/AAC is the compatibility floor and
HEVC/AAC is selected only when both phone and receiver admit it. `PlaybackEngine.playLocal` probes the
file (`core.transcode.PlaybackRouter` → `RouteKind.CLIENT_TRANSCODE`, `TranscodeNegotiator`,
`core.hls.MediaPlaylistPlanner`, `core.transcode.Fmp4Splitter`) and routes to direct play or that
on-device path.

The phone does not produce DASH or MPEG-TS here, has no CPU/AV1/VP9/Main10/HDR contract, and does not
send this output to DLNA. It remains device-validation-dependent rather than a general local-media
compatibility guarantee. See [ONDEVICE_TRANSCODE.md](ONDEVICE_TRANSCODE.md).
