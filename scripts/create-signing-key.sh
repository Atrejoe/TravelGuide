#!/usr/bin/env bash
# Creates the long-lived key that signs every Travel Guide build (issue #15), and prints the values
# for the repository secrets. Run it once, by a maintainer. It never writes into the repository.
#
# Usage: scripts/create-signing-key.sh [keystore-path]
#   keystore-path defaults to ~/.travelguide/travelguide-signing.jks
#
# Requires keytool (from a JDK) and openssl.
set -euo pipefail

keystore="${1:-$HOME/.travelguide/travelguide-signing.jks}"
alias_name="travelguide"

for tool in keytool openssl base64; do
  command -v "$tool" >/dev/null || { echo "error: $tool is not installed" >&2; exit 1; }
done

mkdir -p "$(dirname "$keystore")"
keystore="$(cd "$(dirname "$keystore")" && pwd)/$(basename "$keystore")"

# Refuse to write inside a git work tree, so the key can't be committed by accident.
if git -C "$(dirname "$keystore")" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  echo "error: $keystore is inside a git repository. Choose a path outside any repository." >&2
  exit 1
fi
if [[ -e "$keystore" ]]; then
  echo "error: $keystore already exists. Reuse it, or pass another path." >&2
  exit 1
fi

# keytool creates PKCS12 keystores, which use one password for the store and the key.
# Hex keeps the password free of characters that need quoting.
password="$(openssl rand -hex 32)"

umask 077
keytool -genkeypair -v \
  -keystore "$keystore" \
  -storetype PKCS12 \
  -alias "$alias_name" \
  -keyalg RSA -keysize 4096 \
  -validity 10950 \
  -storepass "$password" \
  -keypass "$password" \
  -dname "CN=Travel Guide, O=Travel Guide" >/dev/null

keystore_base64="$(base64 < "$keystore" | tr -d '\n')"

cat <<MSG

Created $keystore (RSA 4096, valid for 30 years, alias "$alias_name").

!!! BACK UP THIS FILE AND THE PASSWORD NOW (e.g. in a password manager). !!!
If the key is lost, no future build can update installed apps: every user has to uninstall.

The four values for the repository secrets:

  SIGNING_KEYSTORE_BASE64   (the keystore, base64; ${#keystore_base64} characters, not shown)
  SIGNING_KEYSTORE_PASSWORD $password
  SIGNING_KEY_ALIAS         $alias_name
  SIGNING_KEY_PASSWORD      $password

Set them with the GitHub CLI, from a clone of the repository:

  base64 < "$keystore" | tr -d '\n' | gh secret set SIGNING_KEYSTORE_BASE64
  gh secret set SIGNING_KEYSTORE_PASSWORD --body '$password'
  gh secret set SIGNING_KEY_ALIAS --body '$alias_name'
  gh secret set SIGNING_KEY_PASSWORD --body '$password'

Or add them under Settings → Secrets and variables → Actions → New repository secret
(print the keystore value with: base64 < "$keystore" | tr -d '\n').

To sign local builds with the same key, export:

  export TRAVELGUIDE_KEYSTORE_FILE='$keystore'
  export TRAVELGUIDE_KEYSTORE_PASSWORD='$password'
  export TRAVELGUIDE_KEY_ALIAS='$alias_name'
  export TRAVELGUIDE_KEY_PASSWORD='$password'
MSG
