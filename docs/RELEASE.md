# Public releases

Every release is built from a signed Git tag and published on GitHub with:

- a directly installable, R8-minified APK;
- a SHA-256 checksum and signer-certificate report.

GitHub users can install an update over an earlier GitHub APK because every release uses the same
long-lived signing identity.

## Signing identity — create once, back up forever

**Never commit, attach, or otherwise publish the private `.p12` file or its password.** Possession
of it lets somebody ship an app update under this project's identity. The only signing material that
belongs in Git is the public certificate PEM; it lets users compare the signer fingerprint.

Run this once from the repository root on a trusted developer machine (OpenSSL required; JDK 17+ is
preferred but optional):

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
key can permanently prevent updates to the GitHub APK lineage.

### GitHub configuration

Add the following **repository Actions secrets**, exactly as written in
`release-signing/github-secrets.env`:

- `RELEASE_KEYSTORE_BASE64`
- `RELEASE_KEYSTORE_PASSWORD`
- `RELEASE_KEY_ALIAS`
- `RELEASE_KEY_PASSWORD`

## Cut a release

1. Update the checked-in `versionName` and `CHANGELOG.md`.
2. Commit and push the release commit to `main`.
3. Create and push a strict semantic-version tag, for example:

   ```bash
   git tag -a v0.3.0 -m "Stream Ferry 0.3.0"
   git push origin v0.3.0
   ```

The release workflow verifies the tag has the form `vMAJOR.MINOR.PATCH` and passes its version name to
Gradle, which is the sole source of the monotonic Android version-code calculation
(`MAJOR * 1,000,000 + MINOR * 1,000 + PATCH`). The workflow requires the stable signing secrets, then
builds the APK. It creates or updates the public GitHub Release with the APK, checksum, and signer
report. A manual dispatch can rebuild an already-published tag.

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
