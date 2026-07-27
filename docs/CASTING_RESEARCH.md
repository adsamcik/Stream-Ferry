# Phone→TV casting research — condensed for Stream Ferry

Distilled from a 1256-line research dump (`paste-1782057501050.txt`), read line-by-line by
20 agents and filtered to **only** what matters for Stream Ferry's architecture: the
**phone is the sole media gateway and the TV only ever fetches a phone-hosted LAN URL**
(`http://PHONE_LAN_IP:EPHEMERAL_PORT/session/<256-bit-id>/stream`). The TV never touches
Jellyfin. Line cites `[Lnnn]` refer to the source dump.

---

## 0. TL;DR — how the research maps to us

- The source's **"phone-as-proxy / local relay"** (its Profile 3) **is the entire Jellyfin
  Bridge design.** The dump repeatedly frames it as a *fragile, advanced fallback* — for us
  it is the core path, so its warnings are our primary engineering checklist, not edge cases.
- Both our modes work for one reason: **the receiver fetches a URL, and we make that URL the
  phone's**. That satisfies our security invariant by construction — the TV cannot reach
  Jellyfin and never sees upstream auth. [L99-L101, L656, L888, L1009-L1010, L1163-L1166]
- **Proxy Cast (preferred):** phone is a Cast *sender/controller*; the Cast *receiver* pulls
  the phone URL. **Proxy DLNA (fallback):** phone is a UPnP *control point*; the DLNA
  *renderer* pulls the phone URL.
- **Detect, never assume.** Cast availability, DLNA renderer behavior, and codec/subtitle
  support vary by TV model/region/firmware — probe at runtime, store results per receiver,
  never infer from brand. [L23, L525-L526, L855, L874-L875]
- Everything that is **not** a "TV pulls a phone-hosted URL" path is rejected for us
  (§9): mirroring, MediaProjection, HDMI/DeX, Sharesheet/Quick Share, SmartThings, raw
  MediaRouter, Smart View SDK / Tizen / webOS receiver apps, Cast Connect, Matter/Fling, DIAL.

---

## 1. Proxy Cast mode (preferred)

**Model.** Phone = Cast sender/controller; the Cast **receiver fetches the media URL**. So we
hand the receiver our phone-hosted LAN URL, never Jellyfin's. Cast is *not* byte-streaming from
the phone and *not* DLNA. [L97-L99, L655-L658, L914-L915]

**APIs.** Google Cast Android **Sender SDK** (`play-services-cast-framework`): `CastContext`,
`SessionManager`/`CastSession`, `RemoteMediaClient`, `MediaInfo`, `MediaLoadRequestData`.
Sender controls load/play/pause/seek/stop/volume/status/queue/tracks. [L25-L26, L639, L903, L932]
- **Media3 `CastPlayer`** is a thin `Player` abstraction — use only if the app is Media3-first
  and Cast needs are standard. Prefer the **direct Cast SDK** for custom receiver messages,
  credentials, diagnostics, and proxy-specific logic — i.e. our case. [L640-L643, L860, L961-L979]

**Receiver choice.** Default/Styled Web Receiver is enough for plain phone-hosted-URL playback.
Use a **Custom Web Receiver** only if we want receiver-side auth, custom UI, advanced
audio/video track switching, or diagnostics; published web receivers must be TLS-hosted.
[L245-L248, L808-L811, L908-L912]

**Discovery / selection.** `MediaRouteButton` / **Output Switcher**; discovery needs same
Wi-Fi + multicast/mDNS (`_googlecast._tcp.local`); AP isolation breaks it. Build for
controller-state reconnect after interruptions. [L585, L681, L944]

**Serving constraints specific to Cast.**
- Correct MIME in `MediaInfo`; implement HTTP byte-range/206 for seek/progressive. [L668-L669]
- **CORS** is required for adaptive streams (HLS/DASH) **and for any media that carries tracks**,
  including simple MP4 with subtitles. [L104, L661, L928]
- Text tracks (WebVTT/TTML) work on Default/Styled; **audio/video track switching needs a
  Custom Receiver**. Subtitle URLs must be receiver-reachable. [L671, L927-L928]
- Receiver TLS trust: private LAN HTTPS certs may be rejected; our plain-`http://` LAN URL is
  the right shape but must be tested per receiver. [L667, L1041-L1042]
- Never push phone-only cookies/VPN/private headers to the receiver — the relay hides them. [L672, L930]

---

## 2. Proxy DLNA mode (fallback)

**Model.** Phone = UPnP AV **Control Point**; the DLNA **renderer (TV) pulls** the phone URL. [L109-L110]

**Flow.** SSDP `M-SEARCH` multicast → fetch device-description XML → filter `MediaRenderer` →
parse service URLs → `ConnectionManager.GetProtocolInfo` → `AVTransport.SetAVTransportURI` +
`Play` (+ `Pause`/`Stop`/`Seek`, `GetTransportInfo`, `GetPositionInfo`) →
`RenderingControl.SetVolume`/mute where supported. [L264-L270, L381-L386, L691-L700]

