#!/usr/bin/env bash
set -euo pipefail

./gradlew \
  :kmpwright-core:checkKotlinAbi \
  :kmpwright-core:assembleAndroidMain \
  :kmpwright-compose:lint \
  :kmpwright-example-shared:assembleAndroidMain \
  :example-android:assembleDebug

# The docs site ships a committed build of the live demo (Kotlin/JS); fail when it is stale.
./gradlew :kmpwright-web-demo:syncWebsiteDemo
git diff --exit-code --stat -- website/lib/kmpwright-demo || {
  echo "website/lib/kmpwright-demo is stale: run ./gradlew :kmpwright-web-demo:syncWebsiteDemo and commit it." >&2
  exit 1
}

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
