# Adversarial review TODO

Findings from the 20-topic review on 2026-10-22, against commit
`8942b60068c14a02381c7a16ca9ae2827dcb9b7a`: four P1 issues, eighteen P2 issues,
and two P3 optimization or cleanup opportunities. Resolution notes and actual verification are
recorded below. V01 separately tracks the transcode qualification gap; it is not an additional
production defect.

Status: **AR01–AR24 resolved** in local implementation commit
`f935b219e487d9329cfa67fe8a27a572114a4fc1`; independent review found no remaining blocking issue.
V01 remains open for Android execution and receiver qualification.

The original review was source-backed, with a small DLNA regex probe and no new build, test-suite or
device run. The subsequent fixes have build, regression and host-render evidence below. Physical
receiver effects, device performance and battery consumption remain unmeasured. Original source line
links refer to the reviewed revision and may move; removed-code links document historical findings.

Keep each ID stable. Check an item only after its change and relevant regression verification are
complete; record the fixing commit and actual verification beneath it. Keep any remaining physical
device or performance qualification gap explicit rather than treating a build as runtime proof.
Preserve playback position, latest pause/resume intent, audio, subtitles and output settings through
recovery and replacement, for local, downloaded and Jellyfin media on Cast and DLNA where applicable.
Keep credential/account boundaries, Stop and cancellation guarantees intact.

## P1 issues

