#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPOSITORY="$ROOT_DIR/build/test-repository"
VERSION="${RELEASE_VERSION:-$(sed -n 's/^kmpwrightDevelopmentVersion=//p' "$ROOT_DIR/gradle.properties")}"

if [[ -z "${ANDROID_HOME:-}" && -f "$ROOT_DIR/local.properties" ]]; then
  ANDROID_HOME="$(sed -n 's/^sdk\.dir=//p' "$ROOT_DIR/local.properties" | head -n 1)"
  export ANDROID_HOME
fi

CATALOG="$ROOT_DIR/gradle/libs.versions.toml"
KOTLIN_VERSION="$(sed -n 's/^kotlin = "\([^"]*\)"$/\1/p' "$CATALOG")"
AGP_VERSION="$(sed -n 's/^agp = "\([^"]*\)"$/\1/p' "$CATALOG")"
CONSUMER_BUILD="$ROOT_DIR/tests/consumers/kmp/build.gradle.kts"
for tool_version in "$KOTLIN_VERSION" "$AGP_VERSION"; do
  if ! [[ "$tool_version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    echo "Maven releases require stable Kotlin and AGP, got: $tool_version" >&2
    exit 1
  fi
done
grep -Fq "id(\"org.jetbrains.kotlin.multiplatform\") version \"$KOTLIN_VERSION\"" "$CONSUMER_BUILD"
grep -Fq "id(\"com.android.kotlin.multiplatform.library\") version \"$AGP_VERSION\"" "$CONSUMER_BUILD"

rm -rf "$REPOSITORY"

# Native publications and their consumers require Apple's toolchain.
# Keep the candidate compiler aligned with the independent, stable consumer baseline.
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
    :kmpwright-compose:publishAllPublicationsToLocalTestRepository
  )
fi
"$ROOT_DIR/gradlew" "${PUBLISH_TASKS[@]}" \
  -PreleaseVersion="$VERSION" \
  --no-daemon --no-configuration-cache

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
CONSUMER_TASKS=(compileKotlinJvm compileAndroidMain)
if [[ "$(uname -s)" == "Darwin" ]]; then
  CONSUMER_TASKS+=(compileKotlinIosSimulatorArm64 compileKotlinIosArm64)
fi
"$ROOT_DIR/gradlew" -p "$ROOT_DIR/tests/consumers/kmp" "${CONSUMER_TASKS[@]}" \
  -PkmpwrightVersion="$VERSION" \
  --no-daemon --no-configuration-cache
"$ROOT_DIR/gradlew" -p "$ROOT_DIR/tests/consumers/android" :app:assembleDebug \
  -PkmpwrightVersion="$VERSION" \
  --no-daemon --no-configuration-cache

echo "Published artifacts and independent Android/KMP consumers verified in $REPOSITORY"
