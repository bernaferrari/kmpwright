#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VERSION="${RELEASE_VERSION:-0.0.1}"

if [[ "$(uname -s)" != "Darwin" ]]; then
  echo "Skipping XCFramework and Swift consumer smoke build (requires Xcode on macOS)."
  exit 0
fi

"$ROOT_DIR/gradlew" :kmpwright-core:packageKmpwrightCoreReleaseXCFramework \
  -PreleaseVersion="$VERSION" --no-daemon --no-configuration-cache

XCF_PATH="$ROOT_DIR/kmpwright-core/build/XCFrameworks/release/KmpwrightCore.xcframework"
if [[ ! -d "$XCF_PATH" ]]; then
  echo "KmpwrightCore XCFramework was not produced: $XCF_PATH" >&2
  exit 1
fi

DEVICE_SLICE="$XCF_PATH/ios-arm64"
SIMULATOR_SLICE="$XCF_PATH/ios-arm64-simulator"
DEVICE_FRAMEWORK="$DEVICE_SLICE/KmpwrightCore.framework"
SIMULATOR_FRAMEWORK="$SIMULATOR_SLICE/KmpwrightCore.framework"
DEVICE_BINARY="$DEVICE_FRAMEWORK/KmpwrightCore"
SIMULATOR_BINARY="$SIMULATOR_FRAMEWORK/KmpwrightCore"

for required in "$DEVICE_BINARY" "$SIMULATOR_BINARY" \
  "$DEVICE_FRAMEWORK/Info.plist" "$SIMULATOR_FRAMEWORK/Info.plist"; do
  [[ -e "$required" ]] || { echo "Missing XCFramework member: $required" >&2; exit 1; }
done

[[ "$(lipo -info "$DEVICE_BINARY")" =~ arm64 ]] || {
  echo "The device framework does not contain arm64" >&2
  exit 1
}
SIMULATOR_ARCHES="$(lipo -info "$SIMULATOR_BINARY")"
[[ "$SIMULATOR_ARCHES" =~ arm64 ]] && [[ ! "$SIMULATOR_ARCHES" =~ x86_64 ]] || {
  echo "The simulator framework must contain only arm64: $SIMULATOR_ARCHES" >&2
  exit 1
}

for plist in "$DEVICE_FRAMEWORK/Info.plist" "$SIMULATOR_FRAMEWORK/Info.plist"; do
  minimum_os="$(plutil -extract MinimumOSVersion raw -o - "$plist")"
  [[ "$minimum_os" == "15.0" ]] || {
    echo "Expected MinimumOSVersion 15.0 in $plist, got $minimum_os" >&2
    exit 1
  }
done

# Verify the native archive members as well as the framework metadata. This
# catches a stale/host SDK deployment target hidden behind a correct plist.
verify_archive_build_version() {
  local archive="$1"
  local expected_platform="$2"
  shift 2
  local architecture thin_archive member object_file
  for architecture in "$@"; do
    thin_archive="$TMP_DIR/$(basename "$archive").$architecture"
    if lipo -info "$archive" | grep -q 'Non-fat file'; then
      thin_archive="$archive"
    else
      lipo "$archive" -thin "$architecture" -output "$thin_archive"
    fi
    local found=0
    while IFS= read -r member; do
      [[ "$member" == *.o ]] || continue
      found=1
      object_file="$TMP_DIR/$(basename "$archive").$architecture.${member//[^[:alnum:]._-]/_}"
      ar -p "$thin_archive" "$member" > "$object_file"
      if ! otool -l "$object_file" | awk -v expected="$expected_platform" '
        /LC_BUILD_VERSION/ { in_build_version=1; next }
        in_build_version && $1 == "platform" { platform=$2 }
        in_build_version && $1 == "minos" {
          if (platform == expected && $2 == "15.0") valid=1
          in_build_version=0
        }
        END { exit(valid ? 0 : 1) }
      '; then
        echo "Invalid LC_BUILD_VERSION in $archive $architecture member $member (platform $expected_platform, minos 15.0 required)" >&2
        exit 1
      fi
    done < <(ar -t "$thin_archive")
    [[ "$found" == 1 ]] || { echo "No object members found in $archive $architecture" >&2; exit 1; }
  done
}

TMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/kmpwright-ios-smoke.XXXXXX")"
trap 'rm -rf "$TMP_DIR"' EXIT
verify_archive_build_version "$DEVICE_BINARY" 2 arm64
verify_archive_build_version "$SIMULATOR_BINARY" 7 arm64

