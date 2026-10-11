#!/usr/bin/env bash
set -euo pipefail

./gradlew \
  :kmpwright-core:jvmTest \
  :kmpwright-core:assembleAndroidMain \
  :kmpwright-compose:lint \
  :kmpwright-example-shared:assembleAndroidMain \
  :example-android:assembleDebug

if [[ "$(uname -s)" == "Darwin" ]]; then
  ./gradlew \
    :kmpwright-core:linkReleaseFrameworkIosSim \
    :kmpwright-example-shared:linkReleaseFrameworkIosSim
else
  echo "Skipping iOS framework build (macOS only)."
fi
