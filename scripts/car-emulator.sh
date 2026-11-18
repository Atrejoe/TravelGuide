#!/usr/bin/env bash
# Boots an Android Automotive emulator and takes the car screenshots on it (car-screenshots.sh).
# Run by the Android workflow, with KVM enabled and the debug APK built.
#
# Usage: scripts/car-emulator.sh <dir with the debug APK> <output dir>
set -euo pipefail

# Answers the licence questions. Wrapped, because `yes` fails with a broken pipe once the command
# stops reading, which would fail the pipeline under pipefail.
agree() { yes 2> /dev/null || true; }

tools="$ANDROID_HOME/cmdline-tools/latest/bin"
# The automotive system images are listed in a newer repository format than older command-line
# tools can read, so update them first. With "latest" already there, the update lands in
# "latest-2".
agree | "$tools/sdkmanager" --install "cmdline-tools;latest" > /dev/null || true
if [ -x "$ANDROID_HOME/cmdline-tools/latest-2/bin/sdkmanager" ]; then
  tools="$ANDROID_HOME/cmdline-tools/latest-2/bin"
fi
"$tools/sdkmanager" --version

# The newest x86_64 Android Automotive image on offer, whatever it is called.
image=$("$tools/sdkmanager" --list 2> /dev/null \
  | grep -o 'system-images;android-[0-9]*;android-automotive[a-z-]*;x86_64' \
  | grep -v distant | sort -u -V -t ';' -k 2,2 | tail -n 1 || true)
if [ -z "$image" ]; then
  echo "::error::No Android Automotive system image is listed."
  "$tools/sdkmanager" --list 2> /dev/null | grep -i automotive || true
  exit 1
fi
echo "Using $image"
agree | "$tools/sdkmanager" --install emulator platform-tools "$image" > /dev/null
# A car display, when the SDK has one; else the image's default.
device=$("$tools/avdmanager" list device --compact 2> /dev/null | grep -m 1 '^automotive' || true)
echo "Display: ${device:-default}"
echo no | "$tools/avdmanager" create avd --name car --package "$image" ${device:+--device "$device"} --force > /dev/null

"$ANDROID_HOME/emulator/emulator" -avd car -no-window -gpu swiftshader_indirect -noaudio \
  -no-boot-anim -no-snapshot -camera-back none > "$RUNNER_TEMP/emulator.log" 2>&1 &
emulator=$!
trap 'adb emu kill > /dev/null 2>&1 || true; kill "$emulator" 2> /dev/null || true' EXIT

adb wait-for-device
echo "Waiting for the car to boot…"
timeout 900 bash -c 'until [ "$(adb shell getprop sys.boot_completed 2> /dev/null | tr -d "\r")" = 1 ]; do sleep 5; done' \
  || { tail -n 50 "$RUNNER_TEMP/emulator.log"; exit 1; }
sleep 30 # the car launcher and media center settle

"$(dirname "$0")/car-screenshots.sh" "$@"
