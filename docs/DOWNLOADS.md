# Optional downloads + library cache

Two **opt-in** features that persist data on the phone. They are deliberately scoped so they never
weaken the streaming/security model: the live casting path stays **RAM-only** (see
[MEMORY_BUFFER_POLICY.md](MEMORY_BUFFER_POLICY.md)) and the TV still only ever receives the phone proxy
URL. Both stores live in **app-private internal storage** (sandboxed, excluded from backup by
`data_extraction_rules`) and are wiped by **Settings → Delete all app data**.

## Library metadata cache

`data.cache.LibraryCache` + `data.cache.CachingMediaLibraryRepository` cache the **metadata** of the
libraries/items you browse (titles, ids, years, overviews — *no media bytes, no token*) as JSON under
`filesDir/libcache/<serverScope>/`. Browsing fetches fresh when online (updating the cache) and falls
back to the cache when the server is unreachable, so you can still see your library offline and it
paints instantly. Keyed per server so different servers never mix.

## Offline downloads

`data.download.DownloadStore` + `data.download.MediaDownloader` save a title for later, offline
playback — in its **original** format or a **transcoded** copy of your choice:

- **Format choice (detail screen).** "Original quality" resolves the source via a broad direct-play
  `DeviceProfile` (no transcode). A transcode preset (`DownloadFormat.PRESETS`: MP4 1080p/720p/480p,
  H.264/AAC) forces a **progressive, single-file** server-side transcode via
  `DeviceProfiles.forDownload` (`DirectPlayProfiles:[]` + a `Protocol:"http"` MP4 transcoding profile),
  so the download is a plain byte stream (never HLS). The chosen quality is stored on the
  `DownloadEntry` (`qualityLabel`). The **filename is derived only from the alphanumeric item id**
  (`core.download.DownloadPaths`, unit-tested) — never from the title. The Jellyfin token is used only
  to fetch the bytes and is **never** written to the download index.
- **Runs in the background — and survives process death.** While any download is active,
  `data.download.DownloadService` — a foreground service of type **`dataSync`** (mirrors
  `ProxyPlaybackService`) — keeps the process alive and shows a progress notification, so downloads
  continue when the app is backgrounded / screen off. The download coroutines run on
  `AppContainer.ioScope` (process-lifetime, not the UI scope), and the service self-stops the moment no
  download is `Queued`/`Running`. It is `exported="false"` and started only from the in-app (foreground)
  download action (or restarted by the system, below).
  - Every requested-but-unfinished download is persisted to a small app-private queue
    (`data.download.DownloadQueueStore` → `downloads/queue.json`: item id, title, format — **never a
    token or URL**). On a kill (aggressive OEM battery management, low memory, or the Android 15+ `dataSync`
    runtime cap via `onTimeout`), the service is `START_STICKY`, so the system **restarts it and
    `MediaDownloader.resumePending()` continues the downloads** from their `.part` files — even before you
    reopen the app. Reopening the app also restarts the service if anything is still pending
    (`hasPendingPersisted()`). Queue entries are removed on completion/cancel/delete and cleared by
    "Delete all app data". The resume-selection (`DownloadQueue.selectResumable`) and format persistence
    (`PersistedFormat`) are pure and unit-tested.
- **Auto-recovers from transient errors.** A download does not give up on the first network blip:
  `MediaDownloader.runDownloadWithRecovery` retries **recoverable** failures (any `IOException`, or a
  retryable Jellyfin status — 408/425/429/5xx) with **progress-aware** exponential backoff
  (`core.resilience.Backoff`, base 1 s → cap 30 s). The consecutive-failure counter **resets whenever an
  attempt moved `.part` bytes forward**, so a long download over a flaky link (or a mid-download Wi-Fi ⇄
  cellular switch) keeps going instead of being bounded by one global budget. **Permanent** failures
  (401/403/404/410/416, or a non-downloadable / HLS-only title) are classified by
  `DownloadQueue.isRecoverableFailure` and are **not** retried — they surface as a `Failed` state (with
  the parsed, redacted server reason) and their queue entry + partial file are removed so they never
  loop on relaunch. When connectivity returns, a `ConnectivityManager` default-network callback in
  `AppContainer` re-enqueues anything still pending, so even a download that exhausted its in-flight
  retries while offline resumes by itself the moment the network is back.
- **Resumable (Original only).** Bytes are written to a `<id>.part` file and renamed to the final name
  on completion. An interrupted **Original** download **resumes** from where it left off via an HTTP
  `Range: bytes=<n>-` request (validated against the stored ETag/total; falls back to a clean restart on
  `200`). **Transcode** downloads are re-encoded fresh on each request and are **not** byte-range
  resumable, so an auto-resume restarts them from the beginning. An explicit **cancel**/delete discards
  the partial file and removes the queue entry.
- **Offline playback:** a downloaded file is cast by serving it through the proxy from the local file
  (`ProxySession.localFilePath` → `LocalProxyServer.serveLocalFile`, with full byte-range/seek support
  since the length is known). The renderer receives only the phone proxy URL — exactly as for live
  streaming. No Jellyfin connection is needed.
- **Codec note:** offline, the phone cannot transcode (transcoding is server-side, at download time).
  A downloaded file plays offline only on a renderer that can decode it — so for guaranteed-offline Cast
  playback, download a transcode preset (MP4 H.264/AAC), which every Cast device can decode, rather than
  an exotic original codec.

## UI

- **Detail screen** → pick a format (Original / MP4 1080p / 720p / 480p) then "Download for offline"
  (shows progress / "Downloaded ✓" with "Cast offline" + "Delete"; a failed download keeps the chosen
  format for one-tap retry).
- **Downloads screen** (Gallery top bar or Settings) lists downloads with cast/cancel/delete.

## Verification

- Pure-JVM: `core/DownloadPathsTest.kt` (filename sanitisation / traversal-proofing, extension
  selection); `data/download/DownloadQueueTest.kt` (format persistence round-trip, resume-selection
  skipping completed/active downloads, and transient-vs-permanent failure classification).
- Live (Docker Jellyfin, see [BUILD.md](BUILD.md)): `JellyfinLiveIntegrationTest` downloads the
  original file and serves it back through the proxy as a local file, asserting the whole file + a byte
  range are served correctly and that **no token or Jellyfin URL** reaches the simulated TV.
