#!/usr/bin/env bash
set -euo pipefail
project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$project_dir"
output_dir="$project_dir/build/ios-browser-contracts"
mkdir -p "$output_dir"
rm -f "$output_dir/report.json"
port_file="$(mktemp "${TMPDIR:-/tmp}/kmpwright-ios-port.XXXXXX")"
server_pid=""
simulator=""
cleanup() {
  if [[ -n "$server_pid" ]]; then kill "$server_pid" 2>/dev/null || true; fi
  if [[ -n "$simulator" ]]; then
    xcrun simctl shutdown "$simulator" 2>/dev/null || true
    xcrun simctl delete "$simulator" 2>/dev/null || true
  fi
  rm -f "$port_file"
}
trap cleanup EXIT
python3 scripts/ios-browser-fixture.py "$port_file" &
server_pid="$!"
for _ in $(seq 1 100); do [[ -s "$port_file" ]] && break; sleep 0.1; done
port="$(cat "$port_file")"
[[ -n "$port" ]] || { echo "Fixture did not start" >&2; exit 1; }
xcrun simctl list runtimes --json > "$output_dir/runtimes.json"
selection="$(python3 - "$output_dir/runtimes.json" <<'PY'
import json, sys
runtimes = [r for r in json.load(open(sys.argv[1]))['runtimes'] if r['isAvailable'] and r.get('platform') == 'iOS']
runtime = max(runtimes, key=lambda r: tuple(map(int, r['version'].split('.'))))
device = next(d for d in runtime['supportedDeviceTypes'] if d['productFamily'] == 'iPhone')
print(runtime['identifier'], device['identifier'])
PY
)"
read -r runtime device_type <<< "$selection"
simulator="$(xcrun simctl create 'KMPwright Reliability' "$device_type" "$runtime")"
xcrun simctl boot "$simulator"
xcrun simctl bootstatus "$simulator" -b
xcodebuild -project example-ios/KmpwrightExampleApp.xcodeproj -scheme KmpwrightExampleApp \
  -configuration Debug -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath "$output_dir/DerivedData" CODE_SIGNING_ALLOWED=NO build > "$output_dir/build.log" 2>&1
app="$output_dir/DerivedData/Build/Products/Debug-iphonesimulator/KmpwrightExampleApp.app"
# Allow loopback HTTP only in this unsigned test build; the checked-in host plist stays unchanged.
plutil -insert NSAppTransportSecurity -json '{"NSAllowsArbitraryLoadsInWebContent":true}' "$app/Info.plist"
codesign --force --sign - "$app" > "$output_dir/codesign.log" 2>&1
bundle="com.bernaferrari.kmpwright.exampleios"
xcrun simctl install "$simulator" "$app"
SIMCTL_CHILD_KMPWRIGHT_RELIABILITY_BASE_URL="http://127.0.0.1:$port" xcrun simctl launch "$simulator" "$bundle"
container="$(xcrun simctl get_app_container "$simulator" "$bundle" data)"
report="$container/Documents/kmpwright-reliability.json"
for _ in $(seq 1 240); do
  if [[ -s "$report" ]]; then
    cp "$report" "$output_dir/report.json"
    xcrun simctl spawn "$simulator" log show --last 2m --predicate 'process == "KmpwrightExampleApp"' > "$output_dir/app.log" 2>&1 || true
    python3 - "$output_dir/report.json" <<'PY'
import json, sys
report = json.load(open(sys.argv[1]))
print(json.dumps({**report, "error": report.get("error", "").splitlines()[0] if report.get("error") else None}, indent=2))
if report['status'] != 'passed': sys.exit(1)
PY
    exit 0
  fi
  sleep 0.5
done
xcrun simctl spawn "$simulator" log show --last 3m --predicate 'process == "KmpwrightExampleApp"' > "$output_dir/app.log" 2>&1 || true
xcrun simctl io "$simulator" screenshot "$output_dir/timeout.png" || true
echo "WKWebView contracts did not produce a report; see $output_dir" >&2
exit 1