**No official Android DLNA SDK** — implement via UDP multicast + HTTP + XML/SOAP yourself (or an
audited UPnP lib). [L271, L702]

**Why DLNA *fits* our threat model:** renderers commonly accept **only plain unauthenticated
HTTP** and frequently reject HTTPS, redirects, cookies, and auth headers — exactly our
token-in-path `http://` LAN URL with no auth headers. `SetAVTransportURI` is just a URL handoff,
so upstream auth can't leak. [L274-L276, L709-L711]

**Caveats.** `SetAVTransportURI` success ≠ playback; `CurrentTransportActions` vary per resource
(a stream may expose only Play/Stop); Seek is conditional on renderer + Range support; subtitle
and codec/container support are model/year-specific; control-point state must recover after
interruptions. [L279, L586, L699-L700, L718-L720]

---

## 3. Phone HTTP relay engineering (shared by both modes — the heart of the app)

- Local HTTP server with a **stable LAN URL** for the session; bind local-only. [L327-L344, L1022-L1024]
- Correct `Content-Type`/MIME; `Content-Length` where possible. [L343-L344]
- **`Range` / `206 Partial Content`** with `Content-Range` + `Accept-Ranges` and accurate byte
  counts; handle HEAD, full GET, single + concurrent ranges, seek-after-start/pause; fail
  gracefully when a range can't be satisfied. (Cast doesn't fully specify the server contract —
  implement it anyway.) [L345, L539-L540, L1026]
- Backpressure + cancellation; memory buffer sized to tolerate jitter. [L346-L347]
- **Progressive MP4 with fast-start (`moov` atom at front)** for fast startup/seek; otherwise
  generate HLS/DASH. Optional remux/transcode when the receiver can't decode the source. [L348, L1025, L1196]
- CORS where the receiver path needs it (adaptive, subtitles, custom/browser receivers). [L656, L669]
- **Foreground service** (correct FGS type) + persistent notification to keep the relay alive. [L341, L587, L888]
- Must survive: screen-off, backgrounded, swipe-away, Doze, Wi-Fi sleep/power-saving, Samsung
  battery optimization, incoming calls, Wi-Fi band handoff, IP/route changes, process death. [L351, L573-L582, L1027-L1029]
- Relay only works if the **TV can reach the phone's LAN IP** — fails on AP isolation, different
  subnet, VPN routing, firewall, IPv4/IPv6 mismatch. Separate *discovery* failures from
  *playback* failures in diagnostics. [L570-L571, L597, L1037]

---

## 4. Security & redaction (this is our invariant, restated by the source)

- The TV sees **only** the phone-hosted URL — never the Jellyfin base URL, token, `Authorization`
  header, or any playlist/segment/subtitle/poster URL. Keep all upstream auth behind the proxy. [L349, L656, L888, L1171-L1174]
- **Unguessable session IDs** (our 256-bit), TTL/short-lived, local-only binding, cleanup after
  playback; reject unknown clients where possible; **assume a hostile LAN**. [L349-L350, L1028-L1033]
- Never put upstream bearer tokens/credentials in TV-facing URLs or responses. [L1033]
- **Redact URLs/tokens in logs** (we already have `core/redaction`). [L350, L946]
- Phone-minted session URL TTL is ours to control, but plan recovery for expiry during long
  playback/seek/reconnect. [L940, L1207-L1209]
- DRM/protected content is generally **not** legally/technically relayable — don't assume it is. [L601, L1018]

---

## 5. Android 14–17 platform constraints (we target/compile SDK 37)

- **Local-network access permission.** Android 16 = opt-in; **Android 17 / target SDK 37 adds
  `ACCESS_LOCAL_NETWORK` as a runtime permission** gating raw sockets, mDNS/NSD, SSDP, TCP, and
  UDP unicast/multicast/broadcast — i.e. our SSDP discovery, the LAN relay, and the TV→phone
  connection all need it. Request and handle denial. [L271, L703, L1031]
- **Output Switcher** as the in-app picker is documented as **not** needing local-network
  permission — prefer it for Cast device selection. [L28, L791]
- **Foreground services.** Android 12+ restricts starting an FGS from the background; Android 14+
  requires declaring the appropriate **FGS type** for ongoing work (relay + any capture). [L352, L1031]

---

## 6. Codec / container / subtitle matrix (what to direct-serve vs remux/transcode)

| Tier | Formats | Action |
|---|---|---|
| **Safe baseline (both Cast & DLNA)** | **MP4 (fast-start) + H.264 + AAC**; JPEG/PNG; MP3/AAC | Direct-serve [L390, L444, L919] |
| Cast containers | MP4, WebM, MP2T, MP3, OGG, WAV | Direct-serve; **MKV is NOT a Cast container → remux to MP4/HLS** [L660, L915, L924] |
| Adaptive | HLS / DASH | OK if manifest/segments/keys/subs reachable + CORS [L920-L921] |
| **Device-specific — verify on real receiver** | HEVC 8/10-bit, HDR10, Dolby Vision, AV1 | Test per model; don't infer from brand [L922-L923, L1201] |
| **High-risk → remux/transcode/burn-in** | MKV, DTS/TrueHD, AC3/EAC3 (varies), PGS/ASS subs | Plan a transcode path [L925, L1204] |
| Subtitles | WebVTT/TTML/CEA best; SRT/embedded/ASS/SSA/PGS inconsistent | Reachable URL + CORS; burn-in fallback [L671, L713, L928] |