- [x] **AR01 — Bind saved Jellyfin credentials to their authenticated origin.**
  - Resolution: Endpoint-bound credentials and explicit reauthentication preserve cached data. Coverage:
    `JellyfinCredentialEndpointTest`, `MainViewModelSourceRetryTest`, `SourceSignInNoticeTest`.
  - Problem: adding an endpoint that reports an existing server's public ID replaces the saved URL
    while retaining its user identity and token. Later restoration can send that token to the new
    origin, including an attacker-controlled endpoint with a valid HTTPS certificate. This requires
    the user to add the endpoint; no live exploit was attempted.
  - Code: [profile replacement](../source/jellyfin/src/main/java/com/adsamcik/streamferry/data/jellyfin/JellyfinAuthRepository.kt#L423),
    [token restoration](../source/jellyfin/src/main/java/com/adsamcik/streamferry/data/jellyfin/JellyfinAuthRepository.kt#L311).
  - Change: bind credentials to origin and account/server identity; require fresh authentication for
    a new origin instead of silently replacing the original profile and inheriting its token.
  - Verify: two origins reporting the same server ID cannot share saved credentials on configuration,
    selection or restart; the original profile remains usable and no old token reaches the new origin.

- [x] **AR02 — Validate representation identity before appending a resumed download.**
  - Resolution: Append requires a matching strong ETag, exact remaining range and consistent length;
    unverifiable representations restart. Old resume metadata must be invalidated before truncating the partial,
    or the old bytes remain intact. Coverage: `MediaDownloaderRepresentationTest` (11 cases, including real
    filesystem invalidation failure).
  - Problem: a saved ETag/Last-Modified validator is not sent or compared during resume. A changed
    server file with the same total length can provide a new tail for an old prefix and pass completion.
  - Code: [range acceptance](../app/src/main/java/com/adsamcik/streamferry/data/download/MediaDownloader.kt#L388).
  - Change: use conditional ranges and/or compare strong representation validators; restart the
    partial copy when identity cannot be established. Matching length and offset alone are insufficient.
  - Verify: unchanged content resumes; changed same-length content and mismatched or absent required
    validators restart safely without publishing mixed bytes as a completed download.

- [x] **AR03 — Derive downloaded playback identity from the selected download.**
  - Resolution: The selected, account-scoped download supplies playback and queue identity, including legacy
    entries. Coverage: `DownloadedPlaybackItemTest` and `MainViewModelPlaybackFailureTest`.
  - Problem: after selecting gallery video X, casting download Y retains X as `nowPlayingItem`.
    Saving the current queue can persist X with Y's position; without a prior selection, Y is omitted.
  - Code: [playback identity](../app/src/main/java/com/adsamcik/streamferry/app/MainViewModel.kt#L1569),
    [download selection](../app/src/main/java/com/adsamcik/streamferry/app/MainViewModel.kt#L2280),
    [queue capture](../app/src/main/java/com/adsamcik/streamferry/app/MainViewModel.kt#L2408).
  - Change: resolve the account-scoped download entry before publishing playback identity, with a
    metadata fallback for legacy entries. Use that identity for preferences and queue capture.
  - Verify: X selected then Y downloaded playback saves/resumes Y at Y's position; repeat with no
    prior gallery selection, legacy metadata and account changes.

- [x] **AR04 — Keep Jellyfin upstream resolution bound to its negotiation.**
  - Resolution: Negotiations carry their own immutable upstream instead of sharing a media-source-ID cache.
    Coverage: `JellyfinNegotiationIsolationTest`.
  - Problem: playback and download negotiations share `upstreamCache[mediaSourceId]`. A concurrent
    negotiation for the same title can substitute its URL, start offset or track mapping before resolution.
  - Code: [cache write and lookup](../source/jellyfin/src/main/java/com/adsamcik/streamferry/data/jellyfin/JellyfinClient.kt#L502),
    [repository resolution](../source/jellyfin/src/main/java/com/adsamcik/streamferry/data/jellyfin/HttpJellyfinRepository.kt#L141).
  - Change: return immutable upstream data with the negotiation or use a unique negotiation handle;
    do not identify a resolved stream solely by media-source ID.
  - Verify: deterministically interleave playback/reload and download negotiation for one media source;
    each retains its own session, URL, offset, output and track selections.

## P2 issues

- [x] **AR05 — Apply bounded codec fallback to synchronous local load rejection.**
  - Resolution: Synchronous local rejection enters the existing bounded codec recovery before terminal cleanup.
    Coverage: `PlaybackEngineLocalTranscodeTest` across absolute/file/content paths and both protocols,
    including NETWORK exclusion and paused intent.
  - Problem: local/downloaded output rejected synchronously by `load()` is torn down immediately,
    while an equivalent asynchronous format error reaches the next-codec fallback.
  - Code: [startup exception handling](../playback/src/main/java/com/adsamcik/streamferry/playback/PlaybackEngine.kt#L1370),
    [codec recovery](../playback/src/main/java/com/adsamcik/streamferry/playback/PlaybackEngine.kt#L3102).
  - Change: apply the same qualified, bounded recovery admission before terminal teardown.
  - Verify: synchronous FORMAT rejection on Cast and DLNA tries an available compatible codec;
    NETWORK failures do not consume format attempts, and playback choices survive the retry.

- [x] **AR06 — Cancel or invalidate explicit retry when playback is closed.**
  - Resolution: Retry jobs have cancellation and generation ownership; obsolete completions cannot recreate
    playback. Coverage: `MainViewModelPlaybackFailureTest`.
  - Problem: explicit retry launches an untracked job. A late handshake failure after Close/Stop
    unconditionally recreates a terminal playback screen.
  - Code: [retry launch and failure](../app/src/main/java/com/adsamcik/streamferry/app/MainViewModel.kt#L3453).
  - Change: own/cancel the retry job and reject obsolete completions using request generations.
  - Verify: suspend retry, close playback, then deliver failure or success; neither restores the old
    screen nor affects a newer session. Retained retry state must still support legitimate retries.

- [x] **AR07 — Parse XML-valid DLNA SOAP fields without attribute-sensitive regexes.**
  - Resolution: SOAP field lookup uses bounded, hardened XML with local-name matching. Healthy polls avoid a
    second fault-only parse. Coverage: `AVTransportTest` and `DlnaTargetControllerControlTest`.
  - Problem: fields such as `<CurrentTransportState xmlns="">PLAYING</CurrentTransportState>`
    fail parsing; three missing samples then falsely disconnect a healthy renderer. Position/status/volume
    regexes have the same attribute limitation. Receiver prevalence is unverified.
  - Code: [SOAP field parsing](../playback/src/main/java/com/adsamcik/streamferry/data/dlna/AVTransport.kt#L21),
    [disconnect threshold](../playback/src/main/java/com/adsamcik/streamferry/data/dlna/DlnaTargetController.kt#L751).
  - Change: use bounded, hardened XML parsing and field lookup by local name.
  - Verify: plain, prefixed and namespace-reset responses with attributes produce correct transport,
    time and volume values; malformed/unreachable responses retain bounded failure handling.

- [x] **AR08 — Format DLNA wire timestamps independently of the phone locale.**
  - Resolution: DIDL and seek timestamps use `Locale.ROOT`. Coverage: `DidlWireLocaleTest` under Arabic and
    Persian defaults.
  - Problem: default-locale formatting can emit non-ASCII digits in DIDL durations and REL_TIME seeks.
  - Code: [duration formatter](../core/src/main/kotlin/com/adsamcik/streamferry/core/dlna/DidlLite.kt#L57),
    [seek command](../playback/src/main/java/com/adsamcik/streamferry/data/dlna/DlnaTargetController.kt#L631).
  - Change: use `Locale.ROOT` for protocol timestamp formatting.
  - Verify: Arabic/Persian default locales still produce ASCII timestamps in metadata, seeks and
    deferred resume; restore the test process's original locale afterwards.

- [x] **AR09 — Relinquish Cast media ownership after another sender replaces it.**
  - Resolution: Public Cast custom data carries a lifecycle UUID and per-load ordinal; older app-issued updates
    are ignored without retaining a growing history. Foreign replacement retires local controls and disconnects
    without stopping its receiver. Coverage: `CastMediaOwnershipTest`, `CastProgressTest`, including pending
    rapid replacements and foreign A -> own B -> foreign A. Metadata omission leaves URL-only identification:
    historical own loads and same-URL external replacement cannot be identified reliably without their tokens.
  - Problem: INTERRUPTED is ignored and foreign content status is filtered, but transport commands
    check session connectivity only. The UI/resources can remain attached to the old media while
    controls act on the other sender's replacement.
  - Code: [idle handling](../playback/src/main/java/com/adsamcik/streamferry/data/cast/CastTargetController.kt#L825),
    [media identity](../playback/src/main/java/com/adsamcik/streamferry/data/cast/CastTargetController.kt#L930),
    [commands](../playback/src/main/java/com/adsamcik/streamferry/data/cast/CastTargetController.kt#L395).
  - Change: track the accepted media session and distinguish app-owned replacements from external loads.
    Release local ownership/resources without stopping another sender's content.
  - Verify: external LOAD within a connected receiver session retires the old playback and its controls;
    app-owned reloads still work. Physical multi-sender confirmation remains a receiver check.

- [x] **AR10 — Preserve actual fragment timing for variable-frame-rate HLS.**
  - Resolution: Per-track encoded timestamps supply cross-fragment lookahead and actual decode-time boundaries.
    Coverage: `HlsFragmentTimingTest` and pinned-Media3 `HlsFragmentNormalizerTest`, including independent audio
    timing and singleton EOS. At final EOF only, absent an encoder EOS timestamp, the last real sample interval
    supplies the otherwise unknowable tail duration.
  - Problem: later `tfdt` values sum sample durations whose fragment tails Media3 approximates by
    duplicating the previous duration. Differing VFR boundary intervals can accumulate video/audio drift.
  - Code: [decode-time accumulation](../playback/src/main/java/com/adsamcik/streamferry/data/transcode/HlsFragmentNormalizer.kt#L90).
  - Change: carry actual encoded timestamps across fragment boundaries and finalize boundary duration
    from the next sample instead of accumulating approximated tails.
  - Verify: irregular frame intervals across several keyframe boundaries preserve original video timing
    and audio alignment; include a one-frame final fragment.
  - Reference: pinned Media3 [fragment writer](https://raw.githubusercontent.com/androidx/media/1.11.1/libraries/muxer/src/main/java/androidx/media3/muxer/FragmentedMp4Writer.java)
    and [duration conversion](https://raw.githubusercontent.com/androidx/media/1.11.1/libraries/muxer/src/main/java/androidx/media3/muxer/Boxes.java).

- [x] **AR11 — Encode HEVC HLS compatibility flags in the required bit order.**
  - Resolution: HEVC compatibility flags are reversed across all 32 bits before codec-string serialization.
    Coverage: `Fmp4SplitterTest` for hvc1/hev1.
  - Problem: compatibility bytes are directly serialized as hex; `0x60000000` becomes `60000000`
    instead of `6`. Malformed advertised CODECS metadata is established; TV rejection is unverified.
  - Code: [HEVC codec string](../core/src/main/kotlin/com/adsamcik/streamferry/core/transcode/Fmp4Splitter.kt#L255).
  - Change: reverse all 32 compatibility bits before hexadecimal serialization.
  - Verify: standard hvcC fixtures produce the specified `hvc1`/`hev1` identifiers and the same values
    reach the published HLS playlist.
  - Reference: [ETSI TS 103 285, HEVC codec identifier derivation](https://www.etsi.org/deliver/etsi_ts/103200_103299/103285/01.01.01_60/ts_103285v010101p.pdf).

- [x] **AR12 — Fit transcoded output within both admitted encoder dimensions.**
  - Resolution: A display-dimension effect fits both admitted dimensions without upscaling; muxer admission
    checks actual encoded dimensions. Coverage: `EncoderEnvelopeTest`, `OnDeviceTranscoderTest`; hardware
    qualification remains V01.
  - Problem: a 1920x1080 capability tier constrains only height during export. A 3840x1080 source
    remains too wide for a phone encoder limited to 1920x1080, with fallback disabled.
  - Code: [height-only effect](../playback/src/main/java/com/adsamcik/streamferry/data/transcode/OnDeviceTranscoder.kt#L153),
    [capability envelope](../playback/src/main/java/com/adsamcik/streamferry/data/transcode/MediaCodecCapabilityProbe.kt#L33).
  - Change: aspect-fit display dimensions into the admitted width and height, including rotation and
    pixel aspect ratio, and validate the actual output size.
  - Verify: ultrawide, portrait, rotated and smaller sources stay within the chosen encoder envelope
    without unintended upscaling or distortion.

- [x] **AR13 — Make local content-provider opens and reads teardown-owned.**
  - Resolution: Proxy teardown owns cancellation signals, descriptors and channels, including late-open
    registration. Coverage: `LocalContentReadTest` and SDK37 `LocalContentProxyCancellationTest`; physical
    provider behavior remains unverified.
  - Problem: a pipe/cloud DocumentsProvider can block an open/read after Stop because local inputs
    are not registered for teardown and opens lack a CancellationSignal. Handlers retain their resources.
  - Code: [content input ownership](../playback/src/main/java/com/adsamcik/streamferry/data/proxy/LocalProxyServer.kt#L991),
    [blocking copy](../playback/src/main/java/com/adsamcik/streamferry/data/proxy/LocalProxyServer.kt#L907).
  - Change: own cancellation signals and opened descriptors/inputs in the proxy lifecycle; reject and
    close registrations racing with Stop.
  - Verify: Stop and replacement unblock a stalled provider open/read, close descriptors and release
    connection permits without cancelling newer sessions. Include unknown-size content sources.

- [x] **AR14 — Retain the receiver's LAN when multiple physical networks exist.**
  - Resolution: Fresh selection deterministically prefers Wi-Fi; active playback retains its physical LAN until
    disappearance. Coverage: `LanNetworkSelectionTest` and existing engine network-change/recovery tests.
  - Problem: taking the first Wi-Fi/Ethernet network can switch a healthy Wi-Fi TV session to unrelated
    USB Ethernet, advertise the wrong address and rediscover on the wrong LAN.
  - Code: [LAN selection](../playback/src/main/java/com/adsamcik/streamferry/diagnostics/NetworkInfoProvider.kt#L82).
  - Change: preserve the active receiver network while available and make fresh network selection
    deterministic, including the intended Wi-Fi preference.
  - Verify: Wi-Fi plus Ethernet enumeration changes do not move an active session; loss of its actual
    network triggers bounded recovery with position and choices preserved.

- [x] **AR15 — Persist the generated container for converted downloads.**
  - Resolution: Converted downloads persist the negotiated output container. Coverage:
    `JellyfinDownloadProviderTest`.
  - Problem: MP4 conversion of an MKV original retains the original container and can save actual MP4
    bytes with `.mkv` metadata/extension. Decoder rejection is not established.
  - Code: [download result](../source/jellyfin/src/main/java/com/adsamcik/streamferry/data/jellyfin/JellyfinSourceBackend.kt#L199).
  - Change: use `upstream.outputContainer` rather than the source profile's container.
  - Verify: differing original/generated containers produce filenames, MIME and persisted metadata
    matching the actual downloaded representation.

- [x] **AR16 — Restore the verified account during sticky download-service recovery.**
  - Resolution: Foreground restart restores only a verified, still-current owner; newer Start/Stop cancels
    obsolete work. Coverage: `DownloadSessionRestorationTest`.
  - Problem: after process death the fresh repository has no live user, so the restarted service stops
    before restoring authentication or resuming downloads. Progress remains stranded until UI startup.
  - Code: [service account check](../app/src/main/java/com/adsamcik/streamferry/data/download/DownloadService.kt#L64).
  - Change: asynchronously restore and verify the active saved profile within foreground ownership
    before selecting its download owner; preserve account isolation and cancellation.
  - Verify: a sticky restart without Activity creation resumes only the verified active owner's queue;
    unavailable or rejected authentication never resumes another account's downloads.

- [x] **AR17 — Show local downloaded content before serial metadata refresh.**
  - Resolution: Verified local entries publish immediately; cancellable batches of at most 50 refresh through an
    account-pinned request and ordered metadata commit. Coverage:
    `DownloadedJellyfinMediaLibraryRepositoryTest`, `DownloadedMetadataRefreshTest`, and queued-account-switch
    regression.
  - Problem: opening Downloaded waits for one remote item request per title before returning local
    entries. First display costs approximately N times request latency; individual cache writes can
    additionally cause O(N squared) full-index validations. This was calculated, not device-profiled.
  - Code: [serial shelf refresh](../app/src/main/java/com/adsamcik/streamferry/data/download/DownloadedJellyfinMediaLibraryRepository.kt#L42),
    [index synchronization](../app/src/main/java/com/adsamcik/streamferry/data/download/DownloadStore.kt#L105).
  - Change: publish verified local entries first, refresh separately with bounded cancellation-aware
    work, and batch metadata updates.
  - Verify: first local results do not wait for all metadata requests; refresh preserves ownership,
    offline data and external watch-state updates without one full-index scan per changed title.

- [x] **AR18 — Release CPU wake ownership after paused work drains.**
  - Resolution: Playback intent and scoped source/encoder work own the CPU lock; drained pause/backpressure
    releases it, and failed Resume reconciles ownership. Coverage: `PlaybackCpuOwnershipTest`,
    `ProxyPlaybackServiceTest`, both streaming backpressure paths, and the engine source/protocol matrix.
    Battery impact is not measured.
  - Problem: successfully paused sessions retain an indefinite partial CPU wake lock until service
    destruction, even after delivery and encoding have stopped progressing. Consumption was not measured.
  - Code: [lock acquisition](../playback/src/main/java/com/adsamcik/streamferry/playback/proxy/ProxyPlaybackService.kt#L168),
    [pause](../playback/src/main/java/com/adsamcik/streamferry/playback/PlaybackEngine.kt#L1206).
  - Change: derive CPU lock ownership from active relay/encoding work, releasing it after paused work
    drains and reacquiring it before resumed delivery. Keep the session and notification available.
  - Verify: active delivery -> paused/drained -> resume transitions own/release/reacquire locks correctly;
    Stop releases them and paused incoming requests remain serviceable. Measure battery impact separately.

- [x] **AR19 — Disable accessibility seek actions when seeking is unavailable.**
  - Resolution: Unavailable seeking exposes disabled semantics without SetProgress. Coverage: SDK37
    `PlaybackSeekAccessibilityTest` for both protocols and playback/reconnect phases.
  - Problem: `SetProgress` ignores `seekable` when duration is known, allowing accessibility commands
    during reconnect/protocol change even though touch and Jump to controls are disabled.
  - Code: [timeline semantics](../ui/src/main/java/com/adsamcik/streamferry/ui/screens/PlaybackScreens.kt#L1358).
  - Change: gate `setProgress` and expose disabled semantics consistently with the control policy.
  - Verify: invoking the semantics action while changing protocol/reconnecting sends no seek; normal
    playing/paused seekable states still allow accessible seeking.

- [x] **AR20 — Restore parent scroll and search context on folder Back.**
  - Resolution: Bounded saveable grid/list anchors and folder/search history restore parent context. Coverage:
    SDK37 `GalleryFolderReturnPositionTest`, `GalleryReturnPositionTest`, and ViewModel folder navigation
    coverage. Native light/dark inspection also shortened narrow availability badges while preserving full
    accessibility descriptions.
  - Problem: the single grid/list state is reset by folder/query changes. Returning from a folder
    loses the parent's anchor; entering a folder from search clears the query and loses those results.
  - Code: [scroll ownership](../ui/src/main/java/com/adsamcik/streamferry/ui/screens/GalleryScreens.kt#L155),
    [folder navigation](../app/src/main/java/com/adsamcik/streamferry/app/MainViewModel.kt#L1080).
  - Change: retain bounded browse-history entries with folder, search context and scroll anchor.
  - Verify: library/season folder -> Back and search-result folder -> Back restore their prior content,
    query and position; returning from Playback continues to preserve its existing behavior.

- [x] **AR21 — Run existing JVM-only suites and source boundaries in CI.**
  - Resolution: CI includes core/source-API JVM suites and source boundaries; release also includes source-API
    tests. Local task execution is recorded below; no remote workflow was launched.
  - Problem: `testDebugUnitTest` omits `:core:test` and `:source:api:test`; CI also omits
    `checkSourceBoundaries`. Release includes core/boundaries but still omits source API tests.
  - Code: [CI unit task](../.github/workflows/ci.yml#L60), [release gate](../.github/workflows/release.yml#L120).
  - Change: add the omitted existing tasks to their relevant gates without weakening other checks.
  - Verify: actual task execution includes both JVM suites and the boundary check in CI, plus the
    source API suite in release; inspect task results rather than relying on an aggregate green status.

- [x] **AR22 — Reject HTTP error bodies in live streaming acceptance.**
  - Resolution: Acceptance helpers require expected 200/206 responses and traversal reaching media. Coverage:
    `StreamingAcceptanceTest` with nonempty 404/500, range, missing/depth-exhausted and successful HLS cases. No
    live-provider claim.
  - Problem: `fetch()` ignores status, so nonempty 404/500 bodies can count as HLS media, and a large
    error body can pass progressive checks. HLS traversal can also exit without reaching media.
  - Code: [fetch helper](../app/src/test/java/com/adsamcik/streamferry/integration/JellyfinLiveIntegrationTest.kt#L304),
    [HLS traversal](../app/src/test/java/com/adsamcik/streamferry/integration/JellyfinLiveIntegrationTest.kt#L268).
  - Change: assert expected successful statuses and require traversal to reach a media response;
    retain precise range-status checks.
  - Verify: nonempty 404/500 bodies, missing segment URLs and exhausted traversal depth fail acceptance;
    successful progressive, ranged and nested-HLS delivery still pass.

## P3 opportunities

- [x] **AR23 — Reduce full-payload copies during HLS fragment assembly.**
  - Resolution: Headers and payload remain separate until final assembly; substantial copying occurs outside the
    reader lock. Existing fragment payload/timing and backpressure tests protect behavior. Allocation/latency
    improvements are structural, not device-profiled.
  - Cost: the current path creates about five payload-sized array allocations under the receiver lock
    before backpressure. A near-24 MiB fragment causes roughly 120 MiB aggregate allocation, not a
    measured simultaneous heap peak. Playback interruption or OOM was not demonstrated.
  - Code: [normalizer copies](../playback/src/main/java/com/adsamcik/streamferry/data/transcode/HlsFragmentNormalizer.kt#L36),
    [fragment assembly](../playback/src/main/java/com/adsamcik/streamferry/data/transcode/StreamingTranscodeSession.kt#L430).
  - Change: keep headers/payload separate until one final allocation, avoiding the intermediate
    ByteArrayOutputStream copy and concatenations; move substantial copying outside the reader lock.
  - Verify: compare output equivalence and allocation work; retain startup latency, bounded buffering,
    backpressure and cancellation behavior. Profile before claiming runtime improvement.

- [x] **AR24 — Remove unused routing policy and selection adapter layers.**
  - Resolution: Removed the unused router, selection adapter/interface and legacy capability wiring; actual
    selector and engine tests remain.
  - Cost: `PlaybackRouter` has test consumers but no production caller; the selection adapter/domain
    interface are also unused while the engine directly creates the core selector. These duplicate
    apparent decision paths add maintenance work without validating actual playback routing.
  - Code: [unused router](../core/src/main/kotlin/com/adsamcik/streamferry/core/transcode/PlaybackRouter.kt#L40),
    [unused adapter](../playback/src/main/java/com/adsamcik/streamferry/playback/streamselection/DefaultStreamSelectionService.kt#L11).
  - Change: remove unconsumed policy/adapter/test layers and only those capability fields confirmed
    unused. Retain tests around actual engine/core decisions; do not replace them with another abstraction.
  - Verify: search production consumers before removal, compile affected modules and run meaningful
    actual-path routing tests. No runtime behavior change is intended.

## Qualification follow-up

- [ ] **V01 — Establish transcode decoding and playback evidence beyond initial packaging.**
  - Partial implementation: instrumentation now decodes later fragments through platform MediaCodec,
    checks nonzero AAC PCM, EOS, sample count, timestamp span and audio/video end alignment.
    The extended test compiles and packages; it has not run on Android or a receiver. V01 stays open.
  - Original gap: [LocalTranscodeOutputTest.kt:94](../ui/src/androidTest/java/com/adsamcik/streamferry/ui/screens/LocalTranscodeOutputTest.kt#L94)
    checked publication, codec, output height, initial fragment structure and playlist presence. It did
    not decode media, validate audio or inspect later fragments; that pass was not playback qualification.
  - Follow-up: add proportionate decode/audio/timing and later-fragment checks, then record applicable
    Cast/DLNA receiver results for supported source/output paths, including seek, pause/resume and changes.
  - Evidence rule: keep build, structural, decoder and receiver results separate. Use only the highest
    stable Android target already available; never create/clone an AVD or substitute an older API.
    Coordinate exclusive use of the shared emulator and leave unavailable checks explicitly outstanding.

## Review coverage

One agent reviewed each distinct topic: correctness, resiliency, reliability, security, minimalism,
UI, UX, general performance, battery use, DLNA, Google Cast, Jellyfin, local streaming, transcoding
lifecycle, fallback, transcoding format support, transcoding correctness, local transcoding performance,
transcoding validation and tests. Repeated topic names were consolidated. The bounded transcoding
lifecycle and validation passes established no additional production defect; V01 preserves the
validation limitation without inventing another bug.

## Fix verification (2026-10-22)

All AR01–AR24 fixes share the implementation commit recorded above. The per-item notes name their
regressions; original problem statements remain as review history.

The full JVM suites were run, then only failed fixture classes and subsequently changed Cast/retry
classes were rerun. Initial failures included strict MockK fixtures, a nullable matcher compilation
error, and the missing API 37 Robolectric module access flag; these were corrected without weakening
behavior assertions. Passing focused XML results replaced their class reports in the retained local
archive at `build/adversarial-verification/full/`. The totals below describe that combined evidence,
not a claim that one final aggregate command reran every test.

| Module | Passed | Skipped |
| --- | ---: | ---: |
| app | 119 | 25 |
| core | 415 | 0 |
| playback | 402 | 0 |
| source/api | 6 | 0 |
| source/jellyfin | 127 | 0 |
| source/local | 13 | 0 |
| ui | 55 | 0 |
| **Total** | **1137** | **25** |

The 25 skips are the explicitly opt-in live-provider suites `JellyfinLiveIntegrationTest` (3) and
`LiveWatchStateValidationTest` (22). No live Jellyfin account or TV result is claimed.

Executed tasks included `:core:test`, `:source:api:test`, `checkSourceBoundaries`,
`testDebugUnitTest`, `:app:assembleDebug`, `:ui:assembleDebugAndroidTest`,
`:app:compileDebugAndroidTestKotlin` and `lintDebug`. The final Cast-only change was checked with
`:app:assembleDebug :app:lintDebug` after its focused tests passed. Logs are local ignored artifacts:
`build/adversarial-fixes-verification*.log`; iterations 3–7 contain the full suites and corrective
focused runs, and iteration 8 contains the final packaging/lint result. All five Android modules have
passing lint reports. Existing warnings remain; no required gate was weakened.

Robolectric checks use the highest existing stable test target, API 37, with the pinned Robolectric
version. Existing lower host-test annotations were normalized to 37; app/local/Jellyfin forks received
the same JDK module access option already used by playback/UI. Production SDK levels and dependencies
were not changed. Verification used the existing Gradle 9.8/JDK 21 toolchain. The user's pre-existing
Gradle-wrapper changes and `app/release/` files were preserved and excluded from the fix commits.

Native host rendering inspected six 393×851 light/dark gallery, connected-playback and
reconnecting-playback images, including Offline/Unavailable badges and a sign-in-required cached
library. A temporary capture probe was removed after inspection; images remain locally in
`ui/build/ui-review/`. Committed host interaction tests cover folder/search restoration, sign-in
navigation and seek accessibility. Host rendering is not physical-device UI verification.

V01 remains open. The extended platform-decoder/audio/later-fragment instrumentation compiles and
packages but was not executed: the pre-existing API 37 emulator was reserved by another project.
No older target, replacement AVD or shared-device action was used. Cast/DLNA receiver interoperability,
multi-sender behavior, content-provider behavior on a phone, hardware decode/encode qualification,
latency, allocation and battery measurements remain separate runtime evidence.

An independent review-only agent reviewed the final changes and follow-up corrections. No remote
publication or workflow dispatch was performed.
