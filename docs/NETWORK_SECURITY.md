# Network Security Policy

## Upstream (phone ↔ Jellyfin)

- **TLS validation is never disabled.** No trust-all `TrustManager`, no hostname-verifier bypass.
- **Remote HTTP Jellyfin is blocked.** Only HTTPS is permitted for non-LAN servers.
- **HTTP is allowed only for an explicit, user-approved LAN Jellyfin server.** This policy is enforced
  at the **application layer** by `core.net.ServerUrlValidator` (pure-JVM, unit-tested): `https` is
  preferred, **remote `http` is rejected**, and a LAN `http` server is used only after the user
  explicitly approves it on the server-setup screen. A host counts as LAN when it is a
  loopback/link-local/private/CGNAT IP (incl. IPv6 ULA/link-local), a single-label name, a reserved
  local DNS suffix (`.local`, `home.arpa`, `.internal`, `.intranet`, `.private`, `.corp`, `.lan`,
  `.localhost`, …), or an overlay-mesh VPN peer domain (**NetBird** `*.netbird.cloud` / `*.netbird.local`,
  **Tailscale** `*.ts.net`) whose names resolve to private peer addresses. Android's
  `network-security-config` **cannot** express this (its `<domain>` matches hostnames/literal IPs, not
  private IP/CIDR ranges, and there is no runtime API to inject a per-server policy), so
  `cleartextTrafficPermitted` is left `true` and the meaningful enforcement lives in the validator,
  which can actually parse the address.
- **Custom / self-signed Jellyfin CA** support is per-server, explicit, and user-approved, implemented
  by adding that CA as a trust anchor for that server only — **never** by trusting all certificates.
- **No cleartext downgrade of the token.** When the server is reached over HTTPS, the app refuses to
  fetch the token-bearing upstream stream over `http://` (a misbehaving/compromised server cannot
  downgrade the `api_key`/`Authorization` onto a cleartext connection) — `JellyfinClient.absoluteUpstreamUrl`.
- **Debug trust anchors do not ship in release.** A debug-only `src/debug/res/xml/network_security_config.xml`
  adds the user trust store for local testing; the release config keeps **system CAs only**.

`res/xml/network_security_config.xml` (release) keeps **system trust anchors only** (no user-added CAs)
and permits cleartext at the platform layer; the actual https/LAN-http policy is enforced by
`ServerUrlValidator` as described above.

## Downstream (phone ↔ TV, LAN)

- The phone-to-TV proxy uses **plaintext HTTP by design**: most TVs/DLNA renderers/Cast receivers
  cannot trust a phone-generated TLS certificate. This is an accepted residual risk (threat 7).
- It is mitigated by: binding only during an active session, an **ephemeral port**, **256-bit
  high-entropy session IDs**, **unguessable per-session URLs**, rejection of unknown/expired/
  non-session/path-traversal requests, and the fact that **no Jellyfin URL or token is ever placed in
  the stream or its URL**.
- The proxy binds to the phone's LAN address, never to a public interface, and is closed on
  stop/error/expiry.

## Discovery (SSDP / DLNA)

- A device-description `LOCATION:` advertised over SSDP is fetched **only when its host is a
  private/LAN address** (`SsdpParser.isAcceptableLocation` + `ServerUrlValidator.isPrivateHost`), so a
  hostile LAN device cannot lure the control point into fetching an arbitrary remote URL (SSRF). No
  Jellyfin URL or token is ever included in any discovery or AVTransport request.

## Logging & privacy

Never logged: tokens, passwords, Authorization headers, Jellyfin URLs (normal logs), full phone proxy
URLs, upstream stream URLs, auth query params, `PlaySessionId` (normal logs), and — in privacy mode —
user IDs / LAN IPs. `LogRedactor` enforces this and is unit-tested. Stream Ferry operates no
developer analytics, first-party telemetry backend, ads, cloud service, or tracking. The Google Cast
Sender SDK separately sends anonymous Cast interaction and app/device metadata to Google for
aggregate SDK improvement. Diagnostic exports are opt-in and redacted.

**Crash reports** are captured on-device by an uncaught-exception handler (`diagnostics.CrashReporter`)
and written to app-private storage (`filesDir/crashes/`). The whole report — header plus the full
stack trace — is passed through `LogRedactor` (pure-JVM `core.diagnostics.CrashReportFormatter`), so a
token / Jellyfin URL / auth header that lands in an exception message is stripped before it is
persisted. Reports never leave the device unless the user explicitly shares them from
**Settings → Diagnostics**, are excluded from backup like all app data, capped to the most recent few,
and removed by "Delete all app data".

## Backup / data hygiene

- `allowBackup="false"`, `fullBackupContent="false"`, and `dataExtractionRules` exclude secrets — no
  plaintext token backup.
- "Delete all app data" removes profiles, tokens, cached metadata, target profiles, compatibility
  results, diagnostics logs, redacted exports, settings, and clears active buffers after stopping
  playback.

## Release hardening

- R8 / minification / resource shrinking enabled (keep rules documented in `app/proguard-rules.pro`).
- Components minimized; `android:exported` set explicitly; only the launcher activity exported.
- No debug HTTP endpoints, sample URLs, test credentials, or fake tokens in release.
