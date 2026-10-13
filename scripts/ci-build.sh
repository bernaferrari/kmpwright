#!/usr/bin/env bash
set -euo pipefail

./gradlew \
  :kmpwright-core:checkKotlinAbi \
  :kmpwright-core:jvmTest \
  :kmpwright-core:assembleAndroidMain \
  :kmpwright-compose:lint \
  :kmpwright-example-shared:assembleAndroidMain \
  :example-android:assembleDebug

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
