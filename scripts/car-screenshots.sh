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
# Captures the car screen as <dir>/<name>.png; the directory defaults to the output directory.
shot() {
  local dir=${2:-$out}
  mkdir -p "$dir"
  adb shell screencap -p /sdcard/shot.png
  adb pull /sdcard/shot.png "$dir/$1.png" > /dev/null
  echo "Captured $dir/$1.png"
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

# The car's status bar shows the time of the run, and its dock at the bottom the apps used last,
# which differ from run to run. Keeps the committed screenshot when only those changed, so the
# screenshots PR only proposes it when Travel Guide's own screen did. (Setting the car's clock
# instead cost the guide its network: certificates and name lookups failed afterwards.)
keep_unless_app_changed() {
  local file=$1 committed="$work/committed.png"
  git show "HEAD:$file" > "$committed" 2> /dev/null || return 0
  python3 -m venv "$work/venv" && "$work/venv/bin/pip" install --quiet pillow || return 0
  if "$work/venv/bin/python" "$(dirname "$0")/same-app-area.py" "$committed" "$file"; then
    git checkout -- "$file"
    echo "Only the clock or the dock changed in $file: kept the committed one."
  fi
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
keep_unless_app_changed "$out/car-browse.png"

# A car ships with location off; the emulator's GPS fix only reaches apps once it is on.
location_on() {
  adb shell cmd location set-location-enabled true || true
}

# A drive north through Haarlem at about 50 km/h, one position every 5 seconds: the guide only
# listens for positions once it runs, and needs a few to tell where you are heading and how fast.
drive() {
  local lat
  for step in $(seq 0 "$1"); do
    lat=$(awk -v s="$step" 'BEGIN { printf "%.5f", 52.3760 + s * 0.00062 }')
    adb emu geo fix 4.6402 "$lat" > /dev/null
    sleep 5
  done
}

# "Start the guide": it greets, then tells about the places along the way. Kept for review only
# (the workflow's artifact), not in docs/screenshots, until it reliably shows a story.
location_on
if tap_text "Start the guide"; then
  drive 24
  shot car-playing "${CAR_REVIEW_DIR:-$out}"
  # What the car shows now, for the log: a story's title, or still "Waiting for location…".
  adb shell uiautomator dump /sdcard/ui.xml > /dev/null
  adb pull /sdcard/ui.xml "$work/ui.xml" > /dev/null
  echo "On the car screen: $(grep -o 'text="[^"]\+"' "$work/ui.xml" | sed 's/text="\(.*\)"/\1/' | paste -sd '|' -)"
fi

# Which app browsed the guide, what it was shown, and how the guide got on.
app_pid=$(adb shell pidof "$pkg" | tr -d '\r' | awk '{print $1}' || true)
if [ -n "$app_pid" ]; then
  adb logcat -d --pid="$app_pid" '*:I' | tail -n 60 || true
else
  adb logcat -d -s GuideMediaBrowser:I GuideMedia:W | tail -n 20 || true
fi
