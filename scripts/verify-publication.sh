#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPOSITORY="$ROOT_DIR/build/test-repository"
VERSION="${RELEASE_VERSION:-0.1.0}"

if [[ -z "${ANDROID_HOME:-}" && -f "$ROOT_DIR/local.properties" ]]; then
  ANDROID_HOME="$(sed -n 's/^sdk\.dir=//p' "$ROOT_DIR/local.properties" | head -n 1)"
  export ANDROID_HOME
fi

rm -rf "$REPOSITORY"

# Publish only JVM/Android artifacts on Linux. Native publications are added on
# macOS by the XCFramework smoke job and require Apple's toolchain.
if [[ "$(uname -s)" == "Darwin" ]]; then
  PUBLISH_TASKS=(
    :kmpwright-core:publishAllPublicationsToLocalTestRepository
    :kmpwright-compose:publishAllPublicationsToLocalTestRepository
  )
else
  PUBLISH_TASKS=(
    :kmpwright-core:publishAndroidPublicationToLocalTestRepository
    :kmpwright-core:publishJvmPublicationToLocalTestRepository
    :kmpwright-core:publishKotlinMultiplatformPublicationToLocalTestRepository
    :kmpwright-compose:publishReleasePublicationToLocalTestRepository
  )
fi
"$ROOT_DIR/gradlew" "${PUBLISH_TASKS[@]}" \
  -PreleaseVersion="$VERSION" \
  --no-daemon --no-configuration-cache --rerun-tasks

for artifact in kmpwright-core kmpwright-compose; do
  artifact_dir="$REPOSITORY/io/github/bernaferrari/$artifact/$VERSION"
  pom="$artifact_dir/$artifact-$VERSION.pom"
  if [[ ! -f "$pom" ]]; then
    echo "Missing published POM: $pom" >&2
    exit 1
  fi
  grep -q '<groupId>io.github.bernaferrari</groupId>' "$pom"
  grep -q "<version>$VERSION</version>" "$pom"
  grep -q 'https://github.com/bernaferrari/kmpwright' "$pom"
  grep -q 'MIT License' "$pom"
  grep -q 'Bernardo Ferrari' "$pom"
  grep -q '<scm>' "$pom"
  sources_jar="$artifact_dir/$artifact-$VERSION-sources.jar"
  javadoc_jar="$artifact_dir/$artifact-$VERSION-javadoc.jar"
  [[ -f "$sources_jar" ]] || { echo "Missing sources jar: $sources_jar" >&2; exit 1; }
  [[ -f "$javadoc_jar" ]] || { echo "Missing javadoc jar: $javadoc_jar" >&2; exit 1; }
done

# These are independent builds: neither fixture includes the root project or
# uses project(":kmpwright-...") dependencies.
"$ROOT_DIR/gradlew" -p "$ROOT_DIR/release/smoke/kmp-consumer" compileKotlinJvm \
  -PkmpwrightVersion="$VERSION" \
  --no-daemon --no-configuration-cache
"$ROOT_DIR/gradlew" -p "$ROOT_DIR/release/smoke/android-consumer" :app:assembleDebug \
  -PkmpwrightVersion="$VERSION" \
  --no-daemon --no-configuration-cache

echo "Published artifacts and independent Android/KMP consumers verified in $REPOSITORY"
