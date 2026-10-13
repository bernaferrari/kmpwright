#!/usr/bin/env bash
set -euo pipefail

./gradlew \
  :kmpwright-core:checkKotlinAbi \
  :kmpwright-core:assembleAndroidMain \
  :kmpwright-compose:lint \
  :kmpwright-example-shared:assembleAndroidMain \
  :example-android:assembleDebug

# Rebuild the Kotlin demo and check the website's pinned release artifact.
./gradlew :kmpwright-web-demo:syncWebsiteDemo
node website/scripts/prepare-demo.mjs --verify-local

# Browser deadlines must be measured without concurrent Android compilation/lint.
./gradlew :kmpwright-core:jvmTest

if [[ "$(uname -s)" == "Darwin" ]]; then
  ./gradlew \
    :kmpwright-core:linkReleaseFrameworkIosSim \
    :kmpwright-example-shared:linkReleaseFrameworkIosSim
  # Gradle's iosSimTest runs a bare executable that cannot load WKWebView
  # documents; run the full iOS suite (document contracts included) in an
  # app host on a simulator.
  ./gradlew :kmpwright-core:iosSimTest
  ./scripts/run-ios-webkit-tests.sh
else
  echo "Skipping iOS framework build (macOS only)."
fi
