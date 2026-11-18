#!/usr/bin/env bash
# Screenshots of Travel Guide in a car (issue #94), on an Android Automotive emulator: its media
# center shows the guide the way Android Auto and cars with Android built in do, from the same
# media service. The Android workflow runs this inside android-emulator-runner.
#
# Usage: scripts/car-screenshots.sh <dir with the debug APK> <output dir>
set -euo pipefail

apk=$(ls "$1"/*.apk | head -n 1)
out=$2
pkg=io.github.atrejoe.travelguide
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
mkdir -p "$out"

# Via a file on the car: screencap warns about the car's several displays on its output.
shot() {
  adb shell screencap -p /sdcard/shot.png
  adb pull /sdcard/shot.png "$out/$1.png" > /dev/null
  echo "Captured $out/$1.png"
}

# Taps the on-screen element showing exactly this text, as a driver would.
tap_text() {
  adb shell uiautomator dump /sdcard/ui.xml > /dev/null
  adb pull /sdcard/ui.xml "$work/ui.xml" > /dev/null
  local bounds
  bounds=$(grep -o "text=\"$1\"[^>]*bounds=\"[^\"]*\"" "$work/ui.xml" | head -n 1 | sed 's/.*bounds="\([^"]*\)"/\1/')
  if [ -z "$bounds" ]; then
    echo "::warning::'$1' is not on the car screen."
    return 1
  fi
  local x1 y1 x2 y2
  read -r x1 y1 x2 y2 < <(echo "$bounds" | tr -c '0-9' ' ')
  adb shell input tap $(((x1 + x2) / 2)) $(((y1 + y2) / 2))
}

# -g grants location and notifications, as the phone app would ask for them.
adb install -r -g "$apk"
# The Grote Markt in Haarlem, where the phone screenshots walk.
adb emu geo fix 4.6368 52.3812

# Travel Guide opened from the car's app launcher: its list.
adb shell am start -a android.car.intent.action.MEDIA_TEMPLATE \
  --es android.car.intent.extra.MEDIA_COMPONENT "$pkg/.service.GuideMediaBrowserService"
sleep 10
shot car-browse

# A drive north along the Spaarne in Haarlem, one position every 5 seconds: the guide only listens
# for positions once it runs, and needs a few to tell where you are heading.
drive() {
  local lat
  for step in $(seq 0 "$1"); do
    lat=$(awk -v s="$step" 'BEGIN { printf "%.5f", 52.3760 + s * 0.00012 }')
    adb emu geo fix 4.6402 "$lat" > /dev/null
    sleep 5
  done
}

# "Start the guide": it greets, then tells about the places along the way.
if tap_text "Start the guide"; then
  drive 24
  shot car-playing
fi

# Which app browsed the guide, and what it was shown.
adb logcat -d -s GuideMediaBrowser:I GuideMedia:W | tail -n 20 || true
