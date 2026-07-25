# Public releases

Every public release is built once from a signed Git tag and delivered in two forms:

- **GitHub Release:** a directly installable, R8-minified APK and its SHA-256 checksum.
- **Google Play:** a signed Android App Bundle (AAB), published to the selected Play track.

The same long-lived signing identity is used for both. GitHub users can therefore install an update
over an earlier GitHub APK without uninstalling it. If Play App Signing is enabled (recommended),
Google re-signs the AAB it serves to Play users; the project key remains the upload key.

## Signing identity — create once, back up forever

**Never commit, attach, or otherwise publish the private `.jks` file or its password.** Possession
of it lets somebody ship an app update under this project's identity. The only signing material that
belongs in Git is the public certificate PEM; it lets users compare the signer fingerprint.

Run this once from the repository root on a trusted developer machine (OpenSSL required; JDK 17+ is preferred but optional):

```bash
./tools/create-release-signing-key.sh
```

It creates these files:

| File | Handling |
| --- | --- |
| `release-signing/stream-ferry-upload.p12` | Private key; encrypted, access-controlled backup only. Git ignores it. |
| `release-signing/github-secrets.env` | Four values for GitHub Actions secrets; private, Git ignored. |
| `docs/release-signing-certificate.pem` | Public certificate; commit this file. |

Copy the private directory to two independent encrypted backups before relying on it. Losing the
key can permanently prevent updates to the GitHub APK lineage and makes upload-key recovery harder.

### GitHub configuration

Add the following **repository Actions secrets**, exactly as written in
`release-signing/github-secrets.env`:

- `RELEASE_KEYSTORE_BASE64`
- `RELEASE_KEYSTORE_PASSWORD`
- `RELEASE_KEY_ALIAS`
- `RELEASE_KEY_PASSWORD`

Create the `google-play-production` GitHub Environment and require an appropriate reviewer before
deploying. Store the Play service-account JSON as the `GOOGLE_PLAY_SERVICE_ACCOUNT_JSON` secret in
that environment, not as a repository secret. This keeps production publishing protected even if a
tag is pushed by mistake.

For the Play Console, enable Play App Signing and register the public upload certificate generated
above when Google asks for it. Create a dedicated Google Cloud service account, enable the Google
Play Android Developer API, then grant that account only the release permissions it needs for this
app in Play Console. The workflow's service-account credential is kept only in the protected
environment secret. Google’s Publishing API uses transactional edits to upload the AAB and assign
it to a track. [Google Play Developer API](https://developers.google.com/android-publisher)

## Cut a release

1. Update the checked-in `versionName` fallback and `CHANGELOG.md`.
2. Commit and push the release commit to `main`.
3. Create and push a strict semantic-version tag, for example:

   ```bash
   git tag -a v0.2.33 -m "Stream Ferry 0.2.33"
   git push origin v0.2.33
   ```

The release workflow verifies the tag has the form `vMAJOR.MINOR.PATCH`, derives a monotonic
Android version code (`MAJOR * 1,000,000 + MINOR * 1,000 + PATCH`), requires the production signing
secrets, then builds the APK and AAB. It creates/updates the GitHub Release with the APK, AAB,
checksum, and signer fingerprint, and uploads the AAB to Play production. A manual dispatch allows
a published tag to be rebuilt and sent to `internal` or `production`; only use that to recover a
failed workflow because Play rejects a version code that already exists.

### Verify a GitHub APK

Download the APK and its `.sha256` asset and run:

```bash
sha256sum --check stream-ferry-vX.Y.Z.sha256
keytool -printcert -jarfile stream-ferry-vX.Y.Z.apk
```

Compare the displayed SHA-256 certificate fingerprint with the `signing-certificate.txt` release
asset and with `docs/release-signing-certificate.pem` in the tagged source. The certificate can be
inspected locally with:

```bash
keytool -printcert -file docs/release-signing-certificate.pem
```

## First Play upload

Play must know the app package (`com.adsamcik.streamferry`) and have its required store listing and
policy declarations completed. If Play does not allow a first production release through the API,
run the workflow once with the `internal` track or complete the first release in Play Console, then
use the normal tagged production release. The service account must be invited in Play Console and
granted permission for this specific app; its JSON must never be committed.
