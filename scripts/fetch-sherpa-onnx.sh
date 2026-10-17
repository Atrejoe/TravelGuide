#!/usr/bin/env bash
# Downloads the sherpa-onnx Android library (on-device neural text-to-speech runtime) into app/libs.
# Usage: scripts/fetch-sherpa-onnx.sh [version]
set -euo pipefail

VERSION="${1:-${SHERPA_ONNX_VERSION:-$(cat "$(dirname "$0")/../sherpa-onnx.version")}}"
DEST="$(dirname "$0")/../app/libs/sherpa-onnx.aar"
API="https://api.github.com/repos/k2-fsa/sherpa-onnx/releases/tags/v${VERSION}"

if [[ -f "$DEST" && -f "$DEST.version" && "$(cat "$DEST.version")" == "$VERSION" ]]; then
  echo "sherpa-onnx $VERSION already present"
  exit 0
fi

auth=()
if [[ -n "${GITHUB_TOKEN:-}" ]]; then auth=(-H "Authorization: Bearer ${GITHUB_TOKEN}"); fi

release_json="$(curl -fsSL "${auth[@]}" "$API")" || {
  echo "Release v${VERSION} not found. Recent releases:" >&2
  curl -fsSL "${auth[@]}" "https://api.github.com/repos/k2-fsa/sherpa-onnx/releases?per_page=10" \
    | grep -oE '"tag_name": *"[^"]+"' >&2 || true
  exit 1
}

url="$(echo "$release_json" | grep -oE '"browser_download_url": *"[^"]+\.aar"' \
  | sed -E 's/.*"(https[^"]+)"/\1/' | grep -vE 'static|java' | head -n1 || true)"
if [[ -z "$url" ]]; then
  echo "No .aar asset in release v${VERSION}. Assets:" >&2
  echo "$release_json" | grep -oE '"name": *"[^"]+"' >&2
  exit 1
fi

echo "Downloading $url"
mkdir -p "$(dirname "$DEST")"
curl -fsSL -o "$DEST" "$url"
echo "$VERSION" > "$DEST.version"
sha256sum "$DEST"