Store compatibility **per receiver model/protocol**, never per brand. [L525-L526]

---

## 7. Testing checklist (relay-relevant subset)

- **Discovery:** same Wi-Fi; mDNS `_googlecast._tcp.local`; SSDP multicast; bands/mesh/guest/
  VLANs; AP isolation; permission-denied; device disappearance. [L428-L440, L944]
- **Load/play:** known-good MP4 H.264/AAC, HLS, and the phone-relay URL; record first-frame time
  + error; app must distinguish receiver-direct vs phone-relayed and show a clear error when the
  receiver can't reach the URL. [L444-L454]
- **Sustained:** 30 s–30 min uninterrupted with screen off / backgrounded / FGS active; Samsung
  battery default vs unrestricted; playback position stays accurate. [L455-L467, L1195]
- **Transport:** pause/resume, seek fwd/back/near-ends, stop/reload; DLNA `GetPositionInfo`
  accuracy; seek only when Range supported; unsupported seek surfaced explicitly. [L468-L480]
- **Range:** HEAD, full GET, `bytes=0-`, mid-range, seek-after-start/pause, concurrent ranges →
  correct 206/`Content-Range`/`Accept-Ranges`. [L528-L540]
- **Subtitles/audio:** none/SRT/WebVTT/embedded/ASS/PGS; single/multi AAC, AC3/EAC3, DTS/TrueHD,
  language metadata, runtime switch. [L481-L504]
- **Auth/URL:** public, signed/short-lived, token expiry during long playback/seek/reconnect;
  ensure the default path needs no phone-only headers. [L541-L555, L1207-L1209]
- **Network/VPN:** phone-on-VPN/TV-not, split tunnel (source VPN-only, TV LAN-reachable), private
  DNS, IPv4-only, IPv6, guest isolation, multicast-blocked router, hotspot. [L556-L571]
- **Samsung lifecycle:** Doze, Wi-Fi power-saving, calls/notifications, band handoff, swipe-away,
  process death → Cast reconnects, DLNA recovers, relay survives only with proper FGS;
  diagnostics explain OEM battery limits. [L572-L588]
- **Real hardware:** TV model/firmware, receiver type, codec/subtitle catalog, HTTPS trust, CORS. [L1223-L1239]

---

## 8. Rejected alternatives (why each is out for us)

- **Screen mirroring** (Miracast/Wi-Fi Display, Samsung Smart View, LG Screen Share): system/OEM
  feature, not a programmable third-party transport; latency/battery/DRM-blackout/privacy; no
  native TV playback semantics. [L282-L283, L611, L727-L734]
- **MediaProjection:** capture-only, needs consent + media-projection FGS, doesn't reach
  Cast/DLNA by itself, DRM blackouts — last resort only. [L676, L1051-L1058]
- **Raw MediaRouter as discovery:** route UI/framework only; won't discover generic
  DLNA/Tizen/webOS/Miracast TVs; for Cast, use the Cast SDK. [L680-L683, L982-L987]
- **Sharesheet / `ACTION_SEND` / `content://`:** phone IPC, not a TV-reachable network URL; no
  session/seek/volume control. [L1077-L1080]
- **Quick Share / Nearby Share:** file transfer, not streaming. [L292, L630]
- **SmartThings:** device control (power/input/volume), not a media transport. [L289, L753]
- **Samsung Smart View SDK / Tizen receiver app:** Samsung-only, receiver-side app required. [L742-L749]
- **LG webOS receiver app / Connect SDK:** receiver-app route only if we ship a TV app; Connect
  SDK is an old multi-protocol abstraction, not a guarantee. [L757-L759]
- **Cast Connect / Android TV receiver:** only if we own/ship the TV app; not generic. [L990-L1004]
- **Fire TV Matter Casting / Amazon Fling:** vendor-specific; **Fling end-of-support 2026-03-05** —
  avoid. [L628, L765-L767]
- **DIAL:** discovery/app-launch only, no media/control channel. [L773-L775]
- **HDMI / USB-C / DeX wired:** hardware display path, not a LAN relay. [L86-L92, L1102]
- **TV browser / WebRTC receiver:** brittle (codecs, autoplay, LAN HTTPS certs, remote keys, weak
  WebRTC), manual pairing — fallback only. [L317-L324, L894]

---

*Process: 1256 source lines split into 20 ranges, each read by a `gpt-5.4-mini` explore agent
with the Jellyfin-Bridge lens; findings deduplicated and synthesized here. The source is heavily
repetitive (multiple overlapping reports), so this condensation collapses ~5–6 restatements of
each point into one.*
