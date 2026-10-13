#!/usr/bin/env bash
# Runs the complete kmpwright-core iOS test suite inside an app host on a simulator.
#
# `./gradlew :kmpwright-core:iosSimTest` launches a bare executable, and WebKit
# only starts a WebContent process for app bundles, so the WKWebView document
# contracts (IOSWebKitDocumentTest) are excluded there. This script links the
# same test binary, wraps it in a minimal ad-hoc signed .app, installs it on a
# simulator and runs every test (or KMPWRIGHT_IOS_TEST_FILTER) with the
# Kotlin/Native test runner. It exits non-zero if any test fails, if the
# runner crashes, or if no test summary is printed.
#
# Simulator selection: KMPWRIGHT_IOS_SIMULATOR (name or UDID), else the first
# booted iOS simulator, else a temporary iPhone simulator on the newest runtime
# (deleted afterwards).
set -euo pipefail
project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$project_dir"

bundle_id="com.bernaferrari.kmpwright.coretests"
filter="${KMPWRIGHT_IOS_TEST_FILTER:-}"
work_dir="$(mktemp -d "${TMPDIR:-/tmp}/kmpwright-ios-tests.XXXXXX")"
log="$work_dir/test.log"
simulator=""
created_simulator=""
installed=""

cleanup() {
  if [[ -n "$installed" ]]; then xcrun simctl uninstall "$simulator" "$bundle_id" >/dev/null 2>&1 || true; fi
  if [[ -n "$created_simulator" ]]; then
    xcrun simctl shutdown "$created_simulator" >/dev/null 2>&1 || true
    xcrun simctl delete "$created_simulator" >/dev/null 2>&1 || true
  fi
  if [[ -f "$log" && -n "${KMPWRIGHT_IOS_TEST_LOG:-}" ]]; then cp "$log" "$KMPWRIGHT_IOS_TEST_LOG" || true; fi
  rm -rf "$work_dir"
}
trap cleanup EXIT

./gradlew :kmpwright-core:linkDebugTestIosSim
binary="$project_dir/kmpwright-core/build/bin/iosSim/debugTest/test.kexe"
[[ -x "$binary" ]] || { echo "Test binary not found: $binary" >&2; exit 1; }

if [[ -n "${KMPWRIGHT_IOS_SIMULATOR:-}" ]]; then
  simulator="$KMPWRIGHT_IOS_SIMULATOR"
else
  simulator="$(xcrun simctl list devices booted --json | python3 -c '
import json, sys
for runtime, devices in json.load(sys.stdin)["devices"].items():
    if "iOS" in runtime:
        for device in devices:
            if device["state"] == "Booted":
                print(device["udid"]); sys.exit(0)
')"
fi
if [[ -z "$simulator" ]]; then
  read -r runtime device_type <<< "$(xcrun simctl list runtimes --json | python3 -c '
import json, sys
runtimes = [r for r in json.load(sys.stdin)["runtimes"] if r["isAvailable"] and r.get("platform") == "iOS"]
runtime = max(runtimes, key=lambda r: tuple(map(int, r["version"].split("."))))
device = next(d for d in runtime["supportedDeviceTypes"] if d["productFamily"] == "iPhone")
print(runtime["identifier"], device["identifier"])
')"
  created_simulator="$(xcrun simctl create "KMPwright iOS Tests" "$device_type" "$runtime")"
  simulator="$created_simulator"
fi
xcrun simctl boot "$simulator" >/dev/null 2>&1 || true
xcrun simctl bootstatus "$simulator" -b >/dev/null

app="$work_dir/KmpwrightCoreTests.app"
mkdir -p "$app"
cp "$binary" "$app/KmpwrightCoreTests"
cat > "$app/Info.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>CFBundleIdentifier</key><string>$bundle_id</string>
  <key>CFBundleExecutable</key><string>KmpwrightCoreTests</string>
  <key>CFBundleName</key><string>KmpwrightCoreTests</string>
  <key>CFBundlePackageType</key><string>APPL</string>
  <key>CFBundleShortVersionString</key><string>1.0</string>
  <key>CFBundleVersion</key><string>1</string>
  <key>CFBundleSupportedPlatforms</key><array><string>iPhoneSimulator</string></array>
  <key>LSRequiresIPhoneOS</key><true/>
  <key>MinimumOSVersion</key><string>15.0</string>
  <key>UIDeviceFamily</key><array><integer>1</integer><integer>2</integer></array>
</dict>
</plist>
PLIST
codesign --force --sign - "$app" >/dev/null

xcrun simctl uninstall "$simulator" "$bundle_id" >/dev/null 2>&1 || true
xcrun simctl install "$simulator" "$app"
installed="1"

args=()
if [[ -n "$filter" ]]; then args+=("--ktest_filter=$filter"); fi
echo "Running iOS tests in an app host on simulator $simulator${filter:+ (filter: $filter)}"
# --console-pty streams the test runner's stdout and waits for it to exit.
set +e
xcrun simctl launch --console-pty --terminate-running-process "$simulator" "$bundle_id" ${args[@]+"${args[@]}"} 2>&1 | tee "$log"
launch_status="${PIPESTATUS[0]}"
set -e

# The runner prints a GoogleTest-style summary; never trust a missing one.
python3 - "$log" "$launch_status" <<'PY'
import re, sys
text = open(sys.argv[1], errors="replace").read().replace("\r", "")
launch_status = int(sys.argv[2])
passed = re.findall(r"^\[  PASSED  \] (\d+) tests?", text, re.M)
failed = re.findall(r"^\[  FAILED  \] (\d+) tests?", text, re.M)
ran = re.findall(r"^\[==========\] (\d+) tests? from \d+ test (?:cases|suites?) ran", text, re.M)
if not ran:
    print("iOS tests did not report a summary (runner crashed or did not start)", file=sys.stderr)
    sys.exit(1)
total = int(ran[-1]); ok = int(passed[-1]) if passed else 0; bad = int(failed[-1]) if failed else 0
print(f"iOS app-hosted tests: {total} ran, {ok} passed, {bad} failed")
if bad or ok != total or total == 0 or launch_status != 0:
    sys.exit(1)
PY
