#!/usr/bin/env bash
# Generates a local, untracked RSA JWT signing key. Never overwrites a key.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
KEYSTORE="$ROOT/secrets/auth-signing.p12"
ALIAS="${AUTH_JWT_KEY_ALIAS:-jwt-signing}"

if [[ ! "$ALIAS" =~ ^[A-Za-z0-9._-]{1,64}$ ]]; then
  echo "AUTH_JWT_KEY_ALIAS should contain only letters, numbers, dots, underscores or dashes." >&2
  exit 2
fi
if [[ -z "${AUTH_JWT_KEYSTORE_PASSWORD:-}" || -z "${AUTH_JWT_KEY_PASSWORD:-}" ]]; then
  echo "Export AUTH_JWT_KEYSTORE_PASSWORD and AUTH_JWT_KEY_PASSWORD before running this script." >&2
  exit 2
fi
if [[ "${#AUTH_JWT_KEYSTORE_PASSWORD}" -lt 24 || "${#AUTH_JWT_KEY_PASSWORD}" -lt 24 ]]; then
  echo "Set AUTH_JWT_KEYSTORE_PASSWORD and AUTH_JWT_KEY_PASSWORD to long local secrets." >&2
  exit 2
fi
if [[ "$AUTH_JWT_KEYSTORE_PASSWORD" != "$AUTH_JWT_KEY_PASSWORD" ]]; then
  echo "Use equal store and key passwords for PKCS12 compatibility." >&2
  exit 2
fi
command -v keytool >/dev/null || { echo "JDK keytool is required." >&2; exit 2; }
umask 077
mkdir -p "$ROOT/secrets"
if [[ -e "$KEYSTORE" ]]; then
  echo "Refusing to overwrite secrets/auth-signing.p12" >&2
  exit 1
fi
keytool -genkeypair -alias "$ALIAS" -keyalg RSA -keysize 3072 \
  -storetype PKCS12 -keystore "$KEYSTORE" \
  -storepass:env AUTH_JWT_KEYSTORE_PASSWORD -keypass:env AUTH_JWT_KEY_PASSWORD \
  -dname "CN=Local Portfolio OAuth Signing Key" -validity 3650 -noprompt >/dev/null
chmod 600 "$KEYSTORE"
echo "Created untracked secrets/auth-signing.p12; protect and back up this private key."
