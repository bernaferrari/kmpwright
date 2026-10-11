#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
serial="${1:?Usage: scripts/run-android-tests.sh DEVICE_SERIAL}"
cd "$project_dir"
./gradlew :kmpwright-test:assembleDebug :kmpwright-test:assembleDebugAndroidTest
adb -s "$serial" install -r kmpwright-test/build/outputs/apk/debug/kmpwright-test-debug.apk
adb -s "$serial" install -r kmpwright-test/build/outputs/apk/androidTest/debug/kmpwright-test-debug-androidTest.apk
result="$(mktemp "${TMPDIR:-/tmp}/kmpwright-android-tests.XXXXXX")"
trap 'rm -f "$result"' EXIT
# Direct adb invocation must carry the same activity-lifetime argument as Gradle.
adb -s "$serial" shell am instrument -w -e waitForActivitiesToComplete false \
  -e class com.bernaferrari.kmpwright.test.KmpwrightInstrumentedTest \
  com.bernaferrari.kmpwright.test.test/androidx.test.runner.AndroidJUnitRunner | tee "$result"
rg -q '^OK \([0-9]+ tests\)' "$result" || { echo "Android instrumentation did not pass" >&2; exit 1; }
