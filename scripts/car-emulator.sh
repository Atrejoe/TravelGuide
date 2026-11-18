#!/usr/bin/env bash
# Boots an Android Automotive emulator and takes the car screenshots on it (car-screenshots.sh).
# Run by the Android workflow, with KVM enabled and the debug APK built.
#
# Usage: scripts/car-emulator.sh <dir with the debug APK> <output dir>
set -euo pipefail

# adb and the emulator, from the SDK this script installs them in.
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"
# One place for the virtual device, for the tool that creates it and the emulator that starts it.
export ANDROID_AVD_HOME="$RUNNER_TEMP/avd"
mkdir -p "$ANDROID_AVD_HOME"

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

# The newest x86_64 "Android Automotive with Google APIs" image on offer. Newer tools list packages
# as system-images/android-35-ext15/android-automotive/x86_64, older ones with semicolons.
image=$("$tools/sdkmanager" --list 2> /dev/null \
  | grep -oE 'system-images[;/]android-[0-9]+(-ext[0-9]+)?[;/]android-automotive[;/]x86_64' \
  | tr '/' ';' | sort -u -V -t ';' -k 2,2 | tail -n 1 || true)
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
echo no | "$tools/avdmanager" create avd --name car --package "$image" ${device:+--device "$device"} --force
if [ ! -f "$ANDROID_AVD_HOME/car.ini" ]; then
  echo "::error::No virtual device was created in $ANDROID_AVD_HOME."
  ls -la "$ANDROID_AVD_HOME" "$HOME/.android" 2> /dev/null || true
  exit 1
fi

echo "Virtual devices: $("$ANDROID_HOME/emulator/emulator" -list-avds | tr '\n' ' ')"
log="$RUNNER_TEMP/emulator.log"
"$ANDROID_HOME/emulator/emulator" -avd car -no-window -gpu swiftshader_indirect -noaudio \
  -no-boot-anim -no-snapshot -camera-back none > "$log" 2>&1 &
emulator=$!
trap 'adb emu kill > /dev/null 2>&1 || true; kill "$emulator" 2> /dev/null || true' EXIT

# Up to 15 minutes for the car to boot; stop at once, with its log, when the emulator quits.
echo "Waiting for the car to boot…"
booted=false
for _ in $(seq 1 180); do
  if ! kill -0 "$emulator" 2> /dev/null; then
    echo "::error::The emulator stopped."
    tail -n 80 "$log"
    exit 1
  fi
  if [ "$(adb shell getprop sys.boot_completed 2> /dev/null | tr -d '\r')" = 1 ]; then
    booted=true
    break
  fi
  sleep 5
done
if [ "$booted" != true ]; then
  echo "::error::The car did not finish booting in 15 minutes."
  tail -n 80 "$log"
  exit 1
fi
echo "Booted."
sleep 30 # the car launcher and media center settle

"$(dirname "$0")/car-screenshots.sh" "$@"
