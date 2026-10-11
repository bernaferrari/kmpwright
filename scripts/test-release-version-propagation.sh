#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="$(mktemp "${TMPDIR:-/tmp}/kmpwright-release-env.XXXXXX")"
trap 'rm -f "$ENV_FILE"' EXIT

# This models the GitHub Actions contract: the validator writes to GITHUB_ENV,
# and later steps inherit RELEASE_VERSION from the runner shell environment.
GITHUB_ENV="$ENV_FILE" "$ROOT_DIR/scripts/validate-release-tag.sh" v9.8.7 >/dev/null
source "$ENV_FILE"
[[ "${RELEASE_VERSION:-}" == "9.8.7" ]] || {
  echo "validate-release-tag.sh did not propagate RELEASE_VERSION=9.8.7" >&2
  exit 1
}

# Guard against reintroducing a step-level expression that overwrites the
# GITHUB_ENV value with an empty Actions expression.
if rg -n 'RELEASE_VERSION: \$\{\{ env\.RELEASE_VERSION \}\}' \
  "$ROOT_DIR/.github/workflows/release.yml"; then
  echo "release workflow must inherit RELEASE_VERSION from GITHUB_ENV" >&2
  exit 1
fi

rg -q 'kmpwrightVersion.*providers\.gradleProperty\("kmpwrightVersion"\)' \
  "$ROOT_DIR/release/smoke/kmp-consumer/build.gradle.kts"
rg -q -- '-PkmpwrightVersion="\$VERSION"' \
  "$ROOT_DIR/scripts/verify-publication.sh"
echo "Release version propagation contract verified for future version 9.8.7"
