#!/usr/bin/env bash
# Downloads the few OpenStreetMap tiles the README screenshots show (the sample walk around the
# Grote Markt in Haarlem, zoom 14) into one archive that the screenshot test hands to osmdroid as an
# offline tile source. Committed, so screenshots don't depend on the network or on map edits; run
# this again only when the sample location or the map's zoom level in the screenshots changes.
# Tiles © OpenStreetMap contributors (ODbL); the screenshots show the attribution.
#
# Usage: scripts/fetch-screenshot-tiles.sh [output.zip]
set -euo pipefail

out="${1:-$(dirname "$0")/../app/src/test/resources/screenshot-tiles.zip}"
zoom=14
# Covers the map at the sample position (52.3812, 4.6368) with a tile of margin on each side.
xs=(8402 8403 8404)
ys=(5382 5383 5384 5385)
agent="TravelGuide screenshot fixture (https://github.com/Atrejoe/TravelGuide)"

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
for x in "${xs[@]}"; do
  for y in "${ys[@]}"; do
    # osmdroid looks tiles up in an archive as <tile source>/<zoom>/<x>/<y>.png.
    mkdir -p "$work/Mapnik/$zoom/$x"
    curl -fsSL -A "$agent" -o "$work/Mapnik/$zoom/$x/$y.png" "https://tile.openstreetmap.org/$zoom/$x/$y.png"
    sleep 1 # The tile usage policy asks for restraint.
  done
done
mkdir -p "$(dirname "$out")"
out=$(cd "$(dirname "$out")" && pwd)/$(basename "$out")
rm -f "$out"
(cd "$work" && zip -qrX "$out" Mapnik)
echo "Wrote $out ($(du -h "$out" | cut -f1))"
