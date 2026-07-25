#!/usr/bin/env bash
# Creates the one long-lived Android upload/release identity for Stream Ferry.
# The private output directory is ignored by Git; only the exported PEM certificate is public.
set -euo pipefail

output_dir="${1:-release-signing}"
certificate_path="${2:-docs/release-signing-certificate.pem}"
alias_name="stream-ferry"

if [[ -e "$output_dir" ]]; then
  echo "Refusing to overwrite existing $output_dir. Choose a new directory or move it to secure storage." >&2
  exit 1
fi
if [[ -e "$certificate_path" ]]; then
  echo "Refusing to overwrite existing public certificate: $certificate_path" >&2
  exit 1
fi
command -v openssl >/dev/null || { echo "openssl is required to create a strong password." >&2; exit 1; }

umask 077
mkdir -p "$output_dir" "$(dirname "$certificate_path")"
keystore_path="$output_dir/stream-ferry-upload.p12"
secrets_path="$output_dir/github-secrets.env"
password="$(openssl rand -base64 48 | tr -dc 'A-Za-z0-9' | head -c 40)"

if command -v keytool >/dev/null; then
  keytool -genkeypair -v \
    -keystore "$keystore_path" -storetype PKCS12 \
    -storepass "$password" -keypass "$password" -alias "$alias_name" \
    -keyalg RSA -keysize 4096 -sigalg SHA256withRSA -validity 10000 \
    -dname 'CN=Stream Ferry, O=adamnova, C=US'
  keytool -exportcert -rfc \
    -keystore "$keystore_path" -storepass "$password" -alias "$alias_name" \
    -file "$certificate_path"
else
  # PKCS#12 is supported by Android/Java signing and lets a trusted machine without
  # a JDK create the same kind of keystore. The transient PEM private key is removed.
  private_key="$output_dir/private-key.pem"
  openssl req -x509 -newkey rsa:4096 -nodes -sha256 -days 10000 \
    -subj '/CN=Stream Ferry/O=adamnova/C=US' \
    -keyout "$private_key" -out "$certificate_path"
  openssl pkcs12 -export -out "$keystore_path" -inkey "$private_key" \
    -in "$certificate_path" -name "$alias_name" -passout "pass:$password"
  rm -f "$private_key"
fi

keystore_base64="$(base64 -w 0 "$keystore_path")"
cat > "$secrets_path" <<EOF
# Keep this file and stream-ferry-upload.p12 in encrypted, access-controlled backup storage.
RELEASE_KEYSTORE_BASE64=$keystore_base64
RELEASE_KEYSTORE_PASSWORD=$password
RELEASE_KEY_ALIAS=$alias_name
RELEASE_KEY_PASSWORD=$password
EOF
chmod 600 "$secrets_path" "$keystore_path"

echo "Created private keystore: $keystore_path"
echo "Created GitHub-secret values: $secrets_path"
echo "Created public certificate (safe to commit): $certificate_path"
echo "Certificate fingerprint:"
if command -v keytool >/dev/null; then
  keytool -list -v -keystore "$keystore_path" -storepass "$password" -alias "$alias_name" \
    | awk -F': ' '/SHA256:/{print $2}'
else
  openssl x509 -in "$certificate_path" -noout -fingerprint -sha256 | cut -d= -f2
fi
