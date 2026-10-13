#!/usr/bin/env bash
set -euo pipefail

serial="${1:?Usage: scripts/run-android-tests-ci.sh DEVICE_SERIAL}"
if timeout 12m "$(dirname "$0")/run-android-tests.sh" "$serial"; then
  exit 0
else
  result=$?
fi

# The emulator action executes each script line independently. Keep the failure
# handling in this script so diagnostics finish before it tears down the device.
timeout 15s adb -s "$serial" logcat -d > android-instrumentation-logcat.txt || true
timeout 15s adb -s "$serial" shell screencap -p /sdcard/kmpwright-failure.png || true
timeout 15s adb -s "$serial" pull /sdcard/kmpwright-failure.png android-instrumentation-failure.png || true
exit "$result"