# Make a release-shaped asset with normalized timestamps and verify its digest.
# The consumer below only sees the extracted archive, never the Gradle build.
ASSET_DIR="$ROOT_DIR/build/release-assets"
mkdir -p "$ASSET_DIR"
ZIP_PATH="$ASSET_DIR/KmpwrightCore-$VERSION.xcframework.zip"
CHECKSUM_PATH="$ZIP_PATH.sha256"
PACKAGE_DIR="$TMP_DIR/package"
mkdir -p "$PACKAGE_DIR"
cp -R "$XCF_PATH" "$PACKAGE_DIR/KmpwrightCore.xcframework"
find "$PACKAGE_DIR/KmpwrightCore.xcframework" -exec touch -t 200001010000 {} +
(
  cd "$PACKAGE_DIR"
  COPYFILE_DISABLE=1 TZ=UTC zip -X -qry "$ZIP_PATH" KmpwrightCore.xcframework
)
(
  cd "$ASSET_DIR"
  shasum -a 256 "$(basename "$ZIP_PATH")" > "$(basename "$CHECKSUM_PATH")"
  shasum -a 256 -c "$(basename "$CHECKSUM_PATH")"
)

CONSUMER_DIR="$TMP_DIR/consumer"
mkdir -p "$CONSUMER_DIR"
cp -R "$ROOT_DIR/tests/consumers/swift/." "$CONSUMER_DIR/"
unzip -q "$ZIP_PATH" -d "$CONSUMER_DIR"
[[ -d "$CONSUMER_DIR/KmpwrightCore.xcframework" ]] || {
  echo "Packaged XCFramework did not extract into the consumer fixture" >&2
  exit 1
}

COMMON_XCODEBUILD_ARGS=(
  -project "$CONSUMER_DIR/KmpwrightSmoke.xcodeproj"
  -scheme KmpwrightSmoke
  -configuration Release
  -sdk iphonesimulator
  -destination 'generic/platform=iOS Simulator'
  CODE_SIGNING_ALLOWED=NO
  IPHONEOS_DEPLOYMENT_TARGET=15.0
  ONLY_ACTIVE_ARCH=YES
)
for simulator_arch in arm64; do
  xcodebuild "${COMMON_XCODEBUILD_ARGS[@]}" \
    ARCHS="$simulator_arch" \
    SYMROOT="$TMP_DIR/build-$simulator_arch" \
    OBJROOT="$TMP_DIR/obj-$simulator_arch" \
    build
done

# Run the Swift consumer on a simulator: it calls throwing APIs inside do/catch and checks that
# Kotlin exceptions arrive as Swift errors carrying the Kotlin exception (see KmpwrightSmoke.swift).
# Uses KMPWRIGHT_IOS_SIMULATOR (name or UDID) or the first booted iOS simulator; set
# KMPWRIGHT_IOS_SMOKE_RUN=0 to only build.
if [[ "${KMPWRIGHT_IOS_SMOKE_RUN:-1}" != "0" ]]; then
  smoke_simulator="${KMPWRIGHT_IOS_SIMULATOR:-$(xcrun simctl list devices booted --json | python3 -c '
import json, sys
for runtime, devices in json.load(sys.stdin)["devices"].items():
    if "iOS" in runtime:
        for device in devices:
            if device["state"] == "Booted":
                print(device["udid"]); sys.exit(0)
')}"
  if [[ -z "$smoke_simulator" ]]; then
    echo "No booted iOS simulator: skipped running the Swift consumer (boot one or set KMPWRIGHT_IOS_SIMULATOR)."
  else
    smoke_bundle_id="io.github.bernaferrari.kmpwright.smoke"
    smoke_app="$TMP_DIR/build-arm64/Release-iphonesimulator/KmpwrightSmoke.app"
    smoke_log="$TMP_DIR/swift-smoke.log"
    codesign --force --sign - "$smoke_app" >/dev/null
    xcrun simctl install "$smoke_simulator" "$smoke_app"
    set +e
    xcrun simctl launch --console-pty --terminate-running-process "$smoke_simulator" "$smoke_bundle_id" \
      --kmpwright-swift-smoke 2>&1 | tee "$smoke_log"
    set -e
    xcrun simctl uninstall "$smoke_simulator" "$smoke_bundle_id" >/dev/null 2>&1 || true
    grep -q "KMPWRIGHT_SWIFT_SMOKE PASSED" "$smoke_log" || {
      echo "The Swift consumer did not report success on simulator $smoke_simulator" >&2
      exit 1
    }
    echo "Swift consumer ran on simulator $smoke_simulator: Kotlin exceptions are catchable Swift errors."
  fi
fi

echo "KmpwrightCore XCFramework, deterministic asset, checksum, and artifact-only Swift consumer verified: $XCF_PATH"
echo "Release assets: $ZIP_PATH $CHECKSUM_PATH"
